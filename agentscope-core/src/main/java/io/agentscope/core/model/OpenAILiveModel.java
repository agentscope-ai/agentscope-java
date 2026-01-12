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
import io.agentscope.core.live.formatter.LiveFormatter;
import io.agentscope.core.live.formatter.OpenAILiveFormatter;
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
 * OpenAI Realtime API (GA version) LiveModel implementation.
 *
 * <p>OpenAI characteristics:
 *
 * <ul>
 *   <li>Supports text input
 *   <li>Supports tool calling (including MCP)
 *   <li>Supports semantic VAD (semantic_vad)
 *   <li>Supports noise reduction (near_field/far_field)
 *   <li>Supports speed control (0.25-4.0)
 *   <li>Does not support session resumption
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * OpenAILiveModel model = OpenAILiveModel.builder()
 *     .apiKey("sk-xxx")
 *     .modelName("gpt-4o-realtime-preview")
 *     .semanticVad(true)
 *     .noiseReduction("near_field")
 *     .webSocketClient(JdkWebSocketClient.create())
 *     .build();
 *
 * model.connect(LiveConfig.builder()
 *         .voice("alloy")
 *         .instructions("You are a friendly assistant")
 *         .build(),
 *     toolkit)
 *     .flatMapMany(session -> session.receive())
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveModelBase
 * @see OpenAILiveFormatter
 */
public class OpenAILiveModel extends LiveModelBase {

    /** Default WebSocket URL. */
    public static final String DEFAULT_BASE_URL = "wss://api.openai.com/v1/realtime";

    // Connection configuration
    private final String apiKey;
    private final String baseUrl;

    // OpenAI-specific configuration
    private final String sessionType;
    private final String promptId;
    private final String promptVersion;
    private final Map<String, String> promptVariables;
    private final String noiseReduction;
    private final Float speed;
    private final String transcriptionModel;
    private final Boolean streamingTranscription;

    // VAD configuration
    private final boolean semanticVad;
    private final Float vadThreshold;
    private final Integer silenceDurationMs;
    private final Integer prefixPaddingMs;
    private final Integer idleTimeoutMs;
    private final boolean vadEnabled;

    private OpenAILiveModel(Builder builder) {
        super(builder.modelName, builder.webSocketClient);
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.sessionType = builder.sessionType;
        this.promptId = builder.promptId;
        this.promptVersion = builder.promptVersion;
        this.promptVariables = builder.promptVariables;
        this.noiseReduction = builder.noiseReduction;
        this.speed = builder.speed;
        this.transcriptionModel = builder.transcriptionModel;
        this.streamingTranscription = builder.streamingTranscription;
        this.semanticVad = builder.semanticVad;
        this.vadThreshold = builder.vadThreshold;
        this.silenceDurationMs = builder.silenceDurationMs;
        this.prefixPaddingMs = builder.prefixPaddingMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.vadEnabled = builder.vadEnabled;
    }

