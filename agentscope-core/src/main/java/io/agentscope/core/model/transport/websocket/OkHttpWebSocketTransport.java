/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.model.transport.websocket;

import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.WebSocketTransport;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * WebSocket client implementation based on OkHttp.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Mature and stable HTTP client library
 *   <li>Built-in ping/pong heartbeat (pingInterval)
 *   <li>Better connection pool management
 *   <li>Client instance is reusable
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * WebSocketTransport client = OkHttpWebSocketTransport.create();
 *
 * WebSocketRequest request = WebSocketRequest.builder("wss://api.example.com/ws")
 *     .header("Authorization", "Bearer token")
 *     .build();
 *
 * client.connect(request, String.class)
 *     .flatMapMany(conn -> {
 *         conn.send("{\"type\":\"config\"}").subscribe();
 *         return conn.receive();
 *     })
 *     .subscribe(data -> handle(data));
 * }</pre>
 */
public class OkHttpWebSocketTransport implements WebSocketTransport {

    private static final Logger log = LoggerFactory.getLogger(OkHttpWebSocketTransport.class);

    private final OkHttpClient client;

    private OkHttpWebSocketTransport(OkHttpClient client) {
        this.client = client;
    }

    /**
     * Create a client with default configuration.
     *
     * @return OkHttpWebSocketTransport instance
     */
    public static OkHttpWebSocketTransport create() {
        return new OkHttpWebSocketTransport(
                new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .pingInterval(30, TimeUnit.SECONDS) // Heartbeat interval
                        .build());
    }

    /**
     * Create a client with custom OkHttpClient.
     *
     * @param client Custom OkHttpClient instance
     * @return OkHttpWebSocketTransport instance
     */
    public static OkHttpWebSocketTransport create(OkHttpClient client) {
        return new OkHttpWebSocketTransport(client);
    }

    /**
     * Create a client with custom configuration.
     *
     * <p>This method supports proxy configuration and other advanced settings.
     *
     * @param config the WebSocket client configuration
     * @return OkHttpWebSocketTransport instance
     */
    public static OkHttpWebSocketTransport create(WebSocketTransportConfig config) {
        return new OkHttpWebSocketTransport(buildClient(config));
    }

