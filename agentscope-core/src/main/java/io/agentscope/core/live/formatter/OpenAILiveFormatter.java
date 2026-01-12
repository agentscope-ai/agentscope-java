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
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.TranscriptionBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Realtime API (GA version) Formatter.
 *
 * <p>Extends {@link AbstractTextLiveFormatter}, implements {@code LiveFormatter<String>}. Works
 * directly with String (JSON), no byte[] conversion needed.
 *
 * <p>OpenAI characteristics:
 *
 * <ul>
 *   <li>Supports semantic VAD (semantic_vad)
 *   <li>Supports text input
 *   <li>Supports tool calling (including MCP)
 *   <li>Supports noise reduction (near_field/far_field)
 *   <li>Supports speed control (0.25-4.0)
 *   <li>Does not support session resumption
 * </ul>
 *
 * <p>Note: GA version event names differ from Beta version:
 *
 * <ul>
 *   <li>response.audio.delta → response.output_audio.delta
 *   <li>response.text.delta → response.output_text.delta
 *   <li>response.audio_transcript.delta → response.output_audio_transcript.delta
 * </ul>
 */
public class OpenAILiveFormatter extends AbstractTextLiveFormatter {

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

    /**
     * Creates a new OpenAILiveFormatter.
     *
     * @param sessionType session type
     * @param promptId prompt ID for server-stored prompts
     * @param promptVersion prompt version
     * @param promptVariables prompt variables
     * @param noiseReduction noise reduction type (near_field/far_field)
     * @param speed output audio speed (0.25-4.0)
     * @param transcriptionModel transcription model name
     * @param streamingTranscription whether to enable streaming transcription
     * @param semanticVad whether to use semantic VAD
     * @param vadThreshold VAD threshold
     * @param silenceDurationMs silence duration in milliseconds
     * @param prefixPaddingMs prefix padding in milliseconds
     * @param idleTimeoutMs idle timeout in milliseconds
     * @param vadEnabled whether VAD is enabled
     */
    public OpenAILiveFormatter(
            String sessionType,
            String promptId,
            String promptVersion,
            Map<String, String> promptVariables,
            String noiseReduction,
            Float speed,
            String transcriptionModel,
            Boolean streamingTranscription,
            boolean semanticVad,
            Float vadThreshold,
            Integer silenceDurationMs,
            Integer prefixPaddingMs,
            Integer idleTimeoutMs,
            boolean vadEnabled) {
        this.sessionType = sessionType;
        this.promptId = promptId;
        this.promptVersion = promptVersion;
        this.promptVariables = promptVariables;
        this.noiseReduction = noiseReduction;
        this.speed = speed;
        this.transcriptionModel = transcriptionModel;
        this.streamingTranscription = streamingTranscription;
        this.semanticVad = semanticVad;
        this.vadThreshold = vadThreshold;
        this.silenceDurationMs = silenceDurationMs;
        this.prefixPaddingMs = prefixPaddingMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.vadEnabled = vadEnabled;
    }

    @Override
    protected String formatControl(ControlBlock controlBlock) {
        String type =
                switch (controlBlock.getControlType()) {
                    case COMMIT -> "input_audio_buffer.commit";
                    case CLEAR -> "input_audio_buffer.clear";
                    case INTERRUPT -> "response.cancel";
                    case CREATE_RESPONSE -> "response.create";
                };
        return toJson(Map.of("type", type));
    }

    @Override
    protected String formatAudio(byte[] audioData) {
        return toJson(
                Map.of("type", "input_audio_buffer.append", "audio", encodeBase64(audioData)));
    }

    @Override
    protected String formatOtherContent(Msg msg, ContentBlock block) {
        // OpenAI supports text input
        if (block instanceof TextBlock textBlock) {
            return formatTextInput(textBlock.getText());
        }

        // Tool result
        if (block instanceof ToolResultBlock toolResult) {
            return formatToolResult(toolResult);
        }

        return null;
    }

