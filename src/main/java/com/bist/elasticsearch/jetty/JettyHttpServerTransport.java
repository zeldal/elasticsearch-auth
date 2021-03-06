/*
 * Copyright 2011 Sonian Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bist.elasticsearch.jetty;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.PortsRange;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.*;
import org.elasticsearch.transport.BindTransportException;

import java.io.File;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author imotov
 * @author zeldal
 * Migrated to new plugin
 */
public class JettyHttpServerTransport extends AbstractLifecycleComponent<HttpServerTransport> implements HttpServerTransport {

    public static final String TRANSPORT_ATTRIBUTE = "com.bist.elasticsearch.http.jetty.transport";

    private final NetworkService networkService;

    private final String port;

    private final String bindHost;

    private final String guiDir;

    private final String publishHost;

    private final String[] jettyConfig;

    private final String jettyConfigServerId;

    private final Environment environment;

    private final ESLoggerWrapper loggerWrapper;

    private final ClusterName clusterName;

    private final Client client;

    private volatile BoundTransportAddress boundAddress;

    private volatile Server jettyServer;

    private volatile HttpServerAdapter httpServerAdapter;

    @Inject
    public JettyHttpServerTransport(Settings settings, Environment environment, NetworkService networkService,
                                    ESLoggerWrapper loggerWrapper, ClusterName clusterName, Client client) {
        super(settings);
        this.environment = environment;
        this.networkService = networkService;
        this.port = componentSettings.get("port", settings.get("http.port", "9200-9300"));
        this.bindHost = componentSettings.get("bind_host", settings.get("http.bind_host", settings.get("http.host")));
        this.guiDir = componentSettings.get("gui", settings.get("http.gui", settings.get("http.kibana")));
        this.publishHost = componentSettings.get("publish_host", settings.get("http.publish_host", settings.get("http.host")));
        this.jettyConfig = componentSettings.getAsArray("config", new String[]{"jetty.xml"});
        this.jettyConfigServerId = componentSettings.get("server_id", "ESServer");
        this.loggerWrapper = loggerWrapper;
        this.clusterName = clusterName;
        this.client = client;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        PortsRange portsRange = new PortsRange(port);
        final AtomicReference<Exception> lastException = new AtomicReference<Exception>();

        Log.setLog(loggerWrapper);

        portsRange.iterate(new PortsRange.PortCallback() {
            @Override
            public boolean onPortNumber(int portNumber) {
                try {
                    Server server = null;
                    XmlConfiguration lastXmlConfiguration = null;
                    Object[] objs = new Object[jettyConfig.length];
                    Map<String, String> esProperties = jettySettings(bindHost, portNumber);

                    for (int i = 0; i < jettyConfig.length; i++) {
                        String configFile = jettyConfig[i];
                        URL config = environment.resolveConfig(configFile);
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(config);

                        // Make ids of objects created in early configurations available
                        // in the later configurations
                        if (lastXmlConfiguration != null) {
                            xmlConfiguration.getIdMap().putAll(lastXmlConfiguration.getIdMap());
                        } else {
                            xmlConfiguration.getIdMap().put("ESServerTransport", JettyHttpServerTransport.this);
                            xmlConfiguration.getIdMap().put("ESClient", client);
                            xmlConfiguration.getIdMap().put("ESEnvironment",environment);
                        }
                        // Inject elasticsearch properties
                        xmlConfiguration.getProperties().putAll(esProperties);

                        objs[i] = xmlConfiguration.configure();
                        lastXmlConfiguration = xmlConfiguration;
                    }
                    // Find jetty Server with id  jettyConfigServerId
                    Object serverObject = lastXmlConfiguration.getIdMap().get(jettyConfigServerId);
                    if (serverObject != null) {
                        if (serverObject instanceof Server) {
                            server = (Server) serverObject;
                        }
                    } else {
                        // For compatibility - if it's not available, find first available jetty Server
                        for (Object obj : objs) {
                            if (obj instanceof Server) {
                                server = (Server) obj;
                                break;
                            }
                        }
                    }
                    if (server == null) {
                        logger.error("Cannot find server with id [{}] in configuration files [{}]", jettyConfigServerId, jettyConfig);
                        lastException.set(new ElasticsearchException("Cannot find server with id " + jettyConfigServerId));
                        return true;
                    }

                    // Keep it for now for backward compatibility with previous versions of jetty.xml
                    server.setAttribute(TRANSPORT_ATTRIBUTE, JettyHttpServerTransport.this);

//                    addSpecific(server);


                    // Start all lifecycle objects configured by xml configurations
                    for (Object obj : objs) {
                        if (obj instanceof LifeCycle) {
                            LifeCycle lifeCycle = (LifeCycle) obj;
                            if (!lifeCycle.isRunning()) {
                                lifeCycle.start();
                            }
                        }
                    }
                    jettyServer = server;
                    lastException.set(null);
                } catch (BindException e) {
                    lastException.set(e);
                    return false;
                } catch (Exception e) {
                    logger.error("Jetty Startup Failed ", e);
                    lastException.set(e);
                    return true;
                }
                return true;
            }
        });
        if (lastException.get() != null) {
            throw new BindHttpException("Failed to bind to [" + port + "]", lastException.get());
        }
        InetSocketAddress jettyBoundAddress = findFirstInetConnector(jettyServer);
        if (jettyBoundAddress != null) {
            InetSocketAddress publishAddress;
            try {
                publishAddress = new InetSocketAddress(networkService.resolvePublishHostAddress(publishHost), jettyBoundAddress.getPort());
            } catch (Exception e) {
                throw new BindTransportException("Failed to resolve publish address", e);
            }
            this.boundAddress = new BoundTransportAddress(new InetSocketTransportAddress(jettyBoundAddress), new InetSocketTransportAddress(publishAddress));
        } else {
            throw new BindHttpException("Failed to find a jetty connector with Inet transport");
        }
    }

