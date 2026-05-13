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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebSocket connection interface.
 *
 * <p>Represents an active WebSocket connection with send/receive capabilities. Uses generic type
 * parameter to support different message formats:
 *
 * <ul>
 *   <li>{@code WebSocketConnection<String>} - Text protocol (JSON over WebSocket)
 *   <li>{@code WebSocketConnection<byte[]>} - Binary protocol
 * </ul>
 *
 * <p>Error handling: All methods return Mono/Flux that propagate errors through Reactor's error
 * channel. Errors are wrapped in {@link WebSocketTransportException} with connection context.
 *
 * <p>Logging: Implementations should log at appropriate levels:
 *
 * <ul>
 *   <li>INFO: Connection established/closed
 *   <li>DEBUG: Message send/receive operations
 *   <li>TRACE: Detailed message content (size, preview)
 *   <li>ERROR: Connection errors, send/receive failures
 * </ul>
 *
 * <p>Usage example (text protocol):
 *
 * <pre>{@code
 * WebSocketTransport client = JdkWebSocketTransport.create();
 * client.connect(request, String.class)
 *     .flatMapMany(connection -> {
 *         // Send JSON message
 *         connection.send("{\"type\":\"config\"}").subscribe();
 *
 *         // Receive JSON messages
 *         return connection.receive();
 *     })
 *     .subscribe(
 *         json -> handleMessage(json),
 *         error -> handleError(error)  // WebSocketTransportException with context
 *     );
 * }</pre>
 *
 * <p>Usage example (binary protocol):
 *
 * <pre>{@code
 * client.connect(request, byte[].class)
 *     .flatMapMany(connection -> {
 *         // Send binary message
 *         connection.send(binaryData).subscribe();
 *
 *         // Receive binary messages
 *         return connection.receive();
 *     })
 *     .subscribe(
 *         data -> handleBinaryMessage(data),
 *         error -> handleError(error)
 *     );
 * }</pre>
 *
 * @param <T> Message type: String for text protocols, byte[] for binary protocols
 */
public interface WebSocketConnection<T> {

    /**
     * Send a message.
     *
     * <p>Implementation should:
     *
     * <ul>
     *   <li>Log at DEBUG level before sending
     *   <li>Log at TRACE level with message size
     *   <li>Wrap errors in WebSocketTransportException using onErrorMap
     *   <li>Log errors at ERROR level with full context
     * </ul>
     *
     * @param data Message data (String or byte[])
     * @return Mono that completes when send is done, or emits WebSocketTransportException on error
     */
    Mono<Void> send(T data);

    /**
     * Receive message stream.
     *
     * <p>The returned Flux:
     *
     * <ul>
     *   <li>Completes when connection is closed normally
     *   <li>Emits error (WebSocketTransportException) on connection failure
     *   <li>Logs each received message at TRACE level
     * </ul>
     *
     * <p>Implementation should:
     *
     * <ul>
     *   <li>Log received messages at TRACE level with size
     *   <li>Wrap errors in WebSocketTransportException using onErrorMap
     *   <li>Log errors at ERROR level with connection context
     * </ul>
     *
     * @return Message stream (String or byte[])
     */
    Flux<T> receive();

    /**
     * Close the connection.
     *
     * <p>Implementation should:
     *
     * <ul>
     *   <li>Log at INFO level with close code and reason
     *   <li>Send WebSocket close frame with code 1000 (normal closure)
     *   <li>Clean up resources
     * </ul>
     *
     * @return Mono that completes when connection is closed
     */
    Mono<Void> close();

    /**
     * Check if connection is open.
     *
     * @return true if connection is open
     */
    boolean isOpen();

    /**
     * Get close information (if closed).
     *
     * @return Close info, or null if not closed
     */
    CloseInfo getCloseInfo();
}
