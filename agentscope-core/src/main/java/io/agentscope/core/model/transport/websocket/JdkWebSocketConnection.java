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

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * WebSocket connection implementation based on JDK WebSocket.
 *
 * <p>Supports both text (String) and binary (byte[]) message types through generic type parameter.
 *
 * @param <T> Message type: String for text protocol, byte[] for binary protocol
 */
public class JdkWebSocketConnection<T> implements WebSocketConnection<T> {

    private static final Logger log = LoggerFactory.getLogger(JdkWebSocketConnection.class);

    private final String url;
    private final Class<T> messageType;
    private volatile WebSocket webSocket;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile CloseInfo closeInfo;

    // Message receive sink (unicast - single subscriber only)
    private final Sinks.Many<T> messageSink = Sinks.many().unicast().onBackpressureBuffer();

    // Buffer for accumulating fragmented messages
    private final StringBuilder textBuffer = new StringBuilder();
    private ByteBuffer binaryBuffer;

    // Send lock (JDK WebSocket is not thread-safe for sending)
    private final ReentrantLock sendLock = new ReentrantLock();

    // JDK WebSocket.Listener implementation
    private final WebSocket.Listener listener =
            new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    log.debug("WebSocket opened: {}", url);
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                    textBuffer.append(data);
                    if (last) {
                        String message = textBuffer.toString();
                        textBuffer.setLength(0);
                        log.trace("Received text message, size: {} bytes", message.length());
                        emitMessage(message);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<?> onBinary(
                        WebSocket webSocket, ByteBuffer data, boolean last) {
                    if (binaryBuffer == null) {
                        binaryBuffer = ByteBuffer.allocate(data.remaining());
                    }
                    // Expand buffer if needed
                    if (binaryBuffer.remaining() < data.remaining()) {
                        ByteBuffer newBuffer =
                                ByteBuffer.allocate(binaryBuffer.position() + data.remaining());
                        binaryBuffer.flip();
                        newBuffer.put(binaryBuffer);
                        binaryBuffer = newBuffer;
                    }
                    binaryBuffer.put(data);

                    if (last) {
                        binaryBuffer.flip();
                        byte[] bytes = new byte[binaryBuffer.remaining()];
                        binaryBuffer.get(bytes);
                        binaryBuffer = null;
                        emitMessage(bytes);
                    }
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public CompletionStage<?> onClose(
                        WebSocket webSocket, int statusCode, String reason) {
                    log.info("WebSocket closed: {} [code={}, reason={}]", url, statusCode, reason);
                    closed.set(true);
                    closeInfo = new CloseInfo(statusCode, reason);
                    messageSink.tryEmitComplete();
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    log.error("WebSocket error: {}", url, error);
                    closed.set(true);
                    closeInfo = new CloseInfo(CloseInfo.ABNORMAL_CLOSURE, error.getMessage());
                    messageSink.tryEmitError(
                            new WebSocketTransportException(
                                    "WebSocket error", error, url, "ERROR"));
                }
            };

    /**
     * Create a new JdkWebSocketConnection.
     *
     * @param url WebSocket URL
     * @param messageType Message type class (String.class or byte[].class)
     */
    JdkWebSocketConnection(String url, Class<T> messageType) {
        this.url = url;
        this.messageType = messageType;
    }

    /**
     * Get the JDK WebSocket.Listener (internal use).
     *
     * @return WebSocket.Listener instance
     */
    WebSocket.Listener getListener() {
        return listener;
    }

    /**
     * Set the WebSocket instance (internal use).
     *
     * @param webSocket WebSocket instance
     */
    void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    @SuppressWarnings("unchecked")
    private void emitMessage(String message) {
        if (messageType == String.class) {
            messageSink.tryEmitNext((T) message);
        } else if (messageType == byte[].class) {
            messageSink.tryEmitNext((T) message.getBytes(StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private void emitMessage(byte[] data) {
        if (messageType == byte[].class) {
            messageSink.tryEmitNext((T) data);
        } else if (messageType == String.class) {
            messageSink.tryEmitNext((T) new String(data, StandardCharsets.UTF_8));
        }
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

                            sendLock.lock();
                            try {
                                CompletableFuture<WebSocket> future;
                                if (data instanceof String text) {
                                    future = webSocket.sendText(text, true);
                                } else if (data instanceof byte[] bytes) {
                                    future = webSocket.sendBinary(ByteBuffer.wrap(bytes), true);
                                } else {
                                    sink.error(
                                            new IllegalArgumentException(
                                                    "Unsupported message type: "
                                                            + data.getClass().getName()));
                                    return;
                                }

                                future.whenComplete(
                                        (ws, error) -> {
                                            if (error != null) {
                                                log.error("Failed to send message: {}", url, error);
                                                sink.error(
                                                        new WebSocketTransportException(
                                                                "Failed to send message",
                                                                error,
                                                                url,
                                                                "OPEN"));
                                            } else {
                                                sink.success();
                                            }
                                        });
                            } finally {
                                sendLock.unlock();
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
                        webSocket
                                .sendClose(CloseInfo.NORMAL_CLOSURE, "")
                                .whenComplete(
                                        (ws, error) -> {
                                            closeInfo = new CloseInfo(CloseInfo.NORMAL_CLOSURE, "");
                                            if (error != null) {
                                                log.warn(
                                                        "Error during WebSocket close: {}",
                                                        url,
                                                        error);
                                                sink.error(error);
                                            } else {
                                                log.info("WebSocket closed: {}", url);
                                                sink.success();
                                            }
                                        });
                    } else {
                        sink.success();
                    }
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
