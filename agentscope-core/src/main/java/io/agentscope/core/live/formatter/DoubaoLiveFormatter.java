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
package io.agentscope.core.live.formatter;

import io.agentscope.core.live.LiveErrorType;
import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.TranscriptionBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doubao Realtime API Formatter (binary protocol).
 *
 * <p>Extends {@link AbstractBinaryLiveFormatter}, implements {@code LiveFormatter<byte[]>}. Works
 * directly with byte[] (binary frames), no String conversion needed.
 *
 * <p>Doubao features:
 *
 * <ul>
 *   <li>Uses binary protocol
 *   <li>Supports session resumption (dialog_id, last 20 turns)
 *   <li>Supports text input (ChatTextQuery)
 *   <li>Supports external RAG (ChatRAGText, O version)
 *   <li>Does not support tool calling
 *   <li>Built-in web search capability
 * </ul>
 */
public class DoubaoLiveFormatter extends AbstractBinaryLiveFormatter {

    // Event IDs - Client to Server
    private static final int START_CONNECTION = 100;
    private static final int FINISH_CONNECTION = 102;
    private static final int START_SESSION = 110;
    private static final int AUDIO_ONLY_REQUEST = 200;
    private static final int CHAT_TTS_TEXT = 500;
    private static final int CHAT_TEXT_QUERY = 501;
    private static final int CHAT_RAG_TEXT = 502;

    // Event IDs - Server to Client
    private static final int CONNECTION_STARTED = 101;
    private static final int CONNECTION_FINISHED = 103;
    private static final int SESSION_STARTED = 150;
    private static final int SESSION_FAILED = 153;
    private static final int USAGE_RESPONSE = 154;
    private static final int TTS_SENTENCE_START = 350;
    private static final int TTS_SENTENCE_END = 351;
    private static final int TTS_RESPONSE = 352;
    private static final int TTS_ENDED = 359;
    private static final int ASR_INFO = 450;
    private static final int ASR_RESPONSE = 451;
    private static final int ASR_ENDED = 459;
    private static final int CHAT_RESPONSE = 550;
    private static final int CHAT_TEXT_QUERY_CONFIRMED = 553;
    private static final int CHAT_ENDED = 559;
    private static final int DIALOG_COMMON_ERROR = 599;

    // Doubao-specific configuration
    private final String appId;
    private final String token;
    private final String uid;
    private final String botName;
    private final String voiceType;
    private final Integer endSmoothWindowMs;
    private final String outputAudioFormat;

    // Runtime state
    private String currentDialogId;

    private DoubaoLiveFormatter(Builder builder) {
        this.appId = builder.appId;
        this.token = builder.token;
        this.uid = builder.uid;
        this.botName = builder.botName;
        this.voiceType = builder.voiceType;
        this.endSmoothWindowMs = builder.endSmoothWindowMs;
        this.outputAudioFormat =
                builder.outputAudioFormat != null ? builder.outputAudioFormat : "ogg_opus";
    }

    /**
     * Creates a new builder for DoubaoLiveFormatter.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public byte[] formatInput(Msg msg) {
        if (msg == null) {
            return null;
        }

        if (msg.getContent() != null && !msg.getContent().isEmpty()) {
            ContentBlock block = msg.getContent().get(0);

            // Control signals
            if (block instanceof ControlBlock controlBlock) {
                return formatControl(controlBlock);
            }

            // Audio input
            if (block instanceof AudioBlock audioBlock) {
                byte[] audioData = extractAudioData(audioBlock);
                if (audioData != null) {
                    return encodeFrame(AUDIO_ONLY_REQUEST, audioData);
                }
            }

            // Text input
            if (block instanceof TextBlock textBlock) {
                return formatTextInput(textBlock.getText());
            }
        }

        return null;
    }

    /**
     * Formats control signals.
     *
     * @param controlBlock the control block to format
     * @return encoded frame bytes, or null if not supported
     */
    private byte[] formatControl(ControlBlock controlBlock) {
        // Doubao does not support most control signals
        // Interruption is handled automatically via ASRInfo event
        return switch (controlBlock.getControlType()) {
            case INTERRUPT, COMMIT, CLEAR, CREATE_RESPONSE -> null;
        };
    }

    /**
     * Formats text input.
     *
     * @param text the text to send
     * @return encoded frame bytes
     */
    private byte[] formatTextInput(String text) {
        String payload = toJson(Map.of("text", text));
        return encodeFrame(CHAT_TEXT_QUERY, stringToPayload(payload));
    }

