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
import io.agentscope.core.model.transport.ProxyType;
import io.agentscope.core.model.transport.WebSocketTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * WebSocket client implementation based on JDK 11+ HttpClient.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>No additional dependencies, uses JDK built-in API
 *   <li>Client instance is reusable
 *   <li>Thread-safe
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * WebSocketTransport client = JdkWebSocketTransport.create();
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
public class JdkWebSocketTransport implements WebSocketTransport {

    private static final Logger log = LoggerFactory.getLogger(JdkWebSocketTransport.class);

    private final HttpClient httpClient;

    private JdkWebSocketTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Create a client with default configuration.
     *
     * @return JdkWebSocketTransport instance
     */
    public static JdkWebSocketTransport create() {
        return new JdkWebSocketTransport(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /**
     * Create a client with custom HttpClient.
     *
     * @param httpClient Custom HttpClient instance
     * @return JdkWebSocketTransport instance
     */
    public static JdkWebSocketTransport create(HttpClient httpClient) {
        return new JdkWebSocketTransport(httpClient);
    }

    /**
     * Create a client with custom configuration.
     *
     * <p>This method supports proxy configuration and other advanced settings.
     *
     * @param config the WebSocket client configuration
     * @return JdkWebSocketTransport instance
     */
    public static JdkWebSocketTransport create(WebSocketTransportConfig config) {
        return new JdkWebSocketTransport(buildClient(config));
    }

    private static HttpClient buildClient(WebSocketTransportConfig config) {
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_2)
                        .connectTimeout(config.getConnectTimeout());

        // Configure SSL (optionally ignore certificate verification)
        if (config.isIgnoreSsl()) {
            log.error(
                    "SSL certificate verification has been disabled for this WebSocket client. This"
                        + " configuration must only be used for local development or testing with"
                        + " self-signed certificates. Do not disable SSL verification in production"
                        + " environments, as it exposes connections to man-in-the-middle attacks.");
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        null,
                        new TrustManager[] {createTrustAllTrustManager()},
                        new SecureRandom());
                builder.sslContext(sslContext);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new RuntimeException("Failed to create trust-all SSL context", e);
            }
        }

        // Configure proxy
        if (config.getProxyConfig() != null) {
            ProxyConfig proxyConfig = config.getProxyConfig();

            if (proxyConfig.getNonProxyHosts() != null
                    && !proxyConfig.getNonProxyHosts().isEmpty()) {
                builder.proxy(new NonProxyHostsSelector(proxyConfig));
            } else {
                builder.proxy(
                        ProxySelector.of(
                                new InetSocketAddress(
                                        proxyConfig.getHost(), proxyConfig.getPort())));
            }

            // Note: JDK HttpClient does not support SOCKS5 authentication directly.
            // For HTTP proxy authentication, use Authenticator.
            if (proxyConfig.hasAuthentication() && proxyConfig.getType() == ProxyType.HTTP) {
                final String username = proxyConfig.getUsername();
                final String password = proxyConfig.getPassword();
                builder.authenticator(
                        new java.net.Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                if (getRequestorType() == RequestorType.PROXY) {
                                    return new PasswordAuthentication(
                                            username, password.toCharArray());
                                }
                                return null;
                            }
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

                    WebSocket.Builder builder = httpClient.newWebSocketBuilder();

                    // Set connection timeout
                    if (request.getConnectTimeout() != null) {
                        builder.connectTimeout(request.getConnectTimeout());
                    }

                    // Add request headers
                    request.getHeaders().forEach(builder::header);

                    // Create connection handler
                    JdkWebSocketConnection<T> connection =
                            new JdkWebSocketConnection<>(request.getUrl(), messageType);

                    builder.buildAsync(URI.create(request.getUrl()), connection.getListener())
                            .whenComplete(
                                    (webSocket, error) -> {
                                        if (error != null) {
                                            log.error(
                                                    "Failed to connect to WebSocket: {}",
                                                    request.getUrl(),
                                                    error);
                                            sink.error(
                                                    new WebSocketTransportException(
                                                            "Failed to connect",
                                                            error,
                                                            request.getUrl(),
                                                            "CONNECTING",
                                                            request.getHeaders()));
                                        } else {
                                            log.info(
                                                    "WebSocket connected successfully: {}",
                                                    request.getUrl());
                                            connection.setWebSocket(webSocket);
                                            sink.success(connection);
                                        }
                                    });
                });
    }

    @Override
    public void shutdown() {
        // JDK HttpClient does not require explicit shutdown
        // If a custom ExecutorService is used, it may need to be closed
        log.debug("JdkWebSocketTransport shutdown called");
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
