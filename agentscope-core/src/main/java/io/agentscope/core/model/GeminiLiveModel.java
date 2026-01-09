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
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.live.formatter.GeminiLiveFormatter;
import io.agentscope.core.live.formatter.GeminiLiveFormatter.SpeechSensitivity;
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
 * Gemini Live API LiveModel implementation.
 *
 * <p>Gemini characteristics:
 *
 * <ul>
 *   <li>Supports text, image, and video input
 *   <li>Supports tool calling
 *   <li>Supports session resumption (sessionResumption, 2-hour validity)
 *   <li>Supports context window compression (contextWindowCompression)
 *   <li>Native Audio features: proactiveAudio, affectiveDialog, thinking
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * GeminiLiveModel model = GeminiLiveModel.builder()
 *     .apiKey("xxx")
 *     .modelName("gemini-2.0-flash-exp")
 *     .sessionResumption(true)
 *     .proactiveAudio(true)
 *     .webSocketClient(JdkWebSocketClient.create())
 *     .build();
 *
 * model.connect(LiveConfig.builder()
 *         .voice("Puck")
 *         .instructions("You are a friendly assistant")
 *         .build(),
 *     toolkit)
 *     .flatMapMany(session -> session.receive())
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveModelBase
 * @see GeminiLiveFormatter
 */
public class GeminiLiveModel extends LiveModelBase {

    /** Default WebSocket URL. */
    public static final String DEFAULT_BASE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta"
                    + ".GenerativeService.BidiGenerateContent";

    // Connection configuration
    private final String apiKey;
    private final String baseUrl;

    // Gemini-specific configuration
    private final Boolean proactiveAudio;
    private final Boolean affectiveDialog;
    private final Boolean enableThinking;
    private final Integer thinkingBudget;
    private final Boolean contextWindowCompression;
    private final Integer slidingWindowTokens;
    private final Boolean sessionResumption;
    private final String sessionResumptionHandle;
    private final String activityHandling;
    private final String mediaResolution;

    // VAD configuration
    private final SpeechSensitivity startOfSpeechSensitivity;
    private final SpeechSensitivity endOfSpeechSensitivity;
    private final Integer silenceDurationMs;
    private final Integer prefixPaddingMs;

    private GeminiLiveModel(Builder builder) {
        super(builder.modelName, builder.webSocketClient);
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.proactiveAudio = builder.proactiveAudio;
        this.affectiveDialog = builder.affectiveDialog;
        this.enableThinking = builder.enableThinking;
        this.thinkingBudget = builder.thinkingBudget;
        this.contextWindowCompression = builder.contextWindowCompression;
        this.slidingWindowTokens = builder.slidingWindowTokens;
        this.sessionResumption = builder.sessionResumption;
        this.sessionResumptionHandle = builder.sessionResumptionHandle;
        this.activityHandling = builder.activityHandling;
        this.mediaResolution = builder.mediaResolution;
        this.startOfSpeechSensitivity = builder.startOfSpeechSensitivity;
        this.endOfSpeechSensitivity = builder.endOfSpeechSensitivity;
        this.silenceDurationMs = builder.silenceDurationMs;
        this.prefixPaddingMs = builder.prefixPaddingMs;
    }

