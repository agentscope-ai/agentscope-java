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

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TranscriptionBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gemini Live API Formatter.
 *
 * <p>Extends {@link AbstractTextLiveFormatter}, implements {@code LiveFormatter<String>}. Works
 * directly with String (JSON), no byte[] conversion needed.
 *
 * <p>Gemini characteristics:
 *
 * <ul>
 *   <li>Supports session resumption (sessionResumption, 2-hour validity)
 *   <li>Supports context window compression (contextWindowCompression)
 *   <li>Supports Native Audio features (proactiveAudio, affectiveDialog, thinking)
 *   <li>Supports text and image/video input
 *   <li>Supports tool calling
 *   <li>Supports goAway event (connection closing notification)
 * </ul>
 */
public class GeminiLiveFormatter extends AbstractTextLiveFormatter {

    private static final Logger log = LoggerFactory.getLogger(GeminiLiveFormatter.class);
    // Model configuration
    private final String modelName;

    // Gemini-specific configuration
    private final Boolean proactiveAudio;
    private final Boolean affectiveDialog;
    private final Boolean enableThinking;
    private final Integer thinkingBudget;
    private final Boolean contextWindowCompression;
    private final Integer triggerTokens;
    private final Integer slidingWindowTokens;
    private final Boolean sessionResumption;
    private final String sessionResumptionHandle;
    private final String activityHandling;
    private final String mediaResolution;

    // Response configuration
    private final List<String> responseModalities;

    // Transcription configuration
    private final Boolean inputAudioTranscription;
    private final Boolean outputAudioTranscription;

    // VAD configuration (Gemini uses speechSensitivity)
    private final SpeechSensitivity startOfSpeechSensitivity;
    private final SpeechSensitivity endOfSpeechSensitivity;
    private final Integer silenceDurationMs;
    private final Integer prefixPaddingMs;

    /** Gemini speech sensitivity enum. */
    public enum SpeechSensitivity {
        /** High sensitivity - more responsive to speech. */
        HIGH,
        /** Low sensitivity - less responsive to speech. */
        LOW
    }

    /**
     * Creates a new GeminiLiveFormatter.
     *
     * @param modelName the model name
     * @param proactiveAudio whether to enable proactive audio
     * @param affectiveDialog whether to enable affective dialog
     * @param enableThinking whether to enable thinking capability
     * @param thinkingBudget thinking budget in tokens
     * @param contextWindowCompression whether to enable context window compression
     * @param triggerTokens trigger tokens for context window compression
     * @param slidingWindowTokens target tokens for sliding window
     * @param sessionResumption whether to enable session resumption
     * @param sessionResumptionHandle previous session handle for resumption
     * @param activityHandling activity handling mode
     * @param mediaResolution media resolution setting
     * @param responseModalities response modalities (TEXT, AUDIO, IMAGE)
     * @param inputAudioTranscription whether to enable input audio transcription
     * @param outputAudioTranscription whether to enable output audio transcription
     * @param startOfSpeechSensitivity start of speech sensitivity
     * @param endOfSpeechSensitivity end of speech sensitivity
     * @param silenceDurationMs silence duration in milliseconds
     * @param prefixPaddingMs prefix padding in milliseconds
     */
    public GeminiLiveFormatter(
            String modelName,
            Boolean proactiveAudio,
            Boolean affectiveDialog,
            Boolean enableThinking,
            Integer thinkingBudget,
            Boolean contextWindowCompression,
            Integer triggerTokens,
            Integer slidingWindowTokens,
            Boolean sessionResumption,
            String sessionResumptionHandle,
            String activityHandling,
            String mediaResolution,
            List<String> responseModalities,
            Boolean inputAudioTranscription,
            Boolean outputAudioTranscription,
            SpeechSensitivity startOfSpeechSensitivity,
            SpeechSensitivity endOfSpeechSensitivity,
            Integer silenceDurationMs,
            Integer prefixPaddingMs) {
        this.modelName = modelName;
        this.proactiveAudio = proactiveAudio;
        this.affectiveDialog = affectiveDialog;
        this.enableThinking = enableThinking;
        this.thinkingBudget = thinkingBudget;
        this.contextWindowCompression = contextWindowCompression;
        this.triggerTokens = triggerTokens;
        this.slidingWindowTokens = slidingWindowTokens;
        this.sessionResumption = sessionResumption;
        this.sessionResumptionHandle = sessionResumptionHandle;
        this.activityHandling = activityHandling;
        this.mediaResolution = mediaResolution;
        this.responseModalities = responseModalities;
        this.inputAudioTranscription = inputAudioTranscription;
        this.outputAudioTranscription = outputAudioTranscription;
        this.startOfSpeechSensitivity = startOfSpeechSensitivity;
        this.endOfSpeechSensitivity = endOfSpeechSensitivity;
        this.silenceDurationMs = silenceDurationMs;
        this.prefixPaddingMs = prefixPaddingMs;
    }

