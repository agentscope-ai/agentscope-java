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
package io.agentscope.core.live.session;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.transport.CloseInfo;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * LiveSession - a simple delegate object for live communication.
 *
 * <p>This class is a thin wrapper that delegates all operations to functions provided by the Model
 * layer. The Model layer handles all formatter processing and WebSocket interaction internally.
 *
 * <p>Responsibility boundaries:
 *
 * <ul>
 *   <li>✅ Message sending (delegated to sendFunction)
 *   <li>✅ Event receiving (delegated to eventFlux)
 *   <li>✅ Connection state notification
 *   <li>✅ Session configuration updates (delegated to updateConfigFunction)
 *   <li>❌ Formatter processing (handled by Model)
 *   <li>❌ WebSocket interaction (handled by Model)
 *   <li>❌ Reconnection logic (handled by LiveAgent)
 *   <li>❌ Tool call processing (handled by LiveAgent)
 * </ul>
 */
public class LiveSession {

    private final String sessionId;
    private final String providerName;

    // Function-based delegates (injected by Model layer)
    private final Function<Msg, Mono<Void>> sendFunction;
    private final Flux<LiveEvent> eventFlux;
    private final Supplier<Boolean> isOpenSupplier;
    private final Supplier<Mono<Void>> closeSupplier;
    private final Supplier<CloseInfo> closeInfoSupplier;
    private final Function<LiveConfig, Mono<Void>> updateConfigFunction;

    // State management
    private final AtomicReference<ConnectionState> connectionState =
            new AtomicReference<>(ConnectionState.CONNECTING);
    private final Sinks.Many<ConnectionStateEvent> connectionStateSink =
            Sinks.many().multicast().onBackpressureBuffer(256);

    // Metadata
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    // Session resumption handle (for Gemini/Doubao)
    private volatile String resumptionHandle;

    /**
     * Create a LiveSession with function-based delegates.
     *
     * @param sessionId Session ID
     * @param providerName Provider name (e.g., "dashscope", "openai")
     * @param sendFunction Function to send messages (handles formatter internally)
     * @param eventFlux Flux of events (already parsed by formatter in Model)
     * @param isOpenSupplier Supplier to check if connection is open
     * @param closeSupplier Supplier to close the connection
     * @param closeInfoSupplier Supplier to get close info
     * @param updateConfigFunction Function to update session configuration
     */
    public LiveSession(
            String sessionId,
            String providerName,
            Function<Msg, Mono<Void>> sendFunction,
            Flux<LiveEvent> eventFlux,
            Supplier<Boolean> isOpenSupplier,
            Supplier<Mono<Void>> closeSupplier,
            Supplier<CloseInfo> closeInfoSupplier,
            Function<LiveConfig, Mono<Void>> updateConfigFunction) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
        this.providerName = Objects.requireNonNull(providerName, "providerName cannot be null");
        this.sendFunction = Objects.requireNonNull(sendFunction, "sendFunction cannot be null");
        this.eventFlux = Objects.requireNonNull(eventFlux, "eventFlux cannot be null");
        this.isOpenSupplier =
                Objects.requireNonNull(isOpenSupplier, "isOpenSupplier cannot be null");
        this.closeSupplier = Objects.requireNonNull(closeSupplier, "closeSupplier cannot be null");
        this.closeInfoSupplier =
                Objects.requireNonNull(closeInfoSupplier, "closeInfoSupplier cannot be null");
        this.updateConfigFunction =
                Objects.requireNonNull(updateConfigFunction, "updateConfigFunction cannot be null");
    }

    /**
     * Get session ID.
     *
     * @return session identifier
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Check if session is active.
     *
     * @return true if session is active and can send/receive messages
     */
    public boolean isActive() {
        return isOpenSupplier.get() && connectionState.get() == ConnectionState.CONNECTED;
    }

    /**
     * Get provider name.
     *
     * @return provider name (e.g., "dashscope", "openai", "gemini", "doubao")
     */
    public String getProviderName() {
        return providerName;
    }

    /**
     * Send a single message.
     *
     * @param msg message to send (AudioBlock/TextBlock/ImageBlock/ControlBlock)
     * @return completion signal
     */
    public Mono<Void> send(Msg msg) {
        return Mono.defer(
                () -> {
                    if (!isActive()) {
                        return Mono.error(new IllegalStateException("Session is not active"));
                    }
                    return sendFunction.apply(msg);
                });
    }

    /**
     * Send a stream of messages.
     *
     * @param messages message stream
     * @return completion signal
     */
    public Mono<Void> send(Flux<Msg> messages) {
        return messages.concatMap(this::send).then();
    }

    /**
     * Receive event stream.
     *
     * <p>The events are already parsed by the formatter in Model layer. When connection is lost,
     * the stream will emit error or complete signal.
     *
     * @return event stream
     */
    public Flux<LiveEvent> receive() {
        return eventFlux;
    }

    /**
     * Interrupt current response.
     *
     * @return completion signal
     */
    public Mono<Void> interrupt() {
        return send(Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build());
    }

    /**
     * Update session configuration.
     *
     * @param newConfig new configuration
     * @return completion signal
     */
    public Mono<Void> updateConfig(LiveConfig newConfig) {
        return Mono.defer(
                () -> {
                    if (!isActive()) {
                        return Mono.error(new IllegalStateException("Session is not active"));
                    }
                    return updateConfigFunction.apply(newConfig);
                });
    }

    /**
     * Close session.
     *
     * @return completion signal
     */
    public Mono<Void> close() {
        connectionState.set(ConnectionState.CLOSED);
        connectionStateSink.tryEmitComplete();
        return closeSupplier.get();
    }

    /**
     * Get current connection state.
     *
     * @return current connection state
     */
    public ConnectionState getConnectionState() {
        return connectionState.get();
    }

    /**
     * Subscribe to connection state changes.
     *
     * @return connection state event stream
     */
    public Flux<ConnectionStateEvent> connectionStateChanges() {
        return connectionStateSink.asFlux();
    }

    /**
     * Get session metadata.
     *
     * @return metadata map
     */
    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    /**
     * Set metadata.
     *
     * @param key Key
     * @param value Value
     */
    public void setMetadata(String key, String value) {
        metadata.put(key, value);
    }

    /**
     * Get session resumption handle (for Gemini/Doubao).
     *
     * @return Resumption handle, or null if not supported or not set
     */
    public String getResumptionHandle() {
        return resumptionHandle;
    }

    /**
     * Set session resumption handle.
     *
     * @param handle Resumption handle
     */
    public void setResumptionHandle(String handle) {
        this.resumptionHandle = handle;
    }

    /**
     * Mark connection as established.
     *
     * <p>Called by Model layer when handshake is complete.
     */
    public void markConnected() {
        ConnectionState oldState = connectionState.getAndSet(ConnectionState.CONNECTED);
        if (oldState != ConnectionState.CONNECTED) {
            connectionStateSink.tryEmitNext(ConnectionStateEvent.connected());
        }
    }

    /**
     * Mark connection as disconnected.
     *
     * <p>Called by Model layer when connection error or close occurs.
     *
     * @param reason Disconnect reason
     * @param error Optional error that caused disconnect
     */
    public void markDisconnected(String reason, Throwable error) {
        connectionState.set(ConnectionState.DISCONNECTED);
        connectionStateSink.tryEmitNext(ConnectionStateEvent.disconnected(reason, error));
    }

    /**
     * Get close info from the underlying connection.
     *
     * @return CloseInfo, or null if not available
     */
    public CloseInfo getCloseInfo() {
        return closeInfoSupplier.get();
    }
}
