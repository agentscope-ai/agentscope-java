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
package io.agentscope.core.live.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
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
 * WebSocketClient client = JdkWebSocketClient.create();
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
public class JdkWebSocketClient implements WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(JdkWebSocketClient.class);

    private final HttpClient httpClient;

    private JdkWebSocketClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Create a client with default configuration.
     *
     * @return JdkWebSocketClient instance
     */
    public static JdkWebSocketClient create() {
        return new JdkWebSocketClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build());
    }

    /**
     * Create a client with custom HttpClient.
     *
     * @param httpClient Custom HttpClient instance
     * @return JdkWebSocketClient instance
     */
    public static JdkWebSocketClient create(HttpClient httpClient) {
        return new JdkWebSocketClient(httpClient);
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
        log.debug("JdkWebSocketClient shutdown called");
    }
}