    @Override
    protected Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas) {
        WebSocketRequest request =
                WebSocketRequest.builder(buildWebSocketUrl()).headers(buildHeaders()).build();

        OpenAILiveFormatter formatter = createOpenAIFormatter();
        List<ToolSchema> schemas = toolSchemas != null ? toolSchemas : Collections.emptyList();

        return webSocketClient
                .connect(request, String.class)
                .map(connection -> createLiveSession(connection, formatter, config, schemas));
    }

    /**
     * Create OpenAI-specific formatter.
     *
     * @return OpenAILiveFormatter instance
     */
    private OpenAILiveFormatter createOpenAIFormatter() {
        return new OpenAILiveFormatter(
                sessionType,
                promptId,
                promptVersion,
                promptVariables,
                noiseReduction,
                speed,
                transcriptionModel,
                streamingTranscription,
                semanticVad,
                vadThreshold,
                silenceDurationMs,
                prefixPaddingMs,
                idleTimeoutMs,
                vadEnabled);
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
     * @param toolSchemas Tool schemas
     * @return LiveSession
     */
    private LiveSession createLiveSession(
            WebSocketConnection<String> connection,
            OpenAILiveFormatter formatter,
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
            OpenAILiveFormatter formatter,
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

            // Handle errors during handshake
            if (event.type() == LiveEventType.ERROR && !configSent.get()) {
                String errorMsg = extractErrorMessage(event);
                eventSink.tryEmitError(
                        new OpenAIConnectionException("Session setup failed: " + errorMsg));
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

    @Override
    protected String buildWebSocketUrl() {
        return baseUrl + "?model=" + modelName;
    }

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("OpenAI-Beta", "realtime=v1");
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> LiveFormatter<T> createFormatter() {
        return (LiveFormatter<T>) createOpenAIFormatter();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public boolean supportsNativeRecovery() {
        return false; // OpenAI does not support session resumption
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
     * Create a new builder for OpenAILiveModel.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for OpenAILiveModel. */
    public static class Builder {
        // Connection configuration
        private String apiKey;
        private String modelName = "gpt-realtime-2025-08-28";
        private String baseUrl = DEFAULT_BASE_URL;
        private WebSocketClient webSocketClient;

        // OpenAI-specific configuration
        private String sessionType = "realtime";
        private String promptId;
        private String promptVersion;
        private Map<String, String> promptVariables;
        private String noiseReduction;
        private Float speed;
        private String transcriptionModel;
        private Boolean streamingTranscription;

        // VAD configuration
        private boolean semanticVad = false;
        private Float vadThreshold = 0.5f;
        private Integer silenceDurationMs = 800;
        private Integer prefixPaddingMs = 300;
        private Integer idleTimeoutMs;
        private boolean vadEnabled = true;

        // ==================== Connection configuration methods ====================

        /**
         * Set API Key (required).
         *
         * @param apiKey OpenAI API key
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
         *   <li>gpt-4o-realtime-preview (default)
         *   <li>gpt-4o-mini-realtime-preview
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
         * <p>Default: wss://api.openai.com/v1/realtime
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

        // ==================== OpenAI-specific configuration methods ====================

        /**
         * Set session type.
         *
         * @param type realtime (voice conversation) or transcription (transcription only)
         * @return this builder
         */
        public Builder sessionType(String type) {
            this.sessionType = type;
            return this;
        }

        /**
         * Enable transcription session mode.
         *
         * @return this builder
         */
        public Builder transcriptionMode() {
            this.sessionType = "transcription";
            this.transcriptionModel = "whisper-1";
            return this;
        }

        /**
         * Set Prompt ID for server-stored prompts.
         *
         * @param id Prompt ID
         * @return this builder
         */
        public Builder promptId(String id) {
            this.promptId = id;
            return this;
        }

        /**
         * Set Prompt version.
         *
         * @param version Prompt version
         * @return this builder
         */
        public Builder promptVersion(String version) {
            this.promptVersion = version;
            return this;
        }

        /**
         * Set Prompt variables.
         *
         * @param vars Prompt variables map
         * @return this builder
         */
        public Builder promptVariables(Map<String, String> vars) {
            this.promptVariables = vars;
            return this;
        }

        /**
         * Set noise reduction mode.
         *
         * @param mode near_field (close range) or far_field (far range)
         * @return this builder
         */
        public Builder noiseReduction(String mode) {
            this.noiseReduction = mode;
            return this;
        }

        /**
         * Set output audio speed.
         *
         * @param speed Speed value (0.25-4.0)
         * @return this builder
         */
        public Builder speed(Float speed) {
            this.speed = speed;
            return this;
        }

        /**
         * Set transcription model.
         *
         * @param model whisper-1 or gpt-4o-transcribe
         * @return this builder
         */
        public Builder transcriptionModel(String model) {
            this.transcriptionModel = model;
            return this;
        }

        /**
         * Enable streaming transcription.
         *
         * @param enabled true to enable streaming transcription
         * @return this builder
         */
        public Builder streamingTranscription(Boolean enabled) {
            this.streamingTranscription = enabled;
            return this;
        }

        // ==================== VAD configuration methods ====================

        /**
         * Enable semantic VAD (OpenAI-specific, uses semantic understanding to detect speech end).
         *
         * @param enabled true to enable semantic VAD
         * @return this builder
         */
        public Builder semanticVad(boolean enabled) {
            this.semanticVad = enabled;
            return this;
        }

        /**
         * Set VAD detection threshold.
         *
         * @param threshold Threshold value (0.0-1.0, default 0.5)
         * @return this builder
         */
        public Builder vadThreshold(Float threshold) {
            this.vadThreshold = threshold;
            return this;
        }

        /**
         * Set silence duration in milliseconds (for detecting user stopped speaking).
         *
         * @param ms Silence duration (default 800)
         * @return this builder
         */
        public Builder silenceDurationMs(Integer ms) {
            this.silenceDurationMs = ms;
            return this;
        }

        /**
         * Set prefix padding duration in milliseconds (before speech start).
         *
         * @param ms Prefix padding (default 300)
         * @return this builder
         */
        public Builder prefixPaddingMs(Integer ms) {
            this.prefixPaddingMs = ms;
            return this;
        }

        /**
         * Set idle timeout in milliseconds (triggers timeout_triggered event).
         *
         * @param ms Idle timeout
         * @return this builder
         */
        public Builder idleTimeoutMs(Integer ms) {
            this.idleTimeoutMs = ms;
            return this;
        }

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
         * Build the OpenAILiveModel.
         *
         * @return OpenAILiveModel instance
         * @throws NullPointerException if required parameters are missing
         */
        public OpenAILiveModel build() {
            Objects.requireNonNull(apiKey, "apiKey is required");
            Objects.requireNonNull(modelName, "modelName is required");
            Objects.requireNonNull(webSocketClient, "webSocketClient is required");
            return new OpenAILiveModel(this);
        }
    }

    // ==================== Exception class ====================

    /** OpenAI connection exception. */
    public static class OpenAIConnectionException extends RuntimeException {

        /**
         * Create a new OpenAIConnectionException.
         *
         * @param message Error message
         */
        public OpenAIConnectionException(String message) {
            super(message);
        }

        /**
         * Create a new OpenAIConnectionException with cause.
         *
         * @param message Error message
         * @param cause Underlying cause
         */
        public OpenAIConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