    private static OkHttpClient buildClient(WebSocketTransportConfig config) {
        OkHttpClient.Builder builder =
                new OkHttpClient.Builder()
                        .connectTimeout(
                                config.getConnectTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .readTimeout(config.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .writeTimeout(config.getWriteTimeout().toMillis(), TimeUnit.MILLISECONDS)
                        .pingInterval(config.getPingInterval().toMillis(), TimeUnit.MILLISECONDS);

        // Configure SSL (optionally ignore certificate verification)
        if (config.isIgnoreSsl()) {
            log.error(
                    "SSL certificate verification has been disabled for this WebSocket client. This"
                        + " configuration must only be used for local development or testing with"
                        + " self-signed certificates. Do not disable SSL verification in production"
                        + " environments, as it exposes connections to man-in-the-middle attacks.");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                X509TrustManager trustManager = createTrustAllTrustManager();
                sslContext.init(null, new TrustManager[] {trustManager}, new SecureRandom());
                builder.sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                        .hostnameVerifier((hostname, session) -> true);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to create trust-all SSL socket factory", e);
            }
        }

        // Configure proxy
        if (config.getProxyConfig() != null) {
            ProxyConfig proxyConfig = config.getProxyConfig();

            if (proxyConfig.getNonProxyHosts() != null
                    && !proxyConfig.getNonProxyHosts().isEmpty()) {
                builder.proxySelector(new NonProxyHostsSelector(proxyConfig));
            } else {
                builder.proxy(proxyConfig.toJavaProxy());
            }

            if (proxyConfig.hasAuthentication()) {
                final String username = proxyConfig.getUsername();
                final String password = proxyConfig.getPassword();
                builder.proxyAuthenticator(
                        (route, response) -> {
                            if (response.request().header("Proxy-Authorization") != null) {
                                return null; // Avoid infinite retry
                            }
                            String credential = Credentials.basic(username, password);
                            return response.request()
                                    .newBuilder()
                                    .header("Proxy-Authorization", credential)
                                    .build();
                        });
            }
        }

        return builder.build();
    }

    private static X509TrustManager createTrustAllTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Trust all certificates
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // Trust all certificates
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Override
    public <T> Mono<WebSocketConnection<T>> connect(
            WebSocketRequest request, Class<T> messageType) {
        return Mono.create(
                sink -> {
                    log.info("Connecting to WebSocket: {}", request.getUrl());

                    Request.Builder requestBuilder = new Request.Builder().url(request.getUrl());

                    // Add request headers
                    request.getHeaders().forEach(requestBuilder::addHeader);

                    Request okRequest = requestBuilder.build();
                    OkHttpWebSocketConnection<T> connection =
                            new OkHttpWebSocketConnection<>(request.getUrl(), messageType);

                    WebSocket webSocket =
                            client.newWebSocket(
                                    okRequest,
                                    new WebSocketListener() {
                                        @Override
                                        public void onOpen(WebSocket webSocket, Response response) {
                                            log.info(
                                                    "WebSocket connected successfully: {}",
                                                    request.getUrl());
                                            connection.setWebSocket(webSocket);
                                            sink.success(connection);
                                        }

                                        @Override
                                        public void onMessage(WebSocket webSocket, String text) {
                                            log.trace(
                                                    "Received text message, size: {} chars",
                                                    text.length());
                                            connection.onMessage(
                                                    text.getBytes(StandardCharsets.UTF_8));
                                        }

                                        @Override
                                        public void onMessage(
                                                WebSocket webSocket, ByteString bytes) {
                                            log.trace(
                                                    "Received binary message, size: {} bytes",
                                                    bytes.size());
                                            connection.onMessage(bytes.toByteArray());
                                        }

                                        @Override
                                        public void onClosing(
                                                WebSocket webSocket, int code, String reason) {
                                            log.debug(
                                                    "WebSocket closing: {} [code={}, reason={}]",
                                                    request.getUrl(),
                                                    code,
                                                    reason);
                                            webSocket.close(code, reason);
                                        }

                                        @Override
                                        public void onClosed(
                                                WebSocket webSocket, int code, String reason) {
                                            log.info(
                                                    "WebSocket closed: {} [code={}, reason={}]",
                                                    request.getUrl(),
                                                    code,
                                                    reason);
                                            connection.onClosed(code, reason);
                                        }

                                        @Override
                                        public void onFailure(
                                                WebSocket webSocket,
                                                Throwable t,
                                                Response response) {
                                            if (!connection.isInitialized()) {
                                                // Connection establishment failed
                                                log.error(
                                                        "Failed to connect to WebSocket: {}",
                                                        request.getUrl(),
                                                        t);
                                                sink.error(
                                                        new WebSocketTransportException(
                                                                "Failed to connect",
                                                                t,
                                                                request.getUrl(),
                                                                "CONNECTING",
                                                                request.getHeaders()));
                                            } else {
                                                // Connection interrupted
                                                log.error(
                                                        "WebSocket error: {}", request.getUrl(), t);
                                                connection.onError(t);
                                            }
                                        }
                                    });
                });
    }

    @Override
    public void shutdown() {
        log.debug("OkHttpWebSocketTransport shutdown called");
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * ProxySelector that respects non-proxy hosts configuration.
     */
    private static class NonProxyHostsSelector extends ProxySelector {
        private final ProxyConfig proxyConfig;
        private final List<Proxy> proxyList;

        NonProxyHostsSelector(ProxyConfig proxyConfig) {
            this.proxyConfig = proxyConfig;
            this.proxyList = Collections.singletonList(proxyConfig.toJavaProxy());
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null || uri.getHost() == null) {
                return proxyList;
            }
            if (proxyConfig.shouldBypass(uri.getHost())) {
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            return proxyList;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            log.warn("Proxy connection failed: uri={}, address={}", uri, sa, ioe);
        }
    }
}