    /**
     * Formats text input as a conversation item.
     *
     * @param text the text to send
     * @return JSON string for text input
     */
    private String formatTextInput(String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", "user");
        item.put("content", List.of(Map.of("type", "input_text", "text", text)));

        return toJson(Map.of("type", "conversation.item.create", "item", item));
    }

    /**
     * Formats tool call result.
     *
     * @param toolResult the tool result block
     * @return JSON string for tool result
     */
    private String formatToolResult(ToolResultBlock toolResult) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", toolResult.getId());
        // Convert output blocks to string
        String output = extractToolResultOutput(toolResult);
        item.put("output", output);

        return toJson(Map.of("type", "conversation.item.create", "item", item));
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
        Map<String, Object> session = new LinkedHashMap<>();

        // Basic configuration
        if (config.getVoice() != null) {
            session.put("voice", config.getVoice());
        }
        if (config.getInstructions() != null) {
            session.put("instructions", config.getInstructions());
        }

        // Response modalities (default AUDIO)
        session.put("modalities", List.of("audio", "text"));

        // Audio format (OpenAI uses 24kHz)
        session.put("input_audio_format", "pcm16");
        session.put("output_audio_format", "pcm16");

        // VAD configuration
        if (vadEnabled) {
            Map<String, Object> turnDetection = new LinkedHashMap<>();
            turnDetection.put("type", semanticVad ? "semantic_vad" : "server_vad");

            if (vadThreshold != null) {
                turnDetection.put("threshold", vadThreshold);
            }
            if (silenceDurationMs != null) {
                turnDetection.put("silence_duration_ms", silenceDurationMs);
            }
            if (prefixPaddingMs != null) {
                turnDetection.put("prefix_padding_ms", prefixPaddingMs);
            }
            if (idleTimeoutMs != null) {
                turnDetection.put("idle_timeout_ms", idleTimeoutMs);
            }

            session.put("turn_detection", turnDetection);
        } else {
            session.put("turn_detection", null);
        }

        // Transcription configuration
        if (config.isEnableInputTranscription()) {
            Map<String, Object> transcription = new LinkedHashMap<>();
            transcription.put(
                    "model", transcriptionModel != null ? transcriptionModel : "gpt-4o-transcribe");
            if (streamingTranscription != null && streamingTranscription) {
                transcription.put("streaming", true);
            }
            session.put("input_audio_transcription", transcription);
        }

