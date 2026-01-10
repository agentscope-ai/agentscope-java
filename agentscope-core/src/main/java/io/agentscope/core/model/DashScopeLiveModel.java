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
package io.agentscope.core.model;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.LiveProtocolException;
import io.agentscope.core.live.audio.AudioFormat;
import io.agentscope.core.live.audio.DashScopeModality;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.formatter.DashScopeLiveFormatter;
import io.agentscope.core.live.formatter.LiveFormatter;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.live.transport.WebSocketConnection;
import io.agentscope.core.live.transport.WebSocketRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * DashScope Realtime API LiveModel implementation.
 *
 * <p>DashScope characteristics:
 *
 * <ul>
 *   <li>Supports image input (JPG/JPEG, ≤500KB)
 *   <li>Does not support text input
 *   <li>Does not support tool calling
 *   <li>Does not support session resumption
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * DashScopeLiveModel model = DashScopeLiveModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("qwen-omni-turbo-realtime")
 *     .smoothOutput(true)
 *     .webSocketClient(JdkWebSocketClient.create())
 *     .build();
 *
 * model.connect(LiveConfig.builder()
 *         .voice("Cherry")
 *         .instructions("You are a friendly assistant")
 *         .build())
 *     .flatMapMany(session -> session.receive())
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveModelBase
 * @see DashScopeLiveFormatter
 */
public class DashScopeLiveModel extends LiveModelBase {

    /** Default WebSocket URL. */
    private static final String DEFAULT_BASE_URL =
            "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";

    // Connection configuration
    private final String apiKey;
    private final String baseUrl;

    // Audio format configuration
    private final AudioFormat inputAudioFormat;
    private final AudioFormat outputAudioFormat;
    private final DashScopeModality modality;

    // DashScope-specific configuration
    private final Boolean smoothOutput;
    private final Float repetitionPenalty;
    private final Float presencePenalty;
    private final Long seed;
    private final Integer maxTokens;
    private final Integer topK;

    // VAD configuration
    private final boolean vadEnabled;

    private DashScopeLiveModel(Builder builder) {
        super(builder.modelName, builder.webSocketClient);
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        // Audio format: use provided value or auto-detect based on model name
        this.inputAudioFormat =
                builder.inputAudioFormat != null
                        ? builder.inputAudioFormat
                        : AudioFormat.dashScopeDefaultInput();
        this.outputAudioFormat =
                builder.outputAudioFormat != null
                        ? builder.outputAudioFormat
                        : AudioFormat.forDashScopeModel(builder.modelName);
        this.modality =
                builder.modality != null ? builder.modality : DashScopeModality.TEXT_AND_AUDIO;
        this.smoothOutput = builder.smoothOutput;
        this.repetitionPenalty = builder.repetitionPenalty;
        this.presencePenalty = builder.presencePenalty;
        this.seed = builder.seed;
        this.maxTokens = builder.maxTokens;
        this.topK = builder.topK;
        this.vadEnabled = builder.vadEnabled;
    }

