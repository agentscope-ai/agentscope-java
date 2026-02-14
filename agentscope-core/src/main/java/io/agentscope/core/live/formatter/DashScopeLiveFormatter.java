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
import io.agentscope.core.live.audio.AudioFormat;
import io.agentscope.core.live.audio.DashScopeModality;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TranscriptionBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DashScope Realtime API Formatter.
 *
 * <p>Extends {@link AbstractTextLiveFormatter}, implements {@code LiveFormatter<String>}. Works
 * directly with String (JSON), no byte[] conversion needed.
 *
 * <p>DashScope characteristics:
 *
 * <ul>
 *   <li>Supports image input (JPG/JPEG, ≤500KB)
 *   <li>Does not support text input
 *   <li>Does not support tool calling
 *   <li>Does not support session resumption
 * </ul>
 */
public class DashScopeLiveFormatter extends AbstractTextLiveFormatter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeLiveFormatter.class);

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
    private final boolean vadEnabled;

    /**
     * Creates a new DashScopeLiveFormatter.
     *
     * @param inputAudioFormat input audio format
     * @param outputAudioFormat output audio format
     * @param modality output modality configuration
     * @param smoothOutput whether to enable smooth output
     * @param repetitionPenalty repetition penalty value
     * @param presencePenalty presence penalty value
     * @param seed random seed for generation
     * @param maxTokens maximum tokens to generate
     * @param topK top-k sampling parameter
     * @param vadEnabled whether VAD is enabled
     */
    public DashScopeLiveFormatter(
            AudioFormat inputAudioFormat,
            AudioFormat outputAudioFormat,
            DashScopeModality modality,
            Boolean smoothOutput,
            Float repetitionPenalty,
            Float presencePenalty,
            Long seed,
            Integer maxTokens,
            Integer topK,
            boolean vadEnabled) {
        this.inputAudioFormat =
                inputAudioFormat != null ? inputAudioFormat : AudioFormat.dashScopeDefaultInput();
        this.outputAudioFormat =
                outputAudioFormat != null ? outputAudioFormat : AudioFormat.PCM16_OUTPUT;
        this.modality = modality != null ? modality : DashScopeModality.TEXT_AND_AUDIO;
        this.smoothOutput = smoothOutput;
        this.repetitionPenalty = repetitionPenalty;
        this.presencePenalty = presencePenalty;
        this.seed = seed;
        this.maxTokens = maxTokens;
        this.topK = topK;
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
        // DashScope supports image input
        if (block instanceof ImageBlock imageBlock) {
            byte[] imageData = extractImageData(imageBlock);
            if (imageData != null) {
                return toJson(
                        Map.of(
                                "type",
                                "input_audio_buffer.append",
                                "image",
                                encodeBase64(imageData)));
            }
        }
        // DashScope does not support text input
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected LiveEvent parseOutputFromJson(String json) {
        Map<String, Object> msg = fromJson(json, Map.class);
        String type = (String) msg.get("type");

        return switch (type) {
            case "session.created" ->
                    LiveEvent.sessionCreated(Msg.builder().metadata(Map.of("raw", json)).build());

            case "session.updated" ->
                    LiveEvent.sessionUpdated(Msg.builder().metadata(Map.of("raw", json)).build());

            case "response.audio.delta" -> {
                String delta = (String) msg.get("delta");
                byte[] audioData = decodeBase64(delta);
                // Select RawSource factory based on output audio format
                RawSource source =
                        outputAudioFormat == AudioFormat.PCM24_OUTPUT
                                ? RawSource.pcm24k24bitMono(audioData)
                                : RawSource.pcm16kMono(audioData);
                yield LiveEvent.audioDelta(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(AudioBlock.builder().source(source).build())
                                .build(),
                        false);
            }

            case "response.audio_transcript.delta" -> {
                String delta = (String) msg.get("delta");
                yield LiveEvent.outputTranscription(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(TranscriptionBlock.outputPartial(delta))
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

            case "input_audio_buffer.speech_started" -> LiveEvent.speechStarted();

            case "input_audio_buffer.speech_stopped" -> LiveEvent.speechStopped();

            case "response.done" ->
                    LiveEvent.turnComplete(Msg.builder().metadata(Map.of("raw", json)).build());

            case "error" -> {
                Map<String, Object> error = (Map<String, Object>) msg.get("error");
                String code = error != null ? (String) error.get("code") : "unknown";
                String message = error != null ? (String) error.get("message") : "Unknown error";
                yield LiveEvent.error(
                        code != null ? code : "unknown",
                        message != null ? message : "Unknown error");
            }

            default -> LiveEvent.unknown(type, msg);
        };
    }

    @Override
    protected String buildSessionConfigJson(LiveConfig config, List<ToolSchema> toolSchemas) {
        Map<String, Object> session = new HashMap<>();

        // Output modalities configuration
        session.put("modalities", modality.toApiList());

        if (config.getVoice() != null) {
            session.put("voice", config.getVoice());
        }
        if (config.getInstructions() != null) {
            session.put("instructions", config.getInstructions());
        }

        // Audio format configuration
        session.put("input_audio_format", inputAudioFormat.getDashScopeInputApiValue());
        session.put("output_audio_format", outputAudioFormat.getDashScopeOutputApiValue());

        // VAD configuration
        if (vadEnabled) {
            session.put("turn_detection", Map.of("type", "server_vad"));
        } else {
            session.put("turn_detection", null);
        }

        // Transcription configuration
        if (config.isEnableInputTranscription()) {
            session.put("input_audio_transcription", Map.of("model", "gummy-realtime-v1"));
        }

        // DashScope-specific parameters
        Map<String, Object> parameters = new HashMap<>();
        if (smoothOutput != null) {
            parameters.put("smooth_output", smoothOutput);
        }
        if (repetitionPenalty != null) {
            parameters.put("repetition_penalty", repetitionPenalty);
        }
        if (presencePenalty != null) {
            parameters.put("presence_penalty", presencePenalty);
        }
        if (seed != null) {
            parameters.put("seed", seed);
        }
        if (maxTokens != null) {
            parameters.put("max_tokens", maxTokens);
        }
        if (topK != null) {
            parameters.put("top_k", topK);
        }

        // Generation parameters
        if (config.getGenerationConfig() != null) {
            if (config.getGenerationConfig().getTemperature() != null) {
                parameters.put("temperature", config.getGenerationConfig().getTemperature());
            }
            if (config.getGenerationConfig().getTopP() != null) {
                parameters.put("top_p", config.getGenerationConfig().getTopP());
            }
        }

        if (!parameters.isEmpty()) {
            session.put("parameters", parameters);
        }

        // DashScope does not support tool calling, ignore toolSchemas

        return toJson(Map.of("type", "session.update", "session", session));
    }

    /**
     * Extract image data from ImageBlock.
     *
     * <p>Supports Base64Source images. Validates image size against DashScope limit (500KB).
     *
     * @param imageBlock the image block
     * @return image byte data, or null if extraction fails or image is too large
     */
    private byte[] extractImageData(ImageBlock imageBlock) {
        Object source = imageBlock.getSource();
        if (source instanceof Base64Source base64Source) {
            String data = base64Source.getData();
            byte[] imageData = java.util.Base64.getDecoder().decode(data);
            // Validate image size <= 500KB (DashScope limit)
            if (imageData.length > 500 * 1024) {
                log.warn("Image too large: {} bytes, max 500KB", imageData.length);
                return null;
            }
            return imageData;
        }
        // URLSource not yet supported
        log.debug("Unsupported image source type: {}", source.getClass().getSimpleName());
        return null;
    }

    /**
     * Creates a new builder for DashScopeLiveFormatter.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DashScopeLiveFormatter. */
    public static class Builder {
        private AudioFormat inputAudioFormat;
        private AudioFormat outputAudioFormat;
        private DashScopeModality modality;
        private Boolean smoothOutput;
        private Float repetitionPenalty;
        private Float presencePenalty;
        private Long seed;
        private Integer maxTokens;
        private Integer topK;
        private boolean vadEnabled = true;

        /**
         * Sets the input audio format.
         *
         * @param inputAudioFormat the input audio format
         * @return this builder
         */
        public Builder inputAudioFormat(AudioFormat inputAudioFormat) {
            this.inputAudioFormat = inputAudioFormat;
            return this;
        }

        /**
         * Sets the output audio format.
         *
         * @param outputAudioFormat the output audio format
         * @return this builder
         */
        public Builder outputAudioFormat(AudioFormat outputAudioFormat) {
            this.outputAudioFormat = outputAudioFormat;
            return this;
        }

        /**
         * Sets the output modality.
         *
         * @param modality the output modality
         * @return this builder
         */
        public Builder modality(DashScopeModality modality) {
            this.modality = modality;
            return this;
        }

        /**
         * Sets whether to enable smooth output.
         *
         * @param smoothOutput true to enable smooth output
         * @return this builder
         */
        public Builder smoothOutput(Boolean smoothOutput) {
            this.smoothOutput = smoothOutput;
            return this;
        }

        /**
         * Sets the repetition penalty.
         *
         * @param repetitionPenalty the repetition penalty value
         * @return this builder
         */
        public Builder repetitionPenalty(Float repetitionPenalty) {
            this.repetitionPenalty = repetitionPenalty;
            return this;
        }

        /**
         * Sets the presence penalty.
         *
         * @param presencePenalty the presence penalty value
         * @return this builder
         */
        public Builder presencePenalty(Float presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Sets the random seed.
         *
         * @param seed the random seed
         * @return this builder
         */
        public Builder seed(Long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Sets the maximum tokens to generate.
         *
         * @param maxTokens the maximum tokens
         * @return this builder
         */
        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the top-k sampling parameter.
         *
         * @param topK the top-k value
         * @return this builder
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
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
         * Builds a new DashScopeLiveFormatter.
         *
         * @return a new DashScopeLiveFormatter instance
         */
        public DashScopeLiveFormatter build() {
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
    }
}
