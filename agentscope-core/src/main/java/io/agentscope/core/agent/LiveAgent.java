/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agent;

import io.agentscope.core.live.LiveAgentState;
import io.agentscope.core.live.LiveAgentStateEvent;
import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.ReconnectFailedException;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.config.ReconnectConfig;
import io.agentscope.core.live.session.ConnectionState;
import io.agentscope.core.live.session.ConnectionStateEvent;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.live.transport.WebSocketTransportException;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.LiveModel;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Independent Live Agent implementation (POC version).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Reconnection logic and strategy</li>
 *   <li>Tool call processing</li>
 *   <li>Session/memory management</li>
 *   <li>Error recovery</li>
 * </ul>
 *
 * <p>Not responsible for (handled by LiveModel/LiveSession):
 * <ul>
 *   <li>WebSocket connection management</li>
 *   <li>Message format conversion</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * LiveAgent agent = LiveAgent.builder()
 *     .name("voice-assistant")
 *     .systemPrompt("You are a friendly voice assistant.")
 *     .liveModel(dashScopeLiveModel)
 *     .toolkit(toolkit)
 *     .build();
 *
 * agent.live(inputFlux, LiveConfig.defaults())
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveableAgent
 * @see LiveEvent
 */
public class LiveAgent implements LiveableAgent {
    private static final Logger log = LoggerFactory.getLogger(LiveAgent.class);

    // ==================== Core Dependencies ====================

    private final String name;
    private final String description;
    private final String systemPrompt;
    private final LiveModel liveModel;
    private final Toolkit toolkit;
    private final Memory memory;

    // ==================== Runtime State ====================

    private final AtomicReference<LiveSession> currentSession = new AtomicReference<>();
    private final AtomicReference<LiveAgentState> state =
            new AtomicReference<>(LiveAgentState.DISCONNECTED);
    private final Sinks.Many<LiveAgentStateEvent> stateEventSink =
            Sinks.many().multicast().onBackpressureBuffer(256);

    // Session resumption handle (for Gemini/Doubao)
    private volatile String currentResumptionHandle;

    // ==================== Constructor ====================

    private LiveAgent(Builder builder) {
        this.name = builder.name;
        this.description = builder.description;
        this.systemPrompt = builder.systemPrompt;
        this.liveModel = builder.liveModel;
        this.toolkit = builder.toolkit;
        this.memory = builder.memory;
    }

    // ==================== Public API ====================

    /**
     * Get agent name.
     *
     * @return agent name
     */
    public String getName() {
        return name;
    }

    /**
     * Get agent description.
     *
     * @return agent description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Get current agent state.
     *
     * @return current state
     */
    public LiveAgentState getState() {
        return state.get();
    }

    /**
     * Subscribe to agent state change events.
     *
     * @return state change event stream
     */
    public Flux<LiveAgentStateEvent> stateChanges() {
        return stateEventSink.asFlux();
    }

    // ==================== LiveableAgent Implementation ====================