    @Override
    protected String formatControl(ControlBlock controlBlock) {
        // Gemini uses clientContent to send interrupt signal
        return switch (controlBlock.getControlType()) {
            case INTERRUPT -> toJson(Map.of("clientContent", Map.of("turnComplete", true)));
            // Gemini does not support COMMIT/CLEAR/CREATE_RESPONSE
            default -> null;
        };
    }

    @Override
    protected String formatAudio(byte[] audioData) {
        // Gemini uses realtimeInput with audio field (mediaChunks is deprecated)
        return toJson(
                Map.of(
                        "realtimeInput",
                        Map.of(
                                "audio",
                                Map.of(
                                        "mimeType",
                                        "audio/pcm;rate=16000",
                                        "data",
                                        encodeBase64(audioData)))));
    }

    @Override
    protected String formatOtherContent(Msg msg, ContentBlock block) {
        // Gemini supports text input
        if (block instanceof TextBlock textBlock) {
            return formatTextInput(textBlock.getText());
        }

        // Gemini supports image/video input via realtimeInput.video
        if (block instanceof ImageBlock imageBlock) {
            return formatImageInput(imageBlock);
        }

        // Tool result
        if (block instanceof ToolResultBlock toolResult) {
            return formatToolResult(toolResult);
        }

        return null;
    }