        // Tool configuration
        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolSchema tool : toolSchemas) {
                Map<String, Object> toolDef = new LinkedHashMap<>();
                toolDef.put("type", "function");
                toolDef.put("name", tool.getName());
                if (tool.getDescription() != null) {
                    toolDef.put("description", tool.getDescription());
                }
                if (tool.getParameters() != null) {
                    toolDef.put("parameters", tool.getParameters());
                }
                tools.add(toolDef);
            }
            session.put("tools", tools);
        }

        // OpenAI-specific configuration
        if (noiseReduction != null) {
            session.put("input_audio_noise_reduction", Map.of("type", noiseReduction));
        }
        if (speed != null) {
            session.put("output_audio_speed", speed);
        }

        // Generation parameters
        if (config.getGenerationConfig() != null) {
            if (config.getGenerationConfig().getTemperature() != null) {
                session.put("temperature", config.getGenerationConfig().getTemperature());
            }
            if (config.getGenerationConfig().getMaxTokens() != null) {
                session.put(
                        "max_response_output_tokens", config.getGenerationConfig().getMaxTokens());
            }
        }

        // Prompt configuration (OpenAI-specific)
        if (promptId != null) {
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("id", promptId);
            if (promptVersion != null) {
                prompt.put("version", promptVersion);
            }
            if (promptVariables != null && !promptVariables.isEmpty()) {
                prompt.put("variables", promptVariables);
            }
            session.put("prompt", prompt);
        }

        return toJson(Map.of("type", "session.update", "session", session));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected LiveEvent parseOutputFromJson(String json) {
        Map<String, Object> msg = fromJson(json, Map.class);
        String type = (String) msg.get("type");

        if (type == null) {
            return LiveEvent.unknown("null", msg);
        }

        return switch (type) {
            // Session lifecycle
            case "session.created" ->
                    LiveEvent.sessionCreated(Msg.builder().metadata(Map.of("raw", json)).build());

            case "session.updated" ->
                    LiveEvent.sessionUpdated(Msg.builder().metadata(Map.of("raw", json)).build());

            // Audio output (GA version)
            case "response.output_audio.delta" -> {
                String delta = (String) msg.get("delta");
                byte[] audioData = decodeBase64(delta);
                yield LiveEvent.audioDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        AudioBlock.builder()
                                                .source(RawSource.pcm24kMono(audioData))
                                                .build())
                                .build(),
                        false);
            }

            // Text output (GA version)
            case "response.output_text.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.textDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(delta).build())
                                .build(),
                        false);
            }

            // Output transcription (GA version)
            case "response.output_audio_transcript.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TranscriptionBlock.outputPartial(delta))
                                .build(),
                        false);
            }

            case "response.output_audio_transcript.done" -> {
                String transcript = (String) msg.get("transcript");
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        TranscriptionBlock.output(
                                                transcript != null ? transcript : ""))
                                .build(),
                        true);
            }

            // Input transcription
            case "conversation.item.input_audio_transcription.completed" -> {
                String transcript = (String) msg.get("transcript");
                yield LiveEvent.inputTranscription(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        TranscriptionBlock.input(
                                                transcript != null ? transcript : ""))
                                .build(),
                        true);
            }

            case "conversation.item.input_audio_transcription.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.inputTranscription(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        TranscriptionBlock.inputPartial(delta != null ? delta : ""))
                                .build(),
                        false);
            }

            // Tool call
            case "response.function_call_arguments.done" -> {
                String callId = (String) msg.get("call_id");
                String name = (String) msg.get("name");
                String arguments = (String) msg.get("arguments");
                // Parse arguments JSON string to Map
                Map<String, Object> inputMap = null;
                if (arguments != null && !arguments.isEmpty()) {
                    try {
                        inputMap = fromJson(arguments, Map.class);
                    } catch (Exception e) {
                        // If parsing fails, store raw string in content
                        inputMap = null;
                    }
                }
                yield LiveEvent.toolCall(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id(callId)
                                                .name(name)
                                                .input(inputMap)
                                                .content(arguments)
                                                .build())
                                .build(),
                        true);
            }

            // VAD events
            case "input_audio_buffer.speech_started" -> LiveEvent.speechStarted();
            case "input_audio_buffer.speech_stopped" -> LiveEvent.speechStopped();
            case "input_audio_buffer.timeout_triggered" ->
                    LiveEvent.error(
                            LiveErrorType.OPENAI_VAD_IDLE_TIMEOUT, "VAD idle timeout triggered");

            // Turn complete
            case "response.done" -> {
                Map<String, Object> response = (Map<String, Object>) msg.get("response");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("raw", json);
                if (response != null && response.containsKey("usage")) {
                    metadata.put("usage", response.get("usage"));
                }
                yield LiveEvent.turnComplete(Msg.builder().metadata(metadata).build());
            }

            // Error
            case "error" -> {
                Map<String, Object> error = (Map<String, Object>) msg.get("error");
                String code = error != null ? (String) error.get("code") : "unknown";
                String message = error != null ? (String) error.get("message") : "Unknown error";
                yield LiveEvent.error(
                        code != null ? code : "unknown",
                        message != null ? message : "Unknown error");
            }

            // Compatibility with Beta version event names
            case "response.audio.delta" -> {
                String delta = (String) msg.get("delta");
                byte[] audioData = decodeBase64(delta);
                yield LiveEvent.audioDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        AudioBlock.builder()
                                                .source(RawSource.pcm24kMono(audioData))
                                                .build())
                                .build(),
                        false);
            }

            case "response.text.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.textDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text(delta).build())
                                .build(),
                        false);
            }

            // Beta version: response.audio_transcript.delta (output transcription)
            case "response.audio_transcript.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        TranscriptionBlock.outputPartial(
                                                delta != null ? delta : ""))
                                .build(),
                        false);
            }

            case "response.audio_transcript.done" -> {
                String transcript = (String) msg.get("transcript");
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        TranscriptionBlock.output(
                                                transcript != null ? transcript : ""))
                                .build(),
                        true);
            }

            default -> LiveEvent.unknown(type, msg);
        };
    }

    /**
     * Creates a new builder for OpenAILiveFormatter.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for OpenAILiveFormatter. */
    public static class Builder {
        private String sessionType;
        private String promptId;
        private String promptVersion;
        private Map<String, String> promptVariables;
        private String noiseReduction;
        private Float speed;
        private String transcriptionModel;
        private Boolean streamingTranscription;
        private boolean semanticVad = true;
        private Float vadThreshold;
        private Integer silenceDurationMs;
        private Integer prefixPaddingMs;
        private Integer idleTimeoutMs;
        private boolean vadEnabled = true;

        /**
         * Sets the session type.
         *
         * @param sessionType the session type
         * @return this builder
         */
        public Builder sessionType(String sessionType) {
            this.sessionType = sessionType;
            return this;
        }

        /**
         * Sets the prompt ID for server-stored prompts.
         *
         * @param promptId the prompt ID
         * @return this builder
         */
        public Builder promptId(String promptId) {
            this.promptId = promptId;
            return this;
        }

        /**
         * Sets the prompt version.
         *
         * @param promptVersion the prompt version
         * @return this builder
         */
        public Builder promptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
            return this;
        }

        /**
         * Sets the prompt variables.
         *
         * @param promptVariables the prompt variables
         * @return this builder
         */
        public Builder promptVariables(Map<String, String> promptVariables) {
            this.promptVariables = promptVariables;
            return this;
        }

        /**
         * Sets the noise reduction type.
         *
         * @param noiseReduction the noise reduction type (near_field/far_field)
         * @return this builder
         */
        public Builder noiseReduction(String noiseReduction) {
            this.noiseReduction = noiseReduction;
            return this;
        }

        /**
         * Sets the output audio speed.
         *
         * @param speed the speed (0.25-4.0)
         * @return this builder
         */
        public Builder speed(Float speed) {
            this.speed = speed;
            return this;
        }

        /**
         * Sets the transcription model.
         *
         * @param transcriptionModel the transcription model name
         * @return this builder
         */
        public Builder transcriptionModel(String transcriptionModel) {
            this.transcriptionModel = transcriptionModel;
            return this;
        }

        /**
         * Sets whether to enable streaming transcription.
         *
         * @param streamingTranscription true to enable streaming transcription
         * @return this builder
         */
        public Builder streamingTranscription(Boolean streamingTranscription) {
            this.streamingTranscription = streamingTranscription;
            return this;
        }

        /**
         * Sets whether to use semantic VAD.
         *
         * @param semanticVad true to use semantic VAD
         * @return this builder
         */
        public Builder semanticVad(boolean semanticVad) {
            this.semanticVad = semanticVad;
            return this;
        }

        /**
         * Sets the VAD threshold.
         *
         * @param vadThreshold the VAD threshold
         * @return this builder
         */
        public Builder vadThreshold(Float vadThreshold) {
            this.vadThreshold = vadThreshold;
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
         * Sets the idle timeout in milliseconds.
         *
         * @param idleTimeoutMs the idle timeout
         * @return this builder
         */
        public Builder idleTimeoutMs(Integer idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        /**
         * Sets whether VAD is enabled.
         *
         * @param vadEnabled true to enable VAD
         * @return this builder
         */
        public Builder vadEnabled(boolean vadEnabled) {
            this.vadEnabled = vadEnabled;
            return this;
        }

        /**
         * Builds a new OpenAILiveFormatter.
         *
         * @return a new OpenAILiveFormatter instance
         */
        public OpenAILiveFormatter build() {
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
    }
}