    /**
     * Formats external RAG input.
     *
     * @param text the RAG text content
     * @return encoded frame bytes
     */
    public byte[] formatRagText(String text) {
        String payload = toJson(Map.of("text", text));
        return encodeFrame(CHAT_RAG_TEXT, stringToPayload(payload));
    }

    /**
     * Formats TTS text input (text to speech synthesis).
     *
     * @param text the text to synthesize
     * @return encoded frame bytes
     */
    public byte[] formatTtsText(String text) {
        String payload = toJson(Map.of("text", text));
        return encodeFrame(CHAT_TTS_TEXT, stringToPayload(payload));
    }

    /**
     * Extracts audio data from AudioBlock.
     *
     * @param audioBlock the audio block
     * @return raw audio bytes, or null if not a RawSource
     */
    private byte[] extractAudioData(AudioBlock audioBlock) {
        if (audioBlock.getSource() instanceof RawSource rawSource) {
            return rawSource.getDataUnsafe();
        }
        return null;
    }

    @Override
    public byte[] buildSessionConfig(LiveConfig config, List<ToolSchema> toolSchemas) {
        // Doubao requires StartConnection first, then StartSession
        // This returns the StartConnection message
        // Doubao does not support tool calling, toolSchemas is ignored
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("app_id", appId);
        payload.put("token", token);
        if (uid != null) {
            payload.put("uid", uid);
        }

        return encodeFrame(START_CONNECTION, stringToPayload(toJson(payload)));
    }

    /**
     * Builds StartSession message.
     *
     * @param config session configuration
     * @param resumptionHandle session resumption handle (dialog_id), can be null
     * @return encoded frame bytes
     */
    public byte[] buildStartSession(LiveConfig config, String resumptionHandle) {
        Map<String, Object> payload = new LinkedHashMap<>();

        if (botName != null) {
            payload.put("bot_name", botName);
        }
        if (voiceType != null) {
            payload.put("voice_type", voiceType);
        }
        if (config != null && config.getInstructions() != null) {
            payload.put("system_prompt", config.getInstructions());
        }

        // VAD configuration
        if (endSmoothWindowMs != null) {
            payload.put("end_smooth_window_ms", endSmoothWindowMs);
        }

        // Session resumption
        if (resumptionHandle != null) {
            payload.put("dialog_id", resumptionHandle);
        }

        // Audio input configuration
        payload.put(
                "audio_config",
                Map.of(
                        "format", "pcm",
                        "sample_rate", 16000,
                        "channel", 1));

        // Audio output configuration
        Map<String, Object> ttsConfig = new LinkedHashMap<>();
        ttsConfig.put("format", outputAudioFormat);
        if ("ogg_opus".equals(outputAudioFormat)) {
            ttsConfig.put("sample_rate", 24000);
        } else {
            ttsConfig.put("sample_rate", 24000);
            ttsConfig.put("bits", 32);
        }
        payload.put("tts_audio_config", ttsConfig);

        return encodeFrame(START_SESSION, stringToPayload(toJson(payload)));
    }

    /**
     * Builds FinishConnection message.
     *
     * @return encoded frame bytes
     */
    public byte[] buildFinishConnection() {
        return encodeFrame(FINISH_CONNECTION, null);
    }

    @Override
    public LiveEvent parseOutput(byte[] serverMessage) {
        DecodedFrame frame = decodeFrame(serverMessage);
        if (frame == null) {
            return LiveEvent.unknown("invalid_frame", serverMessage);
        }

        int eventId = frame.eventId();
        byte[] payload = frame.payload();

        return switch (eventId) {
            case CONNECTION_STARTED -> {
                parseJsonPayload(payload);
                yield LiveEvent.connectionState("CONNECTED", "Connection established", true);
            }

            case SESSION_STARTED -> {
                Map<String, Object> data = parseJsonPayload(payload);
                // Save dialog_id for session resumption
                if (data != null && data.containsKey("dialog_id")) {
                    currentDialogId = (String) data.get("dialog_id");
                    // Emit SESSION_RESUMPTION event
                    yield LiveEvent.sessionResumption(currentDialogId, true);
                }
                yield LiveEvent.sessionCreated(
                        Msg.builder()
                                .metadata(data != null ? Map.of("raw", data) : Map.of())
                                .build());
            }

            case TTS_RESPONSE -> {
                // Audio output (raw bytes)
                yield LiveEvent.audioDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        AudioBlock.builder()
                                                .source(
                                                        RawSource.builder()
                                                                .data(payload)
                                                                .mimeType(
                                                                        "ogg_opus"
                                                                                        .equals(
                                                                                                outputAudioFormat)
                                                                                ? "audio/ogg"
                                                                                : "audio/pcm")
                                                                .sampleRate(24000)
                                                                .bitDepth(
                                                                        "ogg_opus"
                                                                                        .equals(
                                                                                                outputAudioFormat)
                                                                                ? 16
                                                                                : 32)
                                                                .channels(1)
                                                                .build())
                                                .build())
                                .build(),
                        false);
            }

