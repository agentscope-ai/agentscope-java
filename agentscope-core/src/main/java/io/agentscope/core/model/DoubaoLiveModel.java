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
import io.agentscope.core.live.formatter.DoubaoLiveFormatter;
import io.agentscope.core.live.formatter.LiveFormatter;
import io.agentscope.core.live.session.LiveSession;
import io.agentscope.core.live.transport.WebSocketClient;
import io.agentscope.core.live.transport.WebSocketConnection;
import io.agentscope.core.live.transport.WebSocketRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Doubao Realtime API LiveModel implementation.
 *
 * <p>Doubao characteristics:
 *
 * <ul>
 *   <li>Uses binary protocol
 *   <li>Supports session resumption (dialog_id, last 20 turns)
 *   <li>Supports text input (ChatTextQuery)
 *   <li>Supports web search capability
 *   <li>Supports music/singing capability (O2.0 version)
 *   <li>Does not support tool calling
 * </ul>
 *
 * <p>Two-phase handshake flow:
 *
 * <ol>
 *   <li>Client sends StartConnection (event_id=100)
 *   <li>Server responds with ConnectionStarted (event_id=101)
 *   <li>Client sends StartSession (event_id=110)
 *   <li>Server responds with SessionStarted (event_id=150)
 *   <li>Session is ready for audio/text input
 * </ol>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * DoubaoLiveModel model = DoubaoLiveModel.builder()
 *     .appId("xxx")
 *     .token("xxx")
 *     .botName("Assistant")
 *     .voiceType("zh_female_shuangkuaisisi_moon_bigtts")
 *     .webSocketClient(JdkWebSocketClient.create())
 *     .build();
 *
 * model.connect(LiveConfig.builder()
 *         .instructions("You are a friendly assistant")
 *         .build())
 *     .flatMapMany(session -> session.receive())
 *     .subscribe(event -> handleEvent(event));
 * }</pre>
 *
 * @see LiveModelBase
 * @see DoubaoLiveFormatter
 */
public class DoubaoLiveModel extends LiveModelBase {

    /** Default WebSocket URL. */
    public static final String DEFAULT_BASE_URL =
            "wss://openspeech.bytedance.com/api/v3/realtime/dialogue";

    /** Default resource ID. */
    public static final String DEFAULT_RESOURCE_ID = "volc.speech.dialog";

    /** Default app key. */
    public static final String DEFAULT_APP_KEY = "PlgvMymc7f3tQnJ6";

    // Event IDs - Client to Server
    private static final int START_CONNECTION = 1;
    private static final int START_SESSION = 100;

    // Event IDs - Server to Client
    private static final int CONNECTION_STARTED = 50;
    private static final int CONNECTION_FAILED = 51;
    private static final int SESSION_STARTED = 150;
    private static final int SESSION_FAILED = 153;

    // Connection configuration
    private final String appId;
    private final String accessKey;
    private final String resourceId;
    private final String appKey;
    private final String connectId;
    private final String baseUrl;

    // Doubao-specific configuration
    private final String modelVersion;
    private final String botName;
    private final String systemRole;
    private final String speakingStyle;
    private final String characterManifest;
    private final String voiceType;
    private final Integer endSmoothWindowMs;
    private final String outputAudioFormat;
    private final String dialogId;
    private final Boolean enableWebSearch;
    private final String webSearchType;
    private final String webSearchApiKey;
    private final Boolean enableMusic;
    private final Boolean strictAudit;

    private DoubaoLiveModel(Builder builder) {
        super(builder.modelName, builder.webSocketClient);
        this.appId = builder.appId;
        this.accessKey = builder.accessKey;
        this.resourceId = builder.resourceId;
        this.appKey = builder.appKey;
        this.connectId = builder.connectId;
        this.baseUrl = builder.baseUrl;
        this.modelVersion = builder.modelVersion;
        this.botName = builder.botName;
        this.systemRole = builder.systemRole;
        this.speakingStyle = builder.speakingStyle;
        this.characterManifest = builder.characterManifest;
        this.voiceType = builder.voiceType;
        this.endSmoothWindowMs = builder.endSmoothWindowMs;
        this.outputAudioFormat = builder.outputAudioFormat;
        this.dialogId = builder.dialogId;
        this.enableWebSearch = builder.enableWebSearch;
        this.webSearchType = builder.webSearchType;
        this.webSearchApiKey = builder.webSearchApiKey;
        this.enableMusic = builder.enableMusic;
        this.strictAudit = builder.strictAudit;
    }