    @Override
    public Flux<LiveEvent> live(Flux<Msg> input, LiveConfig config) {
        // Merge system prompt into config
        LiveConfig effectiveConfig = mergeConfig(config);

        // Extract tool definitions from Toolkit
        List<ToolSchema> toolSchemas = toolkit != null ? toolkit.getToolSchemas() : null;

        return liveModel
                .connect(effectiveConfig, toolSchemas)
                .flatMapMany(
                        liveSession -> {
                            currentSession.set(liveSession);
                            // Session created but handshake may not be complete yet
                            // State will be updated to CONNECTED when markConnected() is called
                            updateState(
                                    LiveAgentState.CONNECTING,
                                    "Session created, handshake in progress");

                            // Monitor underlying connection state changes (trigger reconnection)
                            // This also handles the CONNECTED state transition after handshake
                            Flux<LiveEvent> connectionMonitor =
                                    liveSession
                                            .connectionStateChanges()
                                            .flatMap(
                                                    event ->
                                                            handleConnectionStateChange(
                                                                    event, effectiveConfig));

                            // Upstream task: read messages from input, send to liveSession (with
                            // reconnection support)
                            Mono<Void> upstreamTask =
                                    input.flatMap(msg -> sendWithRetry(msg, effectiveConfig))
                                            .then();

                            // Downstream task: receive events from liveSession, process tool calls
                            // Error handling: recoverable errors attempt reconnection,
                            // unrecoverable
                            // errors propagate to user
                            Flux<LiveEvent> downstreamTask =
                                    liveSession
                                            .receive()
                                            .flatMap(event -> processEvent(liveSession, event))
                                            .onErrorResume(
                                                    error -> {
                                                        // Determine if recoverable
                                                        if (isRecoverable(error, effectiveConfig)) {
                                                            return doReconnect(effectiveConfig, 1)
                                                                    .flatMapMany(
                                                                            success -> {
                                                                                if (success) {
                                                                                    LiveSession
                                                                                            newSession =
                                                                                                    currentSession
                                                                                                            .get();
                                                                                    return newSession
                                                                                            .receive()
                                                                                            .flatMap(
                                                                                                    e ->
                                                                                                            processEvent(
                                                                                                                    newSession,
                                                                                                                    e));
                                                                                }
                                                                                return Flux.error(
                                                                                        new ReconnectFailedException(
                                                                                                "Reconnect"
                                                                                                    + " failed"
                                                                                                    + " after"
                                                                                                    + " error:"
                                                                                                    + " "
                                                                                                        + error
                                                                                                                .getMessage(),
                                                                                                error));
                                                                            });
                                                        }
                                                        // Unrecoverable, propagate to user
                                                        return Flux.error(error);
                                                    });

                            // Agent state events
                            Flux<LiveEvent> agentStateEvents =
                                    stateEventSink.asFlux().map(this::convertToLiveEvent);

                            // Execute all tasks concurrently
                            return Flux.merge(
                                            connectionMonitor,
                                            agentStateEvents,
                                            upstreamTask.thenMany(Flux.empty()),
                                            downstreamTask)
                                    .doOnTerminate(
                                            () -> {
                                                liveSession.close().subscribe();
                                                updateState(
                                                        LiveAgentState.CLOSED, "Session closed");
                                            });
                        });
    }

    // ==================== Config Merging ====================

    /**
     * Merge agent config into LiveConfig.
     *
     * <p>Only merges systemPrompt into instructions. Tool definitions are obtained from Toolkit by
     * LiveModel during connect.
     */
    private LiveConfig mergeConfig(LiveConfig config) {
        LiveConfig.Builder builder = config.toBuilder();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.instructions(systemPrompt);
        }

