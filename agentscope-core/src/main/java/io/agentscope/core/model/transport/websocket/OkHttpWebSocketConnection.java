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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.WebSocket;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * WebSocket connection implementation based on OkHttp WebSocket.
 *
 * <p>Supports both text (String) and binary (byte[]) message types through generic type parameter.
 *
 * @param <T> Message type: String for text protocol, byte[] for binary protocol
 */
public class OkHttpWebSocketConnection<T> implements WebSocketConnection<T> {

    private static final Logger log = LoggerFactory.getLogger(OkHttpWebSocketConnection.class);

    private final String url;
    private final Class<T> messageType;
    private volatile WebSocket webSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile CloseInfo closeInfo;

    // Message receive sink (unicast - single subscriber only)
    private final Sinks.Many<T> messageSink = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * Create a new OkHttpWebSocketConnection.
     *
     * @param url WebSocket URL
     * @param messageType Message type class (String.class or byte[].class)
     */
    OkHttpWebSocketConnection(String url, Class<T> messageType) {
        this.url = url;
        this.messageType = messageType;
    }

    /**
     * Set the WebSocket instance (internal use).
     *
     * @param webSocket WebSocket instance
     */
    void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
        this.initialized.set(true);
    }

    /**
     * Check if the connection is initialized (internal use).
     *
     * @return true if initialized
     */
    boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Handle received message (internal use).
     *
     * @param data Message data as byte array
     */
    @SuppressWarnings("unchecked")
    void onMessage(byte[] data) {
        if (messageType == byte[].class) {
            messageSink.tryEmitNext((T) data);
        } else if (messageType == String.class) {
            messageSink.tryEmitNext((T) new String(data, StandardCharsets.UTF_8));
        }
    }

    /**
     * Handle connection closed (internal use).
     *
     * @param code Close code
     * @param reason Close reason
     */
    void onClosed(int code, String reason) {
        closed.set(true);
        closeInfo = new CloseInfo(code, reason);
        messageSink.tryEmitComplete();
    }

    /**
     * Handle error (internal use).
     *
     * @param error Error that occurred
     */
    void onError(Throwable error) {
        closed.set(true);
        closeInfo = new CloseInfo(CloseInfo.ABNORMAL_CLOSURE, error.getMessage());
        messageSink.tryEmitError(
                new WebSocketTransportException("WebSocket error", error, url, "ERROR"));
    }

    @Override
    public Mono<Void> send(T data) {
        return Mono.<Void>create(
                        sink -> {
                            if (webSocket == null || closed.get()) {
                                sink.error(
                                        new WebSocketTransportException(
                                                "Connection is not open",
                                                null,
                                                url,
                                                closed.get() ? "CLOSED" : "NOT_CONNECTED"));
                                return;
                            }

                            boolean success;
                            if (data instanceof String text) {
                                log.debug("Sending text message, size: {} chars", text.length());
                                success = webSocket.send(text);
                            } else if (data instanceof byte[] bytes) {
                                log.debug("Sending binary message, size: {} bytes", bytes.length);
                                success = webSocket.send(ByteString.of(bytes));
                            } else {
                                sink.error(
                                        new IllegalArgumentException(
                                                "Unsupported message type: "
                                                        + data.getClass().getName()));
                                return;
                            }

                            if (!success) {
                                log.error("Failed to send message: {}", url);
                                sink.error(
                                        new WebSocketTransportException(
                                                "Failed to send message", null, url, "OPEN"));
                            } else {
                                sink.success();
                            }
                        })
                .onErrorMap(
                        e ->
                                e instanceof WebSocketTransportException
                                        ? e
                                        : new WebSocketTransportException(
                                                "Send failed", e, url, "OPEN"));
    }

    @Override
    public Flux<T> receive() {
        return messageSink
                .asFlux()
                .onErrorMap(
                        e ->
                                e instanceof WebSocketTransportException
                                        ? e
                                        : new WebSocketTransportException(
                                                "Receive failed", e, url, "OPEN"));
    }

    @Override
    public Mono<Void> close() {
        return Mono.create(
                sink -> {
                    if (webSocket != null && !closed.getAndSet(true)) {
                        log.info("Closing WebSocket connection: {}", url);
                        webSocket.close(CloseInfo.NORMAL_CLOSURE, "");
                        closeInfo = new CloseInfo(CloseInfo.NORMAL_CLOSURE, "");
                    }
                    sink.success();
                });
    }

    @Override
    public boolean isOpen() {
        return webSocket != null && !closed.get();
    }

    @Override
    public CloseInfo getCloseInfo() {
        return closeInfo;
    }
}