    @Override
    protected Mono<LiveSession> doConnect(LiveConfig config, List<ToolSchema> toolSchemas) {
        // Doubao does not support tool calling, ignore toolSchemas

        WebSocketRequest request =
                WebSocketRequest.builder(buildWebSocketUrl()).headers(buildHeaders()).build();

        DoubaoLiveFormatter formatter = createDoubaoFormatter();

        return webSocketClient
                .connect(request, byte[].class)
                .map(connection -> createLiveSession(connection, formatter, config));
    }

    /**
     * Create a LiveSession with function-based delegates.
     *
     * <p>This method sets up all the necessary functions and event processing for Doubao's
     * two-phase handshake:
     *
     * <ul>
     *   <li>Subscribes to connection.receive() once
     *   <li>Handles two-phase handshake: StartConnection → ConnectionStarted → StartSession →
     *       SessionStarted
     *   <li>Filters out handshake events from the user-facing stream
     *   <li>Creates send/updateConfig functions that use formatter internally
     * </ul>
     *
     * @param connection WebSocket connection
     * @param formatter Message formatter
     * @param config Session configuration
     * @return LiveSession
     */
    private LiveSession createLiveSession(
            WebSocketConnection<byte[]> connection,
            DoubaoLiveFormatter formatter,
            LiveConfig config) {

        String sessionId = UUID.randomUUID().toString();

        // Create unicast sink for event buffering
        Sinks.Many<LiveEvent> eventSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<HandshakeState> state =
                new AtomicReference<>(HandshakeState.WAITING_CONNECTION_STARTED);
        AtomicReference<String> sessionDialogId = new AtomicReference<>();

        // Create the session first (needed for markConnected callback)
        LiveSession session =
                new LiveSession(
                        sessionId,
                        getProviderName(),
                        // sendFunction
                        msg -> {
                            byte[] data = formatter.formatInput(msg);
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
                        // updateConfigFunction - Doubao doesn't support config update
                        newConfig -> Mono.empty());

        // Send StartConnection first
        byte[] startConnectionMsg = formatter.buildSessionConfig(config, null);
        connection.send(startConnectionMsg).subscribe();

        // Subscribe to connection.receive() once and handle messages
        connection
                .receive()
                .subscribe(
                        data ->
                                handleReceivedMessage(
                                        data,
                                        formatter,
                                        config,
                                        connection,
                                        eventSink,
                                        state,
                                        sessionDialogId,
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
     * <p>This method handles Doubao's two-phase handshake:
     *
     * <ol>
     *   <li>WAITING_CONNECTION_STARTED: Wait for ConnectionStarted, then send StartSession
     *   <li>WAITING_SESSION_STARTED: Wait for SessionStarted, then mark connected
     *   <li>READY: Normal event processing
     * </ol>
     */
    private void handleReceivedMessage(
            byte[] data,
            DoubaoLiveFormatter formatter,
            LiveConfig config,
            WebSocketConnection<byte[]> connection,
            Sinks.Many<LiveEvent> eventSink,
            AtomicReference<HandshakeState> state,
            AtomicReference<String> sessionDialogId,
            LiveSession session) {

        int eventId = parseEventId(data);

        switch (state.get()) {
            case WAITING_CONNECTION_STARTED -> {
                if (eventId == CONNECTION_STARTED) {
                    state.set(HandshakeState.WAITING_SESSION_STARTED);
                    // Send StartSession
                    byte[] startSessionMsg = formatter.buildStartSession(config, dialogId);
                    connection.send(startSessionMsg).subscribe();
                } else if (eventId == CONNECTION_FAILED) {
                    String errorMsg = parseErrorMessage(data);
                    eventSink.tryEmitError(
                            new DoubaoConnectionException("Connection failed: " + errorMsg));
                }
            }
            case WAITING_SESSION_STARTED -> {
                if (eventId == SESSION_STARTED) {
                    state.set(HandshakeState.READY);
                    String parsedDialogId = parseDialogId(data);
                    if (parsedDialogId != null) {
                        sessionDialogId.set(parsedDialogId);
                        session.setResumptionHandle(parsedDialogId);
                    }
                    session.markConnected();
                    // Emit SESSION_CREATED event for consistency
                    eventSink.tryEmitNext(LiveEvent.sessionCreated(null));
                } else if (eventId == SESSION_FAILED) {
                    String errorMsg = parseErrorMessage(data);
                    eventSink.tryEmitError(
                            new DoubaoSessionException("Session failed: " + errorMsg));
                }
            }
            case READY -> {
                // Normal event processing
                try {
                    LiveEvent event = formatter.parseOutput(data);
                    eventSink.tryEmitNext(event);
                } catch (Exception e) {
                    eventSink.tryEmitError(
                            new LiveProtocolException("Failed to parse message", e, data));
                }
            }
        }
    }

    /**
     * Parse event ID from binary frame.
     *
     * @param data Binary frame data
     * @return Event ID
     */
    private int parseEventId(byte[] data) {
        if (data == null || data.length < 8) {
            return -1;
        }
        // Event ID is at bytes 4-7 (big-endian)
        return ((data[4] & 0xFF) << 24)
                | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8)
                | (data[7] & 0xFF);
    }

    /**
     * Parse error message from binary frame.
     *
     * @param data Binary frame data
     * @return Error message
     */
    private String parseErrorMessage(byte[] data) {
        if (data == null || data.length <= 8) {
            return "Unknown error";
        }
        try {
            String json =
                    new String(data, 8, data.length - 8, java.nio.charset.StandardCharsets.UTF_8);
            // Simple JSON parsing for error_message field
            int idx = json.indexOf("\"error_message\"");
            if (idx >= 0) {
                int start = json.indexOf("\"", idx + 15) + 1;
                int end = json.indexOf("\"", start);
                if (start > 0 && end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return "Unknown error";
    }

    /**
     * Parse dialog_id from SessionStarted response.
     *
     * @param data Binary frame data
     * @return Dialog ID, or null if not found
     */
    private String parseDialogId(byte[] data) {
        if (data == null || data.length <= 8) {
            return null;
        }
        try {
            String json =
                    new String(data, 8, data.length - 8, java.nio.charset.StandardCharsets.UTF_8);
            // Simple JSON parsing for dialog_id field
            int idx = json.indexOf("\"dialog_id\"");
            if (idx >= 0) {
                int start = json.indexOf("\"", idx + 11) + 1;
                int end = json.indexOf("\"", start);
                if (start > 0 && end > start) {
                    return json.substring(start, end);
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return null;
    }

    /**
     * Create Doubao-specific formatter.
     *
     * @return DoubaoLiveFormatter instance
     */
    private DoubaoLiveFormatter createDoubaoFormatter() {
        return DoubaoLiveFormatter.builder()
                .appId(appId)
                .token(accessKey)
                .botName(botName)
                .voiceType(voiceType)
                .endSmoothWindowMs(endSmoothWindowMs)
                .outputAudioFormat(outputAudioFormat)
                .build();
    }

    @Override
    protected String buildWebSocketUrl() {
        return baseUrl;
    }

    @Override
    protected Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Api-App-ID", appId);
        headers.put("X-Api-Access-Key", accessKey);
        headers.put("X-Api-Resource-Id", resourceId);
        headers.put("X-Api-App-Key", appKey);
        if (connectId != null) {
            headers.put("X-Api-Connect-Id", connectId);
        }
        return headers;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <T> LiveFormatter<T> createFormatter() {
        return (LiveFormatter<T>) createDoubaoFormatter();
    }

    @Override
    public String getProviderName() {
        return "doubao";
    }

    @Override
    public boolean supportsNativeRecovery() {
        return true; // Doubao supports dialog_id for session resumption
    }

    // ==================== Internal Types ====================

    /** Handshake state machine. */
    private enum HandshakeState {
        WAITING_CONNECTION_STARTED,
        WAITING_SESSION_STARTED,
        READY
    }

    // ==================== Builder ====================

    /**
     * Create a new builder for DoubaoLiveModel.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DoubaoLiveModel. */
    public static class Builder {
        // Connection configuration
        private String appId;
        private String accessKey;
        private String resourceId = DEFAULT_RESOURCE_ID;
        private String appKey = DEFAULT_APP_KEY;
        private String connectId;
        private String modelName = "doubao-realtime";
        private String baseUrl = DEFAULT_BASE_URL;
        private WebSocketClient webSocketClient;

        // Doubao-specific configuration
        private String modelVersion = "O";
        private String botName;
        private String systemRole;
        private String speakingStyle;
        private String characterManifest;
        private String voiceType;
        private Integer endSmoothWindowMs = 1500;
        private String outputAudioFormat = "ogg_opus";
        private String dialogId;
        private Boolean enableWebSearch;
        private String webSearchType;
        private String webSearchApiKey;
        private Boolean enableMusic;
        private Boolean strictAudit = true;

        // ==================== Connection configuration methods ====================

        /**
         * Set App ID (required).
         *
         * <p>Obtain from Volcengine console.
         *
         * @param appId Doubao App ID
         * @return this builder
         */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /**
         * Set Access Key (required).
         *
         * <p>Obtain from Volcengine console.
         *
         * @param accessKey Access Key for authentication
         * @return this builder
         */
        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        /**
         * Set Resource ID.
         *
         * <p>Default: volc.speech.dialog
         *
         * @param resourceId Resource ID
         * @return this builder
         */
        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        /**
         * Set App Key.
         *
         * <p>Default: PlgvMymc7f3tQnJ6
         *
         * @param appKey App Key
         * @return this builder
         */
        public Builder appKey(String appKey) {
            this.appKey = appKey;
            return this;
        }

        /**
         * Set Connect ID for tracking.
         *
         * <p>Optional, used for debugging and tracking connections.
         *
         * @param connectId Connect ID (UUID recommended)
         * @return this builder
         */
        public Builder connectId(String connectId) {
            this.connectId = connectId;
            return this;
        }

        /**
         * Set model name.
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
         * <p>Default: wss://openspeech.bytedance.com/api/v3/realtime/dialogue
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

        // ==================== Doubao-specific configuration methods ====================

        /**
         * Set model version.
         *
         * <p>Available versions:
         *
         * <ul>
         *   <li>O - Original version with premium voices (default)
         *   <li>SC - Voice cloning version
         *   <li>1.2.1.0 - O2.0 version with singing capability
         *   <li>2.2.0.0 - SC2.0 version with improved cloning
         * </ul>
         *
         * @param version Model version
         * @return this builder
         */
        public Builder modelVersion(String version) {
            this.modelVersion = version;
            return this;
        }

        /**
         * Set bot name (max 20 characters).
         *
         * <p>Only effective for O and O2.0 versions.
         *
         * @param name Bot name
         * @return this builder
         */
        public Builder botName(String name) {
            this.botName = name;
            return this;
        }

        /**
         * Set system role description.
         *
         * <p>Only effective for O and O2.0 versions.
         *
         * @param role System role description
         * @return this builder
         */
        public Builder systemRole(String role) {
            this.systemRole = role;
            return this;
        }

        /**
         * Set speaking style.
         *
         * <p>Only effective for O and O2.0 versions.
         *
         * @param style Speaking style description
         * @return this builder
         */
        public Builder speakingStyle(String style) {
            this.speakingStyle = style;
            return this;
        }

        /**
         * Set character manifest.
         *
         * <p>Only effective for SC and SC2.0 versions.
         *
         * @param manifest Character manifest description
         * @return this builder
         */
        public Builder characterManifest(String manifest) {
            this.characterManifest = manifest;
            return this;
        }

        /**
         * Set voice type for TTS.
         *
         * <p>Available voices for O/O2.0 versions:
         *
         * <ul>
         *   <li>zh_female_vv_jupiter_bigtts (vv, default)
         *   <li>zh_female_xiaohe_jupiter_bigtts (xiaohe)
         *   <li>zh_male_yunzhou_jupiter_bigtts (yunzhou)
         *   <li>zh_male_xiaotian_jupiter_bigtts (xiaotian)
         * </ul>
         *
         * <p>For SC versions, use ICL_ prefixed voices. For SC2.0 versions, use saturn_ prefixed
         * voices.
         *
         * @param voiceType Voice type identifier
         * @return this builder
         */
        public Builder voiceType(String voiceType) {
            this.voiceType = voiceType;
            return this;
        }

        /**
         * Set VAD end smooth window in milliseconds.
         *
         * <p>Range: 500-50000ms, default 1500ms
         *
         * @param ms Window duration in milliseconds
         * @return this builder
         */
        public Builder endSmoothWindowMs(Integer ms) {
            this.endSmoothWindowMs = ms;
            return this;
        }

        /**
         * Set output audio format.
         *
         * <p>Options:
         *
         * <ul>
         *   <li>ogg_opus (default) - Opus codec in OGG container
         *   <li>pcm - Raw PCM audio (24kHz, 32-bit)
         *   <li>pcm_s16le - Raw PCM audio (24kHz, 16-bit)
         * </ul>
         *
         * @param format Audio format
         * @return this builder
         */
        public Builder outputAudioFormat(String format) {
            this.outputAudioFormat = format;
            return this;
        }

        /**
         * Set dialog ID for session resumption.
         *
         * <p>Use this to continue a previous conversation (last 20 turns).
         *
         * @param dialogId Previous dialog ID
         * @return this builder
         */
        public Builder dialogId(String dialogId) {
            this.dialogId = dialogId;
            return this;
        }

        /**
         * Enable built-in web search capability.
         *
         * @param enabled true to enable web search
         * @return this builder
         */
        public Builder enableWebSearch(Boolean enabled) {
            this.enableWebSearch = enabled;
            return this;
        }

        /**
         * Set web search type.
         *
         * <p>Options:
         *
         * <ul>
         *   <li>web_summary - Summary version (default)
         *   <li>web - Standard version
         *   <li>web_agent - Search agent for improved quality
         * </ul>
         *
         * @param type Web search type
         * @return this builder
         */
        public Builder webSearchType(String type) {
            this.webSearchType = type;
            return this;
        }

        /**
         * Set web search API key.
         *
         * @param apiKey API key for web search service
         * @return this builder
         */
        public Builder webSearchApiKey(String apiKey) {
            this.webSearchApiKey = apiKey;
            return this;
        }

        /**
         * Enable music/singing capability.
         *
         * <p>Only effective for O2.0 version.
         *
         * @param enabled true to enable music capability
         * @return this builder
         */
        public Builder enableMusic(Boolean enabled) {
            this.enableMusic = enabled;
            return this;
        }

        /**
         * Set strict audit mode.
         *
         * <p>Default: true (strict audit)
         *
         * @param strict true for strict audit, false for normal audit
         * @return this builder
         */
        public Builder strictAudit(Boolean strict) {
            this.strictAudit = strict;
            return this;
        }

        /**
         * Build the DoubaoLiveModel.
         *
         * @return DoubaoLiveModel instance
         * @throws NullPointerException if required parameters are missing
         */
        public DoubaoLiveModel build() {
            Objects.requireNonNull(appId, "appId is required");
            Objects.requireNonNull(accessKey, "accessKey is required");
            Objects.requireNonNull(webSocketClient, "webSocketClient is required");
            return new DoubaoLiveModel(this);
        }
    }

    // ==================== Exception classes ====================

    /** Doubao connection exception. */
    public static class DoubaoConnectionException extends RuntimeException {

        /**
         * Create a new DoubaoConnectionException.
         *
         * @param message Error message
         */
        public DoubaoConnectionException(String message) {
            super(message);
        }

        /**
         * Create a new DoubaoConnectionException with cause.
         *
         * @param message Error message
         * @param cause Underlying cause
         */
        public DoubaoConnectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Doubao session exception. */
    public static class DoubaoSessionException extends RuntimeException {

        /**
         * Create a new DoubaoSessionException.
         *
         * @param message Error message
         */
        public DoubaoSessionException(String message) {
            super(message);
        }

        /**
         * Create a new DoubaoSessionException with cause.
         *
         * @param message Error message
         * @param cause Underlying cause
         */
        public DoubaoSessionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