        return builder.build();
    }

    // ==================== Reconnection Logic ====================

    /**
     * Handle underlying connection state changes, trigger reconnection.
     */
    private Flux<LiveEvent> handleConnectionStateChange(
            ConnectionStateEvent event, LiveConfig config) {

        // Handle CONNECTED state - handshake complete
        if (event.currentState() == ConnectionState.CONNECTED) {
            updateState(LiveAgentState.CONNECTED, "Handshake complete");
            return Flux.just(LiveEvent.connectionState("CONNECTED", "Session ready", true));
        }

        if (event.currentState() == ConnectionState.DISCONNECTED) {
            updateState(LiveAgentState.DISCONNECTED, event.reason());

            // Decide whether to auto-reconnect based on provider capability
            boolean supportsNativeRecovery = liveModel.supportsNativeRecovery();

            if (config.isAutoReconnect() && supportsNativeRecovery) {
                // Gemini/Doubao: supports native recovery, auto-reconnect
                return doReconnect(config, 1)
                        .flatMapMany(
                                success -> {
                                    if (success) {
                                        return Flux.just(LiveEvent.reconnected());
                                    }
                                    return Flux.just(
                                            LiveEvent.sessionEnded("RECONNECT_FAILED", false));
                                });
            } else {
                // DashScope/OpenAI: does not support native recovery, end stream directly
                return Flux.just(
                        LiveEvent.connectionState("DISCONNECTED", event.reason(), false),
                        LiveEvent.sessionEnded("CONNECTION_LOST", false));
            }
        }

        return Flux.empty();
    }

    /**
     * Execute reconnection (exponential backoff + jitter).
     */
    private Mono<Boolean> doReconnect(LiveConfig config, int attempt) {
        ReconnectConfig reconnectConfig = config.getReconnectConfig();

        if (attempt > reconnectConfig.getMaxAttempts()) {
            updateState(LiveAgentState.FAILED, "Max attempts exceeded");
            return Mono.just(false);
        }

        updateState(LiveAgentState.RECONNECTING, "Attempt " + attempt);
        emitReconnectingEvent(attempt, reconnectConfig.getMaxAttempts());

        Duration delay = reconnectConfig.getDelayForAttempt(attempt);
        List<ToolSchema> toolSchemas = toolkit != null ? toolkit.getToolSchemas() : null;

        return Mono.delay(delay)
                .then(liveModel.connect(config, toolSchemas))
                .flatMap(
                        newSession -> {
                            currentSession.set(newSession);

                            // Native recovery is handled automatically by provider
                            updateState(LiveAgentState.RESUMING, "Using native resume");
                            updateState(LiveAgentState.RECOVERED, "Native resume success");
                            return Mono.just(true);
                        })
                .onErrorResume(error -> doReconnect(config, attempt + 1));
    }

    /**
     * Send message with reconnection support.
     *
     * <p>If the session is still in CONNECTING state (handshake not complete), this method will
     * wait for the session to become active before sending.
     */
    private Mono<Void> sendWithRetry(Msg msg, LiveConfig config) {
        // Save user message to Memory
        if (memory != null && shouldSaveToMemory(msg)) {
            memory.addMessage(msg);
        }

        return waitForSessionActive()
                .then(
                        Mono.defer(
                                () -> {
                                    LiveSession liveSession = currentSession.get();
                                    if (liveSession == null || !liveSession.isActive()) {
                                        return Mono.error(
                                                new IllegalStateException("Session not active"));
                                    }
                                    return liveSession.send(msg);
                                }))
                .onErrorResume(
                        error -> {
                            if (config.isAutoReconnect() && !state.get().isRecovering()) {
                                return doReconnect(config, 1)
                                        .flatMap(
                                                success -> {
                                                    if (success) {
                                                        LiveSession liveSession =
                                                                currentSession.get();
                                                        return liveSession.send(msg);
                                                    }
                                                    return Mono.error(
                                                            new ReconnectFailedException(
                                                                    "Reconnect failed"));
                                                });
                            }
                            return Mono.error(error);
                        });
    }

    /**
     * Wait for the session to become active.
     *
     * <p>If the session is already active, returns immediately. Otherwise, waits for the
     * CONNECTED state event from connectionStateChanges().
     */
    private Mono<Void> waitForSessionActive() {
        return Mono.defer(
                () -> {
                    LiveSession liveSession = currentSession.get();
                    if (liveSession == null) {
                        return Mono.error(new IllegalStateException("No session available"));
                    }
                    if (liveSession.isActive()) {
                        return Mono.empty();
                    }
                    // Wait for CONNECTED state
                    return liveSession
                            .connectionStateChanges()
                            .filter(event -> event.currentState() == ConnectionState.CONNECTED)
                            .next()
                            .then();
                });
    }

    // ==================== Event Processing ====================

    /**
     * Process downstream events, including tool calls.
     */
    private Flux<LiveEvent> processEvent(LiveSession liveSession, LiveEvent event) {
        // Update session resumption handle (Gemini/Doubao)
        if (event.type() == LiveEventType.SESSION_RESUMPTION) {
            String handle = event.getMetadata("live.session.resumption_handle");
            if (handle != null) {
                currentResumptionHandle = handle;
            }
        }

        // Tool call processing
        if (event.type() == LiveEventType.TOOL_CALL && toolkit != null) {
            return handleToolCall(liveSession, event);
        }

        // Save assistant message to Memory
        if (memory != null && shouldSaveToMemory(event)) {
            memory.addMessage(event.message());
        }

        return Flux.just(event);
    }

    /**
     * Handle tool call.
     */
    private Flux<LiveEvent> handleToolCall(LiveSession liveSession, LiveEvent event) {
        ToolUseBlock toolUse = extractToolUseBlock(event.message());
        if (toolUse == null) {
            return Flux.just(event);
        }

        // Save tool call message to Memory
        if (memory != null) {
            memory.addMessage(event.message());
        }

        return Flux.just(event) // First emit tool call event
                .concatWith(
                        toolkit.callTool(
                                        io.agentscope.core.tool.ToolCallParam.builder()
                                                .toolUseBlock(toolUse)
                                                .build())
                                .flatMapMany(
                                        result -> {
                                            // Build tool result message
                                            Msg resultMsg =
                                                    Msg.builder()
                                                            .role(MsgRole.TOOL)
                                                            .content(
                                                                    ToolResultBlock.builder()
                                                                            .id(toolUse.getId())
                                                                            .name(toolUse.getName())
                                                                            .output(
                                                                                    result
                                                                                            .getOutput())
                                                                            .build())
                                                            .build();

                                            // Save tool result to Memory
                                            if (memory != null) {
                                                memory.addMessage(resultMsg);
                                            }

                                            // Send tool result to LiveSession
                                            return liveSession
                                                    .send(resultMsg)
                                                    .thenMany(
                                                            Flux.just(
                                                                    LiveEvent.toolCall(
                                                                            resultMsg, true)));
                                        }));
    }

    // ==================== Helper Methods ====================

    private void updateState(LiveAgentState newState, String reason) {
        LiveAgentState oldState = state.getAndSet(newState);
        stateEventSink.tryEmitNext(LiveAgentStateEvent.of(oldState, newState, reason));
    }

    private void emitReconnectingEvent(int attempt, int maxAttempts) {
        stateEventSink.tryEmitNext(
                LiveAgentStateEvent.reconnecting(
                        state.get(), attempt, "Attempt " + attempt + " of " + maxAttempts));
    }

    private LiveEvent convertToLiveEvent(LiveAgentStateEvent event) {
        return LiveEvent.connectionState(
                event.currentState().name(), event.reason(), liveModel.supportsNativeRecovery());
    }

    private boolean shouldSaveToMemory(Msg msg) {
        return msg != null && (msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.CONTROL);
    }

    private boolean shouldSaveToMemory(LiveEvent event) {
        return event != null
                && event.message() != null
                && event.message().getRole() == MsgRole.ASSISTANT
                && event.type() != LiveEventType.TOOL_CALL;
    }

    private ToolUseBlock extractToolUseBlock(Msg msg) {
        if (msg == null || msg.getContent() == null || msg.getContent().isEmpty()) {
            return null;
        }
        return msg.getContent().stream()
                .filter(block -> block instanceof ToolUseBlock)
                .map(block -> (ToolUseBlock) block)
                .findFirst()
                .orElse(null);
    }

    /**
     * Determine if error is recoverable.
     *
     * <p>Recoverable conditions:
     * <ol>
     *   <li>Auto-reconnect is enabled</li>
     *   <li>Provider supports native recovery (Gemini/Doubao)</li>
     *   <li>Error type is connection-related exception</li>
     * </ol>
     *
     * @param error the error
     * @param config the config
     * @return true if recovery can be attempted
     */
    private boolean isRecoverable(Throwable error, LiveConfig config) {
        // 1. Must have auto-reconnect enabled
        if (!config.isAutoReconnect()) {
            return false;
        }
        // 2. Must support native recovery
        if (!liveModel.supportsNativeRecovery()) {
            return false;
        }
        // 3. Must be connection-related exception (protocol parsing exceptions are not recoverable)
        return error instanceof WebSocketTransportException;
    }

    // ==================== Builder ====================

    /**
     * Create a new builder for LiveAgent.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for constructing LiveAgent instances. */
    public static class Builder {
        private String name;
        private String description;
        private String systemPrompt;
        private LiveModel liveModel;
        private Toolkit toolkit;
        private Memory memory;

        /**
         * Set the agent name.
         *
         * @param name agent name (required)
         * @return this builder for chaining
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Set the agent description.
         *
         * @param description agent description
         * @return this builder for chaining
         */
        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * Set the system prompt.
         *
         * @param systemPrompt system prompt for the agent
         * @return this builder for chaining
         */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /**
         * Set the live model.
         *
         * @param liveModel live model (required)
         * @return this builder for chaining
         */
        public Builder liveModel(LiveModel liveModel) {
            this.liveModel = liveModel;
            return this;
        }

        /**
         * Set the toolkit for tool execution.
         *
         * @param toolkit toolkit instance
         * @return this builder for chaining
         */
        public Builder toolkit(Toolkit toolkit) {
            this.toolkit = toolkit;
            return this;
        }

        /**
         * Set the memory for conversation history.
         *
         * @param memory memory instance
         * @return this builder for chaining
         */
        public Builder memory(Memory memory) {
            this.memory = memory;
            return this;
        }

        /**
         * Build the LiveAgent instance.
         *
         * @return new LiveAgent instance
         * @throws NullPointerException if name or liveModel is null
         * @throws IllegalArgumentException if name is blank
         */
        public LiveAgent build() {
            Objects.requireNonNull(name, "name is required");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name cannot be blank");
            }
            Objects.requireNonNull(liveModel, "liveModel is required");
            return new LiveAgent(this);
        }
    }
}