            case ASR_RESPONSE -> {
                Map<String, Object> data = parseJsonPayload(payload);
                String text = data != null ? (String) data.get("text") : "";
                Boolean isFinal = data != null ? (Boolean) data.get("is_final") : true;
                yield LiveEvent.inputTranscription(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        Boolean.TRUE.equals(isFinal)
                                                ? TranscriptionBlock.input(text)
                                                : TranscriptionBlock.inputPartial(text))
                                .build(),
                        Boolean.TRUE.equals(isFinal));
            }

            case CHAT_RESPONSE -> {
                Map<String, Object> data = parseJsonPayload(payload);
                String text = data != null ? (String) data.get("text") : "";
                yield LiveEvent.textDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(text).build())
                                .build(),
                        false);
            }

            case TTS_SENTENCE_START -> {
                Map<String, Object> data = parseJsonPayload(payload);
                String ttsType = data != null ? (String) data.get("tts_type") : "default";
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .metadata(Map.of("tts_type", ttsType != null ? ttsType : "default"))
                                .build(),
                        false);
            }

            case TTS_SENTENCE_END -> {
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .metadata(Map.of("event", "tts_sentence_end"))
                                .build(),
                        true);
            }

            case ASR_INFO -> {
                // First word detected, used for interruption
                yield LiveEvent.speechStarted();
            }

            case ASR_ENDED -> LiveEvent.speechStopped();

            case TTS_ENDED ->
                    LiveEvent.turnComplete(
                            Msg.builder().metadata(Map.of("event", "tts_ended")).build());

            case CHAT_ENDED ->
                    LiveEvent.generationComplete(
                            Msg.builder().metadata(Map.of("event", "chat_ended")).build());

            case CHAT_TEXT_QUERY_CONFIRMED -> {
                parseJsonPayload(payload);
                yield LiveEvent.connectionState("TEXT_CONFIRMED", "Text query confirmed", true);
            }

            case SESSION_FAILED -> {
                Map<String, Object> data = parseJsonPayload(payload);
                int errorCode =
                        data != null ? ((Number) data.getOrDefault("error_code", 0)).intValue() : 0;
                String errorMessage =
                        data != null
                                ? (String) data.getOrDefault("error_message", "Unknown error")
                                : "Unknown error";
                LiveErrorType errorType = mapDoubaoErrorCode(errorCode);
                yield LiveEvent.error(errorType, errorMessage);
            }

            case DIALOG_COMMON_ERROR -> {
                Map<String, Object> data = parseJsonPayload(payload);
                int errorCode =
                        data != null ? ((Number) data.getOrDefault("error_code", 0)).intValue() : 0;
                String errorMessage =
                        data != null
                                ? (String) data.getOrDefault("error_message", "Unknown error")
                                : "Unknown error";
                LiveErrorType errorType = mapDoubaoErrorCode(errorCode);
                yield LiveEvent.error(errorType, errorMessage);
            }

            case USAGE_RESPONSE -> {
                Map<String, Object> data = parseJsonPayload(payload);
                yield LiveEvent.usageMetadata(
                        Msg.builder()
                                .metadata(data != null ? Map.of("usage", data) : Map.of())
                                .build());
            }

            case CONNECTION_FINISHED -> LiveEvent.sessionEnded("CONNECTION_FINISHED", false);

            default -> LiveEvent.unknown("event_" + eventId, payload);
        };
    }

    /**
     * Parses JSON payload.
     *
     * @param payload the payload bytes
     * @return parsed map, or null if parsing fails
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonPayload(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            return fromJson(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maps Doubao error code to LiveErrorType.
     *
     * @param errorCode the Doubao error code
     * @return corresponding LiveErrorType
     */
    private LiveErrorType mapDoubaoErrorCode(int errorCode) {
        return switch (errorCode) {
            case 45000002 -> LiveErrorType.DOUBAO_EMPTY_AUDIO;
            case 45000003 -> LiveErrorType.DOUBAO_SILENCE_TIMEOUT;
            case 55000001 -> LiveErrorType.DOUBAO_SERVER_PROCESSING;
            case 55000030 -> LiveErrorType.DOUBAO_SERVICE_UNAVAILABLE;
            case 55002070 -> LiveErrorType.DOUBAO_AUDIO_FLOW_ERROR;
            default -> LiveErrorType.SERVER_ERROR;
        };
    }

    /**
     * Gets the current dialog_id (for session resumption).
     *
     * @return the current dialog_id, or null if not set
     */
    public String getCurrentDialogId() {
        return currentDialogId;
    }

    @Override
    protected byte[] encodeFrame(int eventId, byte[] payload) {
        int payloadLength = payload != null ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(8 + payloadLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header (4 bytes)
        buffer.put((byte) 0x11); // Protocol version
        buffer.put((byte) 0x10); // Header size
        buffer.put((byte) 0x01); // Message type
        buffer.put((byte) 0x00); // Reserved

        // Event ID (4 bytes)
        buffer.putInt(eventId);

        // Payload
        if (payload != null) {
            buffer.put(payload);
        }

        return buffer.array();
    }

    @Override
    protected DecodedFrame decodeFrame(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Skip header (4 bytes)
        buffer.getInt();

        // Event ID (4 bytes)
        int eventId = buffer.getInt();

        // Payload
        byte[] payload = null;
        if (buffer.hasRemaining()) {
            payload = new byte[buffer.remaining()];
            buffer.get(payload);
        }

        return new DecodedFrame(eventId, payload);
    }

    /**
     * Serialize object to JSON.
     *
     * @param obj object to serialize
     * @return JSON string
     */
    private String toJson(Object obj) {
        return JsonUtils.getJsonCodec().toJson(obj);
    }

    /**
     * Parse object from JSON.
     *
     * @param json JSON string to parse
     * @param clazz target class
     * @param <T> target type
     * @return parsed object
     */
    private <T> T fromJson(String json, Class<T> clazz) {
        return JsonUtils.getJsonCodec().fromJson(json, clazz);
    }

    /** Builder for DoubaoLiveFormatter. */
    public static class Builder {
        private String appId;
        private String token;
        private String uid;
        private String botName;
        private String voiceType;
        private Integer endSmoothWindowMs;
        private String outputAudioFormat;

        /**
         * Sets the Doubao app ID.
         *
         * @param appId the app ID
         * @return this builder
         */
        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        /**
         * Sets the authentication token.
         *
         * @param token the token
         * @return this builder
         */
        public Builder token(String token) {
            this.token = token;
            return this;
        }

        /**
         * Sets the user ID.
         *
         * @param uid the user ID
         * @return this builder
         */
        public Builder uid(String uid) {
            this.uid = uid;
            return this;
        }

        /**
         * Sets the bot name.
         *
         * @param botName the bot name
         * @return this builder
         */
        public Builder botName(String botName) {
            this.botName = botName;
            return this;
        }

        /**
         * Sets the voice type for TTS.
         *
         * @param voiceType the voice type
         * @return this builder
         */
        public Builder voiceType(String voiceType) {
            this.voiceType = voiceType;
            return this;
        }

        /**
         * Sets the end smooth window in milliseconds for VAD.
         *
         * @param endSmoothWindowMs the window duration in ms
         * @return this builder
         */
        public Builder endSmoothWindowMs(Integer endSmoothWindowMs) {
            this.endSmoothWindowMs = endSmoothWindowMs;
            return this;
        }

        /**
         * Sets the output audio format.
         *
         * @param outputAudioFormat the format ("ogg_opus" or "pcm")
         * @return this builder
         */
        public Builder outputAudioFormat(String outputAudioFormat) {
            this.outputAudioFormat = outputAudioFormat;
            return this;
        }

        /**
         * Builds the DoubaoLiveFormatter.
         *
         * @return a new DoubaoLiveFormatter instance
         */
        public DoubaoLiveFormatter build() {
            return new DoubaoLiveFormatter(this);
        }
    }
}
