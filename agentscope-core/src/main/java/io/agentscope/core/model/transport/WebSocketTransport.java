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
package io.agentscope.core.model.transport;

import io.agentscope.core.model.transport.websocket.WebSocketConnection;
import io.agentscope.core.model.transport.websocket.WebSocketRequest;
import reactor.core.publisher.Mono;

/**
 * WebSocket client interface.
 *
 * <p>Similar to HTTPClient design:
 *
 * <ul>
 *   <li>Client instance is stateless and reusable
 *   <li>Each connect() creates a new connection
 *   <li>Connection configuration is passed via WebSocketRequest
 *   <li>Supports generic type parameter for message format (String or byte[])
 * </ul>
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@code JdkWebSocketTransport} - Based on JDK 11+ HttpClient
 *   <li>{@code OkHttpWebSocketTransport} - Based on OkHttp
 * </ul>
 *
 * <p>Usage example (text protocol):
 *
 * <pre>{@code
 * // Create client (reusable)
 * WebSocketTransport client = JdkWebSocketTransport.create();
 *
 * // Create connection request
 * WebSocketRequest request = WebSocketRequest.builder("wss://api.openai.com/v1/realtime")
 *     .header("Authorization", "Bearer " + apiKey)
 *     .build();
 *
 * // Establish connection (specify String type)
 * client.connect(request, String.class)
 *     .flatMapMany(connection -> {
 *         // Send JSON configuration
 *         connection.send(sessionConfig).subscribe();
 *         // Receive JSON messages
 *         return connection.receive();
 *     })
 *     .subscribe(json -> handleMessage(json));
 * }</pre>
 *
 * <p>Usage example (binary protocol):
 *
 * <pre>{@code
 * // Establish connection (specify byte[] type)
 * client.connect(request, byte[].class)
 *     .flatMapMany(connection -> {
 *         // Send binary data
 *         connection.send(binaryFrame).subscribe();
 *         // Receive binary data
 *         return connection.receive();
 *     })
 *     .subscribe(data -> handleBinaryMessage(data));
 * }</pre>
 */
public interface WebSocketTransport {

    /**
     * Establish a WebSocket connection.
     *
     * @param <T> Message type: String for text protocol, byte[] for binary protocol
     * @param request Connection request configuration
     * @param messageType Class object for message type (String.class or byte[].class)
     * @return Mono that emits WebSocketConnection on successful connection
     */
    <T> Mono<WebSocketConnection<T>> connect(WebSocketRequest request, Class<T> messageType);

    /**
     * Shutdown the client and release resources.
     *
     * <p>After shutdown, this client should not be used anymore.
     */
    void shutdown();
}