    /**
     * Formats text input as clientContent.
     *
     * @param text the text to send
     * @return JSON string for text input
     */
    private String formatTextInput(String text) {
        return toJson(
                Map.of(
                        "clientContent",
                        Map.of(
                                "turns",
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "parts",
                                                List.of(Map.of("text", text)))),
                                "turnComplete",
                                true)));
    }

    /**
     * Formats image input as realtimeInput.video.
     *
     * <p>Gemini Live API uses the "video" field for images and video frames. The data should be
     * Base64 encoded.
     *
     * @param imageBlock the image block to format
     * @return JSON string for image input, or null if extraction fails
     */
    private String formatImageInput(ImageBlock imageBlock) {
        Object source = imageBlock.getSource();
        if (source instanceof Base64Source base64Source) {
            String data = base64Source.getData();
            String mimeType = base64Source.getMediaType();
            if (mimeType == null) {
                mimeType = "image/jpeg";
            }
            return toJson(
                    Map.of(
                            "realtimeInput",
                            Map.of("video", Map.of("mimeType", mimeType, "data", data))));
        }
        return null;
    }

    /**
     * Formats tool call result.
     *
     * @param toolResult the tool result block
     * @return JSON string for tool result
     */
    @SuppressWarnings("unchecked")
    private String formatToolResult(ToolResultBlock toolResult) {
        Object response;
        String content = extractToolResultOutput(toolResult);
        if (content != null && !content.isEmpty()) {
            try {
                response = fromJson(content, Map.class);
            } catch (Exception e) {
                response = Map.of("result", content);
            }
        } else {
            response = Map.of("result", "");
        }

        Map<String, Object> functionResponse = new LinkedHashMap<>();
        functionResponse.put("id", toolResult.getId());
        functionResponse.put("name", toolResult.getName() != null ? toolResult.getName() : "");
        functionResponse.put("response", response);

        return toJson(
                Map.of("toolResponse", Map.of("functionResponses", List.of(functionResponse))));
    }

    /**
     * Extracts tool result output as a string.
     *
     * @param toolResult the tool result block
     * @return string representation of the output
     */
    private String extractToolResultOutput(ToolResultBlock toolResult) {
        List<ContentBlock> outputs = toolResult.getOutput();
        if (outputs == null || outputs.isEmpty()) {
            return "";
        }
        // If single text block, return its text
        if (outputs.size() == 1 && outputs.get(0) instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        // Otherwise, serialize to JSON
        return toJson(outputs);
    }

    @Override
    protected String buildSessionConfigJson(LiveConfig config, List<ToolSchema> toolSchemas) {
        Map<String, Object> setup = new LinkedHashMap<>();

        // Model configuration
        if (modelName != null) {
            setup.put("model", "models/" + modelName);
        }

        // Generation configuration
        Map<String, Object> generationConfig = new LinkedHashMap<>();

        // Response modalities (default TEXT + AUDIO)
        if (responseModalities != null && !responseModalities.isEmpty()) {
            generationConfig.put("responseModalities", responseModalities);
        } else {
            generationConfig.put("responseModalities", List.of("AUDIO"));
        }

        // Voice configuration
        if (config.getVoice() != null) {
            generationConfig.put(
                    "speechConfig",
                    Map.of(
                            "voiceConfig",
                            Map.of("prebuiltVoiceConfig", Map.of("voiceName", config.getVoice()))));
        }

        // Generation parameters
        if (config.getGenerationConfig() != null) {
            if (config.getGenerationConfig().getTemperature() != null) {
                generationConfig.put("temperature", config.getGenerationConfig().getTemperature());
            }
            if (config.getGenerationConfig().getTopP() != null) {
                generationConfig.put("topP", config.getGenerationConfig().getTopP());
            }
            if (config.getGenerationConfig().getTopK() != null) {
                generationConfig.put("topK", config.getGenerationConfig().getTopK());
            }
            if (config.getGenerationConfig().getMaxTokens() != null) {
                generationConfig.put(
                        "maxOutputTokens", config.getGenerationConfig().getMaxTokens());
            }
        }

        setup.put("generationConfig", generationConfig);

        // System instruction
        if (config.getInstructions() != null) {
            setup.put(
                    "systemInstruction",
                    Map.of("parts", List.of(Map.of("text", config.getInstructions()))));
        }

        // Tool configuration
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            List<Map<String, Object>> functionDeclarations = new ArrayList<>();
            for (ToolSchema tool : toolSchemas) {
                Map<String, Object> funcDecl = new LinkedHashMap<>();
                funcDecl.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    funcDecl.put("description", tool.getDescription());
                }
                if (tool.getParameters() != null) {
                    funcDecl.put("parameters", tool.getParameters());
                }
                functionDeclarations.add(funcDecl);
            }
            setup.put("tools", List.of(Map.of("functionDeclarations", functionDeclarations)));
        }

        // Realtime input configuration (VAD)
        Map<String, Object> realtimeInputConfig = new LinkedHashMap<>();
        Map<String, Object> activityDetection = new LinkedHashMap<>();

        if (startOfSpeechSensitivity != null) {
            activityDetection.put("startOfSpeechSensitivity", startOfSpeechSensitivity.name());
        }
        if (endOfSpeechSensitivity != null) {
            activityDetection.put("endOfSpeechSensitivity", endOfSpeechSensitivity.name());
        }
        if (silenceDurationMs != null) {
            activityDetection.put("silenceDurationMs", silenceDurationMs);
        }
        if (prefixPaddingMs != null) {
            activityDetection.put("prefixPaddingMs", prefixPaddingMs);
        }

        if (!activityDetection.isEmpty()) {
            realtimeInputConfig.put("automaticActivityDetection", activityDetection);
        }

        if (activityHandling != null) {
            realtimeInputConfig.put("activityHandling", activityHandling);
        }
        if (mediaResolution != null) {
            realtimeInputConfig.put("mediaResolution", mediaResolution);
        }

        if (!realtimeInputConfig.isEmpty()) {
            setup.put("realtimeInputConfig", realtimeInputConfig);
        }

        // Session resumption configuration
        if (Boolean.TRUE.equals(sessionResumption)) {
            Map<String, Object> sessionResumptionConfig = new LinkedHashMap<>();
            if (sessionResumptionHandle != null) {
                sessionResumptionConfig.put("handle", sessionResumptionHandle);
            }
            setup.put("sessionResumption", sessionResumptionConfig);
        }

        // Context window compression configuration (requires triggerTokens)
        if (Boolean.TRUE.equals(contextWindowCompression) && triggerTokens != null) {
            Map<String, Object> compressionConfig = new LinkedHashMap<>();
            compressionConfig.put("triggerTokens", triggerTokens);
            if (slidingWindowTokens != null) {
                compressionConfig.put("slidingWindow", Map.of("targetTokens", slidingWindowTokens));
            }
            setup.put("contextWindowCompression", compressionConfig);
        }

        // Transcription configuration (from LiveConfig or formatter settings)
        boolean enableInputTranscript =
                Boolean.TRUE.equals(inputAudioTranscription) || config.isEnableInputTranscription();
        boolean enableOutputTranscript =
                Boolean.TRUE.equals(outputAudioTranscription)
                        || config.isEnableOutputTranscription();
        if (enableInputTranscript) {
            setup.put("inputAudioTranscription", Map.of());
        }
        if (enableOutputTranscript) {
            setup.put("outputAudioTranscription", Map.of());
        }

        // Native Audio configuration
        if (Boolean.TRUE.equals(proactiveAudio)) {
            setup.put("proactiveAudio", Map.of("enabled", true));
        }
        if (Boolean.TRUE.equals(affectiveDialog)) {
            setup.put("affectiveDialog", Map.of("enabled", true));
        }
        if (Boolean.TRUE.equals(enableThinking)) {
            Map<String, Object> thinkingConfig = new LinkedHashMap<>();
            thinkingConfig.put("enabled", true);
            if (thinkingBudget != null) {
                thinkingConfig.put("thinkingBudget", thinkingBudget);
            }
            setup.put("thinking", thinkingConfig);
        }

        return toJson(Map.of("setup", setup));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected LiveEvent parseOutputFromJson(String json) {
        Map<String, Object> msg = fromJson(json, Map.class);

        // setupComplete
        if (msg.containsKey("setupComplete")) {
            return LiveEvent.sessionCreated(Msg.builder().metadata(Map.of("raw", json)).build());
        }

        // serverContent
        if (msg.containsKey("serverContent")) {
            return parseServerContent((Map<String, Object>) msg.get("serverContent"), json);
        }

        // toolCall
        if (msg.containsKey("toolCall")) {
            return parseToolCall((Map<String, Object>) msg.get("toolCall"));
        }

        // toolCallCancellation
        if (msg.containsKey("toolCallCancellation")) {
            Map<String, Object> cancellation =
                    (Map<String, Object>) msg.get("toolCallCancellation");
            List<String> ids = (List<String>) cancellation.get("ids");
            return LiveEvent.toolCallCancellation(
                    Msg.builder()
                            .metadata(Map.of("cancelled_ids", ids != null ? ids : List.of()))
                            .build());
        }

        // sessionResumptionUpdate
        if (msg.containsKey("sessionResumptionUpdate")) {
            Map<String, Object> update = (Map<String, Object>) msg.get("sessionResumptionUpdate");
            String newHandle = (String) update.get("newHandle");
            Boolean resumable = (Boolean) update.get("resumable");
            return LiveEvent.sessionResumption(newHandle, Boolean.TRUE.equals(resumable));
        }

        // goAway
        if (msg.containsKey("goAway")) {
            Map<String, Object> goAway = (Map<String, Object>) msg.get("goAway");
            Map<String, Object> timeLeft = (Map<String, Object>) goAway.get("timeLeft");
            long seconds = 0;
            if (timeLeft != null && timeLeft.containsKey("seconds")) {
                seconds = ((Number) timeLeft.get("seconds")).longValue();
            }
            return LiveEvent.goAway(seconds * 1000);
        }

        // usageMetadata
        if (msg.containsKey("usageMetadata")) {
            return LiveEvent.usageMetadata(
                    Msg.builder().metadata(Map.of("usage", msg.get("usageMetadata"))).build());
        }

        // error
        if (msg.containsKey("error")) {
            Map<String, Object> error = (Map<String, Object>) msg.get("error");
            String code = error != null ? (String) error.get("code") : "unknown";
            String message = error != null ? (String) error.get("message") : "Unknown error";
            return LiveEvent.error(code != null ? code : "GEMINI_ERROR", message);
        }

        return LiveEvent.unknown("gemini", msg);
    }

    /**
     * Parses serverContent message.
     *
     * @param serverContent the serverContent object
     * @param rawMessage the raw JSON message
     * @return parsed LiveEvent
     */
    @SuppressWarnings("unchecked")
    private LiveEvent parseServerContent(Map<String, Object> serverContent, String rawMessage) {
        // turnComplete
        if (Boolean.TRUE.equals(serverContent.get("turnComplete"))) {
            return LiveEvent.turnComplete(
                    Msg.builder().metadata(Map.of("raw", rawMessage)).build());
        }

        // generationComplete
        if (Boolean.TRUE.equals(serverContent.get("generationComplete"))) {
            return LiveEvent.generationComplete(
                    Msg.builder().metadata(Map.of("raw", rawMessage)).build());
        }

        // interrupted
        if (Boolean.TRUE.equals(serverContent.get("interrupted"))) {
            return LiveEvent.interrupted();
        }

        // inputTranscription
        if (serverContent.containsKey("inputTranscription")) {
            Map<String, Object> transcription =
                    (Map<String, Object>) serverContent.get("inputTranscription");
            String text = (String) transcription.get("text");
            return LiveEvent.inputTranscription(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TranscriptionBlock.input(text != null ? text : ""))
                            .build(),
                    false);
        }

        // outputTranscription
        if (serverContent.containsKey("outputTranscription")) {
            Map<String, Object> transcription =
                    (Map<String, Object>) serverContent.get("outputTranscription");
            String text = (String) transcription.get("text");
            return LiveEvent.outputTranscription(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TranscriptionBlock.output(text != null ? text : ""))
                            .build(),
                    false);
        }

        // modelTurn
        if (serverContent.containsKey("modelTurn")) {
            return parseModelTurn((Map<String, Object>) serverContent.get("modelTurn"));
        }

        return LiveEvent.unknown("serverContent", serverContent);
    }

    /**
     * Parses modelTurn message.
     *
     * @param modelTurn the modelTurn object
     * @return parsed LiveEvent
     */
    @SuppressWarnings("unchecked")
    private LiveEvent parseModelTurn(Map<String, Object> modelTurn) {
        List<Map<String, Object>> parts = (List<Map<String, Object>>) modelTurn.get("parts");
        if (parts == null || parts.isEmpty()) {
            return LiveEvent.unknown("modelTurn", modelTurn);
        }

        Map<String, Object> part = parts.get(0);

        // Audio output
        if (part.containsKey("inlineData")) {
            Map<String, Object> inlineData = (Map<String, Object>) part.get("inlineData");
            String data = (String) inlineData.get("data");
            byte[] audioData = decodeBase64(data);
            return LiveEvent.audioDelta(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(
                                    AudioBlock.builder()
                                            .source(RawSource.pcm24kMono(audioData))
                                            .build())
                            .build(),
                    false);
        }

        // Text output
        if (part.containsKey("text")) {
            String text = (String) part.get("text");

            // Check if this is thought/reasoning content
            Object thoughtFlag = part.get("thought");
            boolean isThought = Boolean.TRUE.equals(thoughtFlag);

            if (isThought) {
                return LiveEvent.thinkingDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(ThinkingBlock.builder().thinking(text).build())
                                .build(),
                        false);
            }

            return LiveEvent.textDelta(
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text(text).build())
                            .build(),
                    false);
        }

        return LiveEvent.unknown("modelTurn.part", part);
    }

    /**
     * Parses tool call message.
     *
     * @param toolCall the toolCall object
     * @return parsed LiveEvent
     */
    @SuppressWarnings("unchecked")
    private LiveEvent parseToolCall(Map<String, Object> toolCall) {
        List<Map<String, Object>> functionCalls =
                (List<Map<String, Object>>) toolCall.get("functionCalls");
        if (functionCalls == null || functionCalls.isEmpty()) {
            return LiveEvent.unknown("toolCall", toolCall);
        }

        Map<String, Object> funcCall = functionCalls.get(0);
        String id = (String) funcCall.get("id");
        String name = (String) funcCall.get("name");
        Object args = funcCall.get("args");

        // Convert args to Map<String, Object> if possible
        Map<String, Object> inputMap = null;
        if (args instanceof Map) {
            inputMap = (Map<String, Object>) args;
        }

        return LiveEvent.toolCall(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name(name)
                                        .input(inputMap)
                                        .content(args != null ? toJson(args) : "{}")
                                        .build())
                        .build(),
                true);
    }

    /**
     * Creates a new builder for GeminiLiveFormatter.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for GeminiLiveFormatter. */
    public static class Builder {
        private String modelName;
        private Boolean proactiveAudio;
        private Boolean affectiveDialog;
        private Boolean enableThinking;
        private Integer thinkingBudget;
        private Boolean contextWindowCompression;
        private Integer triggerTokens;
        private Integer slidingWindowTokens;
        private Boolean sessionResumption;
        private String sessionResumptionHandle;
        private String activityHandling;
        private String mediaResolution;
        private List<String> responseModalities;
        private Boolean inputAudioTranscription;
        private Boolean outputAudioTranscription;
        private SpeechSensitivity startOfSpeechSensitivity;
        private SpeechSensitivity endOfSpeechSensitivity;
        private Integer silenceDurationMs;
        private Integer prefixPaddingMs;

        /**
         * Sets the model name.
         *
         * @param modelName the model name
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets whether to enable proactive audio.
         *
         * @param proactiveAudio true to enable proactive audio
         * @return this builder
         */
        public Builder proactiveAudio(Boolean proactiveAudio) {
            this.proactiveAudio = proactiveAudio;
            return this;
        }

        /**
         * Sets whether to enable affective dialog.
         *
         * @param affectiveDialog true to enable affective dialog
         * @return this builder
         */
        public Builder affectiveDialog(Boolean affectiveDialog) {
            this.affectiveDialog = affectiveDialog;
            return this;
        }

        /**
         * Sets whether to enable thinking capability.
         *
         * @param enableThinking true to enable thinking
         * @return this builder
         */
        public Builder enableThinking(Boolean enableThinking) {
            this.enableThinking = enableThinking;
            return this;
        }

        /**
         * Sets the thinking budget in tokens.
         *
         * @param thinkingBudget the thinking budget
         * @return this builder
         */
        public Builder thinkingBudget(Integer thinkingBudget) {
            this.thinkingBudget = thinkingBudget;
            return this;
        }

        /**
         * Sets whether to enable context window compression.
         *
         * @param contextWindowCompression true to enable compression
         * @return this builder
         */
        public Builder contextWindowCompression(Boolean contextWindowCompression) {
            this.contextWindowCompression = contextWindowCompression;
            return this;
        }

        /**
         * Sets the target tokens for sliding window.
         *
         * @param slidingWindowTokens the target tokens
         * @return this builder
         */
        public Builder slidingWindowTokens(Integer slidingWindowTokens) {
            this.slidingWindowTokens = slidingWindowTokens;
            return this;
        }

        /**
         * Sets the trigger tokens for context window compression.
         *
         * @param triggerTokens the trigger tokens threshold
         * @return this builder
         */
        public Builder triggerTokens(Integer triggerTokens) {
            this.triggerTokens = triggerTokens;
            return this;
        }

        /**
         * Sets whether to enable session resumption.
         *
         * @param sessionResumption true to enable session resumption
         * @return this builder
         */
        public Builder sessionResumption(Boolean sessionResumption) {
            this.sessionResumption = sessionResumption;
            return this;
        }

        /**
         * Sets the session resumption handle from a previous session.
         *
         * @param sessionResumptionHandle the previous session handle
         * @return this builder
         */
        public Builder sessionResumptionHandle(String sessionResumptionHandle) {
            this.sessionResumptionHandle = sessionResumptionHandle;
            return this;
        }

        /**
         * Sets the activity handling mode.
         *
         * @param activityHandling the activity handling mode
         * @return this builder
         */
        public Builder activityHandling(String activityHandling) {
            this.activityHandling = activityHandling;
            return this;
        }

        /**
         * Sets the media resolution.
         *
         * @param mediaResolution the media resolution
         * @return this builder
         */
        public Builder mediaResolution(String mediaResolution) {
            this.mediaResolution = mediaResolution;
            return this;
        }

        /**
         * Sets the response modalities.
         *
         * @param responseModalities the response modalities (TEXT, AUDIO, IMAGE)
         * @return this builder
         */
        public Builder responseModalities(List<String> responseModalities) {
            this.responseModalities = responseModalities;
            return this;
        }

        /**
         * Sets whether to enable input audio transcription.
         *
         * @param inputAudioTranscription true to enable input transcription
         * @return this builder
         */
        public Builder inputAudioTranscription(Boolean inputAudioTranscription) {
            this.inputAudioTranscription = inputAudioTranscription;
            return this;
        }

        /**
         * Sets whether to enable output audio transcription.
         *
         * @param outputAudioTranscription true to enable output transcription
         * @return this builder
         */
        public Builder outputAudioTranscription(Boolean outputAudioTranscription) {
            this.outputAudioTranscription = outputAudioTranscription;
            return this;
        }

        /**
         * Sets the start of speech sensitivity.
         *
         * @param startOfSpeechSensitivity the sensitivity level
         * @return this builder
         */
        public Builder startOfSpeechSensitivity(SpeechSensitivity startOfSpeechSensitivity) {
            this.startOfSpeechSensitivity = startOfSpeechSensitivity;
            return this;
        }

        /**
         * Sets the end of speech sensitivity.
         *
         * @param endOfSpeechSensitivity the sensitivity level
         * @return this builder
         */
        public Builder endOfSpeechSensitivity(SpeechSensitivity endOfSpeechSensitivity) {
            this.endOfSpeechSensitivity = endOfSpeechSensitivity;
            return this;
        }

        /**
         * Sets the silence duration in milliseconds.
         *
         * @param silenceDurationMs the silence duration
         * @return this builder
         */
        public Builder silenceDurationMs(Integer silenceDurationMs) {
            this.silenceDurationMs = silenceDurationMs;
            return this;
        }

        /**
         * Sets the prefix padding in milliseconds.
         *
         * @param prefixPaddingMs the prefix padding
         * @return this builder
         */
        public Builder prefixPaddingMs(Integer prefixPaddingMs) {
            this.prefixPaddingMs = prefixPaddingMs;
            return this;
        }

        /**
         * Builds a new GeminiLiveFormatter.
         *
         * @return a new GeminiLiveFormatter instance
         */
        public GeminiLiveFormatter build() {
            return new GeminiLiveFormatter(
                    modelName,
                    proactiveAudio,
                    affectiveDialog,
                    enableThinking,
                    thinkingBudget,
                    contextWindowCompression,
                    triggerTokens,
                    slidingWindowTokens,
                    sessionResumption,
                    sessionResumptionHandle,
                    activityHandling,
                    mediaResolution,
                    responseModalities,
                    inputAudioTranscription,
                    outputAudioTranscription,
                    startOfSpeechSensitivity,
                    endOfSpeechSensitivity,
                    silenceDurationMs,
                    prefixPaddingMs);
        }
    }
}