    @Override
    protected Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas) {
        // DashScope does not support tool calling, ignore toolSchemas

        WebSocketRequest request =
                WebSocketRequest.builder(buildWebSocketUrl()).headers(buildHeaders()).build();

        DashScopeLiveFormatter formatter = createDashScopeFormatter();
        List<ToolSchema> schemas = toolSchemas != null ? toolSchemas : Collections.emptyList();

        return webSocketClient
                .connect(request, String.class)
                .map(connection -> createLiveSession(connection, formatter, config, schemas));
    }

    /**
     * Create a LiveSession with function-based delegates.
     *
     * <p>This method sets up all the necessary functions and event processing:
     *
     * <ul>
     *   <li>Subscribes to connection.receive() once
     *   <li>Handles handshake internally (SESSION_CREATED → send config → SESSION_UPDATED)
     *   <li>Filters out handshake events from the user-facing stream
     *   <li>Creates send/updateConfig functions that use formatter internally
     * </ul>
     *
     * @param connection WebSocket connection
     * @param formatter Message formatter
     * @param config Session configuration
     * @param toolSchemas Tool schemas (ignored for DashScope)
     * @return LiveSession
     */
    private LiveSession createLiveSession(
            WebSocketConnection<String> connection,
            DashScopeLiveFormatter formatter,
            LiveConfig config,
            List<ToolSchema> toolSchemas) {

        String sessionId = UUID.randomUUID().toString();

        // Create unicast sink for event buffering
        Sinks.Many<LiveEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean configSent = new AtomicBoolean(false);

        // Create the session first (needed for markConnected callback)
        LiveSession session =
                new LiveSession(
                        sessionId,
                        getProviderName(),
                        // sendFunction
                        msg -> {
                            String data = formatter.formatInput(msg);
                            if (data == null) {
                                return Mono.empty();
                            }
                            return connection.send(data);
                        },
                        // eventFlux - filter out handshake events
                        eventSink
                                .asFlux()
                                .filter(
                                        event ->
                                                event.type() != LiveEventType.SESSION_CREATED
                                                        && event.type()
                                                                != LiveEventType.SESSION_UPDATED),
                        // isOpenSupplier
                        connection::isOpen,
                        // closeSupplier
                        connection::close,
                        // closeInfoSupplier
                        connection::getCloseInfo,
                        // updateConfigFunction
                        newConfig -> {
                            String data = formatter.buildSessionConfig(newConfig, toolSchemas);
                            if (data == null) {
                                return Mono.empty();
                            }
                            return connection.send(data);
                        });

        // Subscribe to connection.receive() once and handle messages
        connection
                .receive()
                .subscribe(
                        data ->
                                handleReceivedMessage(
                                        data,
                                        formatter,
                                        config,
                                        toolSchemas,
                                        connection,
                                        eventSink,
                                        configSent,
                                        session),
                        error -> {
                            session.markDisconnected("WebSocket error", error);
                            eventSink.tryEmitError(error);
                        },
                        () -> {
                            eventSink.tryEmitComplete();
                        });

        return session;
    }

    /**
     * Handle received message from WebSocket.
     *
     * <p>This method:
     *
     * <ul>
     *   <li>Parses incoming data using formatter
     *   <li>Handles handshake: sends config after SESSION_CREATED, marks connected after
     *       SESSION_UPDATED
     *   <li>Emits events to the sink for user consumption
     * </ul>
     */
    private void handleReceivedMessage(
            String data,
            DashScopeLiveFormatter formatter,
            LiveConfig config,
            List<ToolSchema> toolSchemas,
            WebSocketConnection<String> connection,
            Sinks.Many<LiveEvent> eventSink,
            AtomicBoolean configSent,
            LiveSession session) {
        try {
            LiveEvent event = formatter.parseOutput(data);

            // Handle handshake: send config after SESSION_CREATED
            if (event.type() == LiveEventType.SESSION_CREATED
                    && configSent.compareAndSet(false, true)) {
                String configMsg = formatter.buildSessionConfig(config, toolSchemas);
                connection.send(configMsg).subscribe();
            }

            // Mark connected after SESSION_UPDATED
            if (event.type() == LiveEventType.SESSION_UPDATED) {
                session.markConnected();
            }

            // Emit all events (filtering happens in eventFlux)
            eventSink.tryEmitNext(event);
        } catch (Exception e) {
            byte[] rawBytes = data != null ? data.getBytes(StandardCharsets.UTF_8) : new byte[0];
            eventSink.tryEmitError(
                    new LiveProtocolException("Failed to parse message", e, rawBytes));
        }
    }

    /**
     * Create DashScope-specific formatter.
     *
     * @return DashScopeLiveFormatter instance
     */
    private DashScopeLiveFormatter createDashScopeFormatter() {
        return new DashScopeLiveFormatter(
                inputAudioFormat,
                outputAudioFormat,
                modality,
                smoothOutput,
                repetitionPenalty,
                presencePenalty,
                seed,
                maxTokens,
                topK,
                vadEnabled);
    }

    @Override
    protected String buildWebSocketUrl() {
        return baseUrl + "?model=" + modelName;
    }

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("X-DashScope-DataInspection", "enable");
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> LiveFormatter<T> createFormatter() {
        return (LiveFormatter<T>)
                new DashScopeLiveFormatter(
                        inputAudioFormat,
                        outputAudioFormat,
                        modality,
                        smoothOutput,
                        repetitionPenalty,
                        presencePenalty,
                        seed,
                        maxTokens,
                        topK,
                        vadEnabled);
    }

    @Override
    public String getProviderName() {
        return "dashscope";
    }

    @Override
    public boolean supportsNativeRecovery() {
        return false; // DashScope does not support session resumption
    }

    /**
     * Extract session ID from SESSION_CREATED event.
     *
     * @param event The session created event
     * @return Session ID, or null if not found
     */
    private String extractSessionId(LiveEvent event) {
        if (event.message() != null && event.message().getMetadata() != null) {
            Object value = event.message().getMetadata().get("live.session.id");
            return value != null ? value.toString() : null;
        }
        return null;
    }

    // ==================== Builder ====================

    /**
     * Create a new builder for DashScopeLiveModel.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DashScopeLiveModel. */
    public static class Builder {
        // Connection configuration
        private String apiKey;
        private String modelName = "qwen-omni-turbo-realtime";
        private String baseUrl = DEFAULT_BASE_URL;
        private WebSocketClient webSocketClient;

        // Audio format configuration
        private AudioFormat inputAudioFormat;
        private AudioFormat outputAudioFormat;
        private DashScopeModality modality;

        // DashScope-specific configuration
        private Boolean smoothOutput;
        private Float repetitionPenalty = 1.05f;
        private Float presencePenalty = 0.0f;
        private Long seed;
        private Integer maxTokens;
        private Integer topK;

        // VAD configuration
        private boolean vadEnabled = true;

        // ==================== Connection configuration methods ====================

        /**
         * Set API Key (required).
         *
         * @param apiKey DashScope API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Set model name.
         *
         * <p>Available models:
         *
         * <ul>
         *   <li>qwen-omni-turbo-realtime (default)
         *   <li>qwen2.5-omni-7b-realtime
         * </ul>
         *
         * @param modelName Model name
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Set WebSocket base URL.
         *
         * <p>Default: wss://dashscope.aliyuncs.com/api-ws/v1/realtime
         *
         * @param baseUrl WebSocket base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Set WebSocket client (required).
         *
         * @param client WebSocket client instance
         * @return this builder
         */
        public Builder webSocketClient(WebSocketClient client) {
            this.webSocketClient = client;
            return this;
        }

        // ==================== Audio format configuration methods ====================

        /**
         * Set input audio format.
         *
         * <p>Default: {@link AudioFormat#PCM_16K_16BIT_MONO} (currently the only supported format)
         *
         * @param format Input audio format
         * @return this builder
         */
        public Builder inputAudioFormat(AudioFormat format) {
            this.inputAudioFormat = format;
            return this;
        }

        /**
         * Set output audio format.
         *
         * <p>If not set, will be auto-detected based on model name:
         *
         * <ul>
         *   <li>Flash models: {@link AudioFormat#PCM24_OUTPUT}
         *   <li>Turbo models: {@link AudioFormat#PCM16_OUTPUT}
         * </ul>
         *
         * @param format Output audio format
         * @return this builder
         */
        public Builder outputAudioFormat(AudioFormat format) {
            this.outputAudioFormat = format;
            return this;
        }

        /**
         * Set output modality.
         *
         * <p>Default: {@link DashScopeModality#TEXT_AND_AUDIO}
         *
         * @param modality Output modality
         * @return this builder
         */
        public Builder modality(DashScopeModality modality) {
            this.modality = modality;
            return this;
        }

        /**
         * Set text-only output mode.
         *
         * <p>Convenience method equivalent to {@code modality(DashScopeModality.TEXT)}.
         *
         * @return this builder
         */
        public Builder textOnly() {
            this.modality = DashScopeModality.TEXT;
            return this;
        }

        // ==================== DashScope-specific configuration methods ====================

        /**
         * Enable smooth output (only supported by Flash series).
         *
         * @param smoothOutput true for conversational style, false for formal style
         * @return this builder
         */
        public Builder smoothOutput(Boolean smoothOutput) {
            this.smoothOutput = smoothOutput;
            return this;
        }

        /**
         * Set repetition penalty.
         *
         * @param penalty Value greater than 0, default 1.05
         * @return this builder
         */
        public Builder repetitionPenalty(Float penalty) {
            this.repetitionPenalty = penalty;
            return this;
        }

        /**
         * Set presence penalty.
         *
         * @param penalty Value from -2.0 to 2.0, default 0.0
         * @return this builder
         */
        public Builder presencePenalty(Float penalty) {
            this.presencePenalty = penalty;
            return this;
        }

        /**
         * Set random seed.
         *
         * @param seed Random seed for reproducibility
         * @return this builder
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Set maximum output tokens.
         *
         * @param maxTokens Maximum number of tokens to generate
         * @return this builder
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Set top-K sampling parameter.
         *
         * @param topK Top-K value
         * @return this builder
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        // ==================== VAD configuration methods ====================

        /**
         * Enable/disable VAD (default enabled).
         *
         * @param enabled true to enable VAD
         * @return this builder
         */
        public Builder vadEnabled(boolean enabled) {
            this.vadEnabled = enabled;
            return this;
        }

        /**
         * Disable VAD (manual control mode).
         *
         * @return this builder
         */
        public Builder vadDisabled() {
            this.vadEnabled = false;
            return this;
        }

        /**
         * Build the DashScopeLiveModel.
         *
         * @return DashScopeLiveModel instance
         * @throws NullPointerException if required parameters are missing
         */
        public DashScopeLiveModel build() {
            Objects.requireNonNull(apiKey, "apiKey is required");
            Objects.requireNonNull(modelName, "modelName is required");
            Objects.requireNonNull(webSocketClient, "webSocketClient is required");
            return new DashScopeLiveModel(this);
        }
    }

    // ==================== Exception class ====================

    /** DashScope connection exception. */
    public static class DashScopeConnectionException extends RuntimeException {

        /**
         * Create a new DashScopeConnectionException.
         *
         * @param message Error message
         */
        public DashScopeConnectionException(String message) {
            super(message);
        }

        /**
         * Create a new DashScopeConnectionException with cause.
         *
         * @param message Error message
         * @param cause Underlying cause
         */
        public DashScopeConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