    private InetSocketAddress findFirstInetConnector(Server server) {
        Connector[] connectors = server.getConnectors();
        if (connectors != null) {
            for (Connector connector : connectors) {
                ServerConnector serverConnector = (ServerConnector) connector;
                return new InetSocketAddress(serverConnector.getHost(), serverConnector.getPort());
            }
        }
        return null;
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        if (jettyServer != null) {
            try {
                jettyServer.stop();
            } catch (Exception ex) {
                throw new ElasticsearchException("Cannot stop jetty server", ex);
            }
            jettyServer = null;
        }
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    @Override
    public BoundTransportAddress boundAddress() {
        return this.boundAddress;
    }

    @Override
    public HttpInfo info() {
        return new HttpInfo(boundAddress(), 0);
    }

    @Override
    public HttpStats stats() {
        return new HttpStats(0, 0);
    }

    @Override
    public void httpServerAdapter(HttpServerAdapter httpServerAdapter) {
        this.httpServerAdapter = httpServerAdapter;
    }

    public HttpServerAdapter httpServerAdapter() {
        return httpServerAdapter;
    }

    public Settings settings() {
        return settings;
    }

    public Settings componentSettings() {
        return componentSettings;
    }

    private Map<String, String> jettySettings(String hostAddress, int port) {
        MapBuilder<String, String> jettySettings = MapBuilder.newMapBuilder();
        jettySettings.put("es.home", environment.homeFile().getAbsolutePath());
        jettySettings.put("es.config", environment.configFile().getAbsolutePath());
        jettySettings.put("es.data", getAbsolutePaths(environment.dataFiles()));
        jettySettings.put("es.cluster.data", getAbsolutePaths(environment.dataWithClusterFiles()));
        jettySettings.put("es.cluster", clusterName.value());
        if (hostAddress != null) {
            jettySettings.put("jetty.bind_host", hostAddress);
        }
        for (Map.Entry<String, String> entry : componentSettings.getAsMap().entrySet()) {
            jettySettings.put("jetty." + entry.getKey(), entry.getValue());
        }
        // Override jetty port in case we have a port-range
        jettySettings.put("jetty.port", String.valueOf(port));

        jettySettings.put("jetty.gui",guiDir);
        return jettySettings.immutableMap();
    }

    private String getAbsolutePaths(File[] files) {
        StringBuilder buf = new StringBuilder();
        for (File file : files) {
            if (buf.length() > 0) {
                buf.append(',');
            }
            buf.append(file.getAbsolutePath());
        }
        return buf.toString();
    }

}