    @Override
    protected Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas) {
        WebSocketRequest request =
                WebSocketRequest.builder(buildWebSocketUrl()).headers(buildHeaders()).build();

        GeminiLiveFormatter formatter = createGeminiFormatter();
        List<ToolSchema> schemas = toolSchemas != null ? toolSchemas : Collections.emptyList();

        return webSocketClient
                .connect(request, String.class)
                .flatMap(
                        connection -> {
                            // Gemini requires sending setup message immediately after connection
                            String setupMsg = formatter.buildSessionConfig(config, schemas);
                            return connection
                                    .send(setupMsg)
                                    .thenReturn(
                                            createLiveSession(
                                                    connection, formatter, config, schemas));
                        });
    }

    /**
     * Create a LiveSession with function-based delegates.
     *
     * <p>This method sets up all the necessary functions and event processing:
     *
     * <ul>
     *   <li>Subscribes to connection.receive() once
     *   <li>Handles handshake internally (waits for SESSION_CREATED/setupComplete)
     *   <li>Filters out handshake events from the user-facing stream
     *   <li>Creates send/updateConfig functions that use formatter internally
     * </ul>
     *
     * @param connection WebSocket connection
     * @param formatter Message formatter
     * @param config Session configuration
     * @param toolSchemas Tool schemas
     * @return LiveSession
     */
    private LiveSession createLiveSession(
            WebSocketConnection<String> connection,
            GeminiLiveFormatter formatter,
            LiveConfig config,
            List<ToolSchema> toolSchemas) {

        String sessionId = UUID.randomUUID().toString();

        // Create unicast sink for event buffering
        Sinks.Many<LiveEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean setupComplete = new AtomicBoolean(false);

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
                                .filter(event -> event.type() != LiveEventType.SESSION_CREATED),
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
                                        data, formatter, eventSink, setupComplete, session),
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
     *   <li>Handles handshake: marks connected after SESSION_CREATED (setupComplete)
     *   <li>Emits events to the sink for user consumption
     * </ul>
     */
    private void handleReceivedMessage(
            String data,
            GeminiLiveFormatter formatter,
            Sinks.Many<LiveEvent> eventSink,
            AtomicBoolean setupComplete,
            LiveSession session) {
        try {
            LiveEvent event = formatter.parseOutput(data);

            // Mark connected after SESSION_CREATED (setupComplete in Gemini)
            if (event.type() == LiveEventType.SESSION_CREATED
                    && setupComplete.compareAndSet(false, true)) {
                session.markConnected();
            }

            // Handle errors during handshake
            if (event.type() == LiveEventType.ERROR && !setupComplete.get()) {
                String errorMsg = extractErrorMessage(event);
                eventSink.tryEmitError(new GeminiConnectionException("Setup failed: " + errorMsg));
                return;
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
     * Create Gemini-specific formatter.
     *
     * @return GeminiLiveFormatter instance
     */
    private GeminiLiveFormatter createGeminiFormatter() {
        return new GeminiLiveFormatter(
                proactiveAudio,
                affectiveDialog,
                enableThinking,
                thinkingBudget,
                contextWindowCompression,
                slidingWindowTokens,
                sessionResumption,
                sessionResumptionHandle,
                activityHandling,
                mediaResolution,
                startOfSpeechSensitivity,
                endOfSpeechSensitivity,
                silenceDurationMs,
                prefixPaddingMs);
    }

    @Override
    protected String buildWebSocketUrl() {
        return baseUrl + "?key=" + apiKey;
    }

    @Override
    protected Map<String, String> buildHeaders() {
        // Gemini uses URL parameter for API key, no special headers needed
        return new HashMap<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> LiveFormatter<T> createFormatter() {
        return (LiveFormatter<T>) createGeminiFormatter();
    }

    @Override
    public String getProviderName() {
        return "gemini";
    }

    @Override
    public boolean supportsNativeRecovery() {
        return true; // Gemini supports sessionResumption
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

    /**
     * Extract error message from ERROR event.
     *
     * @param event The error event
     * @return Error message
     */
    private String extractErrorMessage(LiveEvent event) {
        if (event.message() != null && event.message().getMetadata() != null) {
            Object value = event.message().getMetadata().get("live.error.message");
            return value != null ? value.toString() : "Unknown error";
        }
        return "Unknown error";
    }

    // ==================== Builder ====================

    /**
     * Create a new builder for GeminiLiveModel.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for GeminiLiveModel. */
    public static class Builder {
        // Connection configuration
        private String apiKey;
        private String modelName = "gemini-2.0-flash-exp";
        private String baseUrl = DEFAULT_BASE_URL;
        private WebSocketClient webSocketClient;

        // Gemini-specific configuration
        private Boolean proactiveAudio;
        private Boolean affectiveDialog;
        private Boolean enableThinking;
        private Integer thinkingBudget;
        private Boolean contextWindowCompression = true;
        private Integer slidingWindowTokens;
        private Boolean sessionResumption = true;
        private String sessionResumptionHandle;
        private String activityHandling;
        private String mediaResolution;

        // VAD configuration
        private SpeechSensitivity startOfSpeechSensitivity;
        private SpeechSensitivity endOfSpeechSensitivity;
        private Integer silenceDurationMs;
        private Integer prefixPaddingMs;

        // ==================== Connection configuration methods ====================

        /**
         * Set API Key (required).
         *
         * @param apiKey Gemini API key
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
         *   <li>gemini-2.0-flash-exp (default)
         *   <li>gemini-2.0-flash-live-001
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

        // ==================== Gemini-specific configuration methods ====================

        /**
         * Enable proactive audio - model can proactively decide whether to respond (Native Audio
         * feature).
         *
         * @param enabled true to enable proactive audio
         * @return this builder
         */
        public Builder proactiveAudio(Boolean enabled) {
            this.proactiveAudio = enabled;
            return this;
        }

        /**
         * Enable affective dialog - adjust response style based on tone (Native Audio feature).
         *
         * @param enabled true to enable affective dialog
         * @return this builder
         */
        public Builder affectiveDialog(Boolean enabled) {
            this.affectiveDialog = enabled;
            return this;
        }

        /**
         * Enable thinking capability (Native Audio feature).
         *
         * @param enabled true to enable thinking
         * @return this builder
         */
        public Builder enableThinking(Boolean enabled) {
            this.enableThinking = enabled;
            return this;
        }

        /**
         * Set thinking budget - number of thinking tokens.
         *
         * @param budget Thinking budget in tokens
         * @return this builder
         */
        public Builder thinkingBudget(Integer budget) {
            this.thinkingBudget = budget;
            return this;
        }

        /**
         * Enable context window compression - supports unlimited session duration (default true).
         *
         * @param enabled true to enable context window compression
         * @return this builder
         */
        public Builder contextWindowCompression(Boolean enabled) {
            this.contextWindowCompression = enabled;
            return this;
        }

        /**
         * Set sliding window token count.
         *
         * @param tokens Target tokens for sliding window
         * @return this builder
         */
        public Builder slidingWindowTokens(Integer tokens) {
            this.slidingWindowTokens = tokens;
            return this;
        }

        /**
         * Enable session resumption - maintain state across connections, valid for 2 hours (default
         * true).
         *
         * @param enabled true to enable session resumption
         * @return this builder
         */
        public Builder sessionResumption(Boolean enabled) {
            this.sessionResumption = enabled;
            return this;
        }

        /**
         * Set session resumption handle - used to resume a previous session.
         *
         * @param handle Previous session handle
         * @return this builder
         */
        public Builder sessionResumptionHandle(String handle) {
            this.sessionResumptionHandle = handle;
            return this;
        }

        /**
         * Set activity handling mode.
         *
         * @param handling START_OF_ACTIVITY_INTERRUPTS or NO_INTERRUPTION
         * @return this builder
         */
        public Builder activityHandling(String handling) {
            this.activityHandling = handling;
            return this;
        }

        /**
         * Set media resolution.
         *
         * @param resolution MEDIA_RESOLUTION_LOW, MEDIUM, or HIGH
         * @return this builder
         */
        public Builder mediaResolution(String resolution) {
            this.mediaResolution = resolution;
            return this;
        }

        // ==================== VAD configuration methods ====================

        /**
         * Set start of speech detection sensitivity.
         *
         * @param sensitivity HIGH for faster detection, LOW for fewer false triggers
         * @return this builder
         */
        public Builder startOfSpeechSensitivity(SpeechSensitivity sensitivity) {
            this.startOfSpeechSensitivity = sensitivity;
            return this;
        }

        /**
         * Set end of speech detection sensitivity.
         *
         * @param sensitivity HIGH for faster end detection, LOW for allowing longer pauses
         * @return this builder
         */
        public Builder endOfSpeechSensitivity(SpeechSensitivity sensitivity) {
            this.endOfSpeechSensitivity = sensitivity;
            return this;
        }

        /**
         * Set silence duration in milliseconds (for detecting user stopped speaking).
         *
         * @param ms Silence duration
         * @return this builder
         */
        public Builder silenceDurationMs(Integer ms) {
            this.silenceDurationMs = ms;
            return this;
        }

        /**
         * Set prefix padding duration in milliseconds (before speech start).
         *
         * @param ms Prefix padding
         * @return this builder
         */
        public Builder prefixPaddingMs(Integer ms) {
            this.prefixPaddingMs = ms;
            return this;
        }

        /**
         * Build the GeminiLiveModel.
         *
         * @return GeminiLiveModel instance
         * @throws NullPointerException if required parameters are missing
         */
        public GeminiLiveModel build() {
            Objects.requireNonNull(apiKey, "apiKey is required");
            Objects.requireNonNull(modelName, "modelName is required");
            Objects.requireNonNull(webSocketClient, "webSocketClient is required");
            return new GeminiLiveModel(this);
        }
    }

    // ==================== Exception class ====================

    /** Gemini connection exception. */
    public static class GeminiConnectionException extends RuntimeException {

        /**
         * Create a new GeminiConnectionException.
         *
         * @param message Error message
         */
        public GeminiConnectionException(String message) {
            super(message);
        }

        /**
         * Create a new GeminiConnectionException with cause.
         *
         * @param message Error message
         * @param cause Underlying cause
         */
        public GeminiConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
