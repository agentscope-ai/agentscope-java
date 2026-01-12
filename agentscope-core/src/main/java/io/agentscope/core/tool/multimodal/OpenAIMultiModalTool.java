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
package io.agentscope.core.tool.multimodal;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.OpenAIClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * OpenAI multimodal tool.
 *
 * <p>Supports:
 * <ul>
 *   <li>Text to image(s)</li>
 *   <li>Image to text (via vision models)</li>
 *   <li>Text to audio (speech)</li>
 *   <li>Audio to text (transcription)</li>
 * </ul>
 *
 * <p>This implementation uses the custom {@link OpenAIClient} HTTP client instead of the OpenAI
 * Java SDK, keeping the core module lightweight and dependency-free.
 *
 * <p>Please refer to the <a href="https://platform.openai.com/">OpenAI documentation</a> for more
 * details.
 */
public class OpenAIMultiModalTool {

    private static final Logger log = LoggerFactory.getLogger(OpenAIMultiModalTool.class);

    /** OpenAI API key. */
    private final String apiKey;

    /** OpenAI client for API calls. */
    private final OpenAIClient client;

    /** Base URL for OpenAI API (defaults to https://api.openai.com). */
    private final String baseUrl;

    /**
     * Create a new OpenAIMultiModalTool with default base URL.
     *
     * @param apiKey the OpenAI API key
     */
    public OpenAIMultiModalTool(String apiKey) {
        this(apiKey, null);
    }

    /**
     * Create a new OpenAIMultiModalTool with custom base URL.
     *
     * @param apiKey the OpenAI API key
     * @param baseUrl the base URL (null for default https://api.openai.com)
     */
    public OpenAIMultiModalTool(String apiKey, String baseUrl) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenAI API key cannot be empty.");
        }
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.client = new OpenAIClient();
    }

    /**
     * Create a new OpenAIMultiModalTool with custom client (for testing).
     *
     * @param client the OpenAI client
     */
    protected OpenAIMultiModalTool(OpenAIClient client) {
        this.apiKey = "test-key";
        this.baseUrl = null;
        this.client = client;
    }

    /**
     * Generate image(s) based on the given prompt.
     *
     * @param prompt the text prompt to generate image
     * @param model the model to use (e.g., "dall-e-3", "dall-e-2")
     * @param n the number of images to generate (1 for dall-e-3, 1-10 for dall-e-2)
     * @param size the size of the image (e.g., "1024x1024", "1792x1024", "1024x1792")
     * @param quality the quality of the image ("standard" or "hd" for dall-e-3)
     * @param responseFormat the format of the response ("url" or "b64_json")
     * @return a ToolResultBlock containing the generated image(s)
     */
    @Tool(
            name = "openai_text_to_image",
            description =
                    "Generate image(s) based on the given prompt using OpenAI DALL-E models. "
                            + "Returns image URL(s) or base64 data.")
    public Mono<ToolResultBlock> openaiTextToImage(
            @ToolParam(name = "prompt", description = "The text prompt to generate image")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description = "The model to use, e.g., 'dall-e-3', 'dall-e-2'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description =
                                    "The number of images to generate (1 for dall-e-3, 1-10 for"
                                            + " dall-e-2)",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description =
                                    "Size of the image, e.g., '1024x1024', '1792x1024',"
                                            + " '1024x1792'",
                            required = false)
                    String size,
            @ToolParam(
                            name = "quality",
                            description =
                                    "The quality of the image ('standard' or 'hd' for dall-e-3)",
                            required = false)
                    String quality,
            @ToolParam(
                            name = "response_format",
                            description = "The format of the response ('url' or 'b64_json')",
                            required = false)
                    String responseFormat) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("dall-e-3");
        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse("1024x1024");
        String finalQuality =
                Optional.ofNullable(quality).filter(s -> !s.trim().isEmpty()).orElse("standard");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("url");

        log.debug(
                "openai_text_to_image called: prompt='{}', model='{}', n='{}', size='{}',"
                        + " quality='{}', responseFormat='{}'",
                prompt,
                finalModel,
                finalN,
                finalSize,
                finalQuality,
                finalResponseFormat);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> request = new HashMap<>();
                            request.put("prompt", prompt);
                            request.put("model", finalModel);
                            request.put("n", finalN);
                            request.put("size", finalSize);
                            if ("dall-e-3".equals(finalModel)) {
                                request.put("quality", finalQuality);
                            }
                            request.put("response_format", finalResponseFormat);

                            String responseBody =
                                    client.callApi(
                                            apiKey, baseUrl, "/v1/images/generations", request);
                            Map<String, Object> response =
                                    JsonUtils.getJsonCodec()
                                            .fromJson(
                                                    responseBody,
                                                    new TypeReference<Map<String, Object>>() {});

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> data =
                                    (List<Map<String, Object>>) response.get("data");
                            if (data == null || data.isEmpty()) {
                                log.error("No image data returned.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (Map<String, Object> item : data) {
                                if ("b64_json".equals(finalResponseFormat)) {
                                    String base64Data = (String) item.get("b64_json");
                                    if (base64Data != null && !base64Data.trim().isEmpty()) {
                                        contentBlocks.add(
                                                ImageBlock.builder()
                                                        .source(
                                                                Base64Source.builder()
                                                                        .mediaType("image/png")
                                                                        .data(base64Data)
                                                                        .build())
                                                        .build());
                                    }
                                } else {
                                    String url = (String) item.get("url");
                                    if (url != null && !url.trim().isEmpty()) {
                                        contentBlocks.add(
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(url)
                                                                        .build())
                                                        .build());
                                    }
                                }
                            }

                            if (contentBlocks.isEmpty()) {
                                log.error("No valid image content generated.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            return ToolResultBlock.builder().output(contentBlocks).build();
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to generate images: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to generate images: " + ex.getMessage(), ex);
                        });
    }

    /**
     * Convert image(s) to text using vision models.
     *
     * @param imageUrls the URLs of the images to analyze
     * @param prompt the text prompt describing what to extract from the images
     * @param model the vision model to use (e.g., "gpt-4o", "gpt-4-vision-preview")
     * @param maxTokens the maximum number of tokens in the response
     * @return a ToolResultBlock containing the text description of the images
     */
    @Tool(
            name = "openai_image_to_text",
            description =
                    "Convert image(s) to text using OpenAI vision models. "
                            + "Analyzes images and returns text descriptions.")
    public Mono<ToolResultBlock> openaiImageToText(
            @ToolParam(
                            name = "image_urls",
                            description = "The URLs of the images to analyze (comma-separated)")
                    String imageUrls,
            @ToolParam(
                            name = "prompt",
                            description =
                                    "The text prompt describing what to extract from the images",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The vision model to use, e.g., 'gpt-4o',"
                                            + " 'gpt-4-vision-preview'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "max_tokens",
                            description = "The maximum number of tokens in the response",
                            required = false)
                    Integer maxTokens) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("gpt-4o");
        String finalPrompt =
                Optional.ofNullable(prompt)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("Describe the image(s) in detail.");
        Integer finalMaxTokens = Optional.ofNullable(maxTokens).orElse(300);

        log.debug(
                "openai_image_to_text called: imageUrls='{}', prompt='{}', model='{}',"
                        + " maxTokens='{}'",
                imageUrls,
                finalPrompt,
                finalModel,
                finalMaxTokens);

        return Mono.fromCallable(
                        () -> {
                            // Parse image URLs
                            String[] urls = imageUrls.split(",");
                            List<Map<String, Object>> contentParts = new ArrayList<>();

                            // Add text prompt
                            contentParts.add(Map.of("type", "text", "text", finalPrompt));

                            // Add image URLs
                            for (String url : urls) {
                                String trimmedUrl = url.trim();
                                if (!trimmedUrl.isEmpty()) {
                                    contentParts.add(
                                            Map.of(
                                                    "type",
                                                    "image_url",
                                                    "image_url",
                                                    Map.of("url", trimmedUrl)));
                                }
                            }

                            Map<String, Object> request = new HashMap<>();
                            request.put("model", finalModel);
                            request.put(
                                    "messages",
                                    List.of(Map.of("role", "user", "content", contentParts)));
                            request.put("max_tokens", finalMaxTokens);

                            String responseBody =
                                    client.callApi(
                                            apiKey, baseUrl, "/v1/chat/completions", request);
                            Map<String, Object> response =
                                    JsonUtils.getJsonCodec()
                                            .fromJson(
                                                    responseBody,
                                                    new TypeReference<Map<String, Object>>() {});

                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> choices =
                                    (List<Map<String, Object>>) response.get("choices");
                            if (choices == null || choices.isEmpty()) {
                                log.error("No choices returned from vision model.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            Map<String, Object> firstChoice = choices.get(0);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> message =
                                    (Map<String, Object>) firstChoice.get("message");
                            if (message == null) {
                                log.error("No message in choice.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            String text = (String) message.get("content");
                            if (text == null || text.trim().isEmpty()) {
                                log.error("No content in message.");
                                return ToolResultBlock.error("Failed to analyze images.");
                            }

                            return ToolResultBlock.builder()
                                    .output(List.of(TextBlock.builder().text(text).build()))
                                    .build();
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to analyze images: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to analyze images: " + ex.getMessage(), ex);
                        });
    }

    /**
     * Convert text to audio (speech) using OpenAI TTS models.
     *
     * @param text the text to convert to speech
     * @param model the TTS model to use (e.g., "tts-1", "tts-1-hd")
     * @param voice the voice to use ("alloy", "echo", "fable", "onyx", "nova", "shimmer")
     * @param responseFormat the audio format ("mp3", "opus", "aac", "flac")
     * @param speed the speed of the speech (0.25 to 4.0)
     * @return a ToolResultBlock containing the audio as base64 data
     */
    @Tool(
            name = "openai_text_to_audio",
            description =
                    "Convert text to audio (speech) using OpenAI TTS models. "
                            + "Returns audio as base64 data.")
    public Mono<ToolResultBlock> openaiTextToAudio(
            @ToolParam(name = "text", description = "The text to convert to speech") String text,
            @ToolParam(
                            name = "model",
                            description = "The TTS model to use, e.g., 'tts-1', 'tts-1-hd'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "voice",
                            description =
                                    "The voice to use: 'alloy', 'echo', 'fable', 'onyx', 'nova',"
                                            + " 'shimmer'",
                            required = false)
                    String voice,
            @ToolParam(
                            name = "response_format",
                            description = "The audio format: 'mp3', 'opus', 'aac', 'flac'",
                            required = false)
                    String responseFormat,
            @ToolParam(
                            name = "speed",
                            description = "The speed of the speech (0.25 to 4.0)",
                            required = false)
                    Double speed) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("tts-1");
        String finalVoice =
                Optional.ofNullable(voice).filter(s -> !s.trim().isEmpty()).orElse("alloy");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("mp3");
        Double finalSpeed = Optional.ofNullable(speed).orElse(1.0);

        log.debug(
                "openai_text_to_audio called: text='{}', model='{}', voice='{}',"
                        + " responseFormat='{}', speed='{}'",
                text,
                finalModel,
                finalVoice,
                finalResponseFormat,
                finalSpeed);

        return Mono.fromCallable(
                        () -> {
                            Map<String, Object> request = new HashMap<>();
                            request.put("model", finalModel);
                            request.put("input", text);
                            request.put("voice", finalVoice);
                            request.put("response_format", finalResponseFormat);
                            request.put("speed", finalSpeed);

                            // Note: /v1/audio/speech returns binary audio data, not JSON
                            // We need to handle binary response - for now, return an informative
                            // error
                            // TODO: Extend OpenAIClient to support binary responses for TTS
                            return ToolResultBlock.error(
                                    "Text-to-speech requires binary response handling, which is not"
                                        + " yet fully implemented. Please use the OpenAI SDK"
                                        + " directly for this feature, or extend OpenAIClient to"
                                        + " support binary responses.");
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to generate audio: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to generate audio: " + ex.getMessage(), ex);
                        });
    }

    /**
     * Convert audio to text (transcription) using OpenAI Whisper models.
     *
     * <p>Note: This requires multipart/form-data upload, which is not yet fully supported.
     * This is a placeholder implementation.
     *
     * @param audioUrl the URL of the audio file to transcribe
     * @param model the transcription model to use (e.g., "whisper-1")
     * @param language the language of the audio (ISO-639-1 code, optional)
     * @param prompt optional text to guide the model's style
     * @param responseFormat the format of the response ("json", "text", "verbose_json", etc.)
     * @param temperature the temperature for sampling (0.0 to 1.0)
     * @return a ToolResultBlock containing the transcribed text
     */
    @Tool(
            name = "openai_audio_to_text",
            description =
                    "Convert audio to text (transcription) using OpenAI Whisper models. "
                            + "Requires audio file URL.")
    public Mono<ToolResultBlock> openaiAudioToText(
            @ToolParam(name = "audio_url", description = "The URL of the audio file to transcribe")
                    String audioUrl,
            @ToolParam(
                            name = "model",
                            description = "The transcription model to use, e.g., 'whisper-1'",
                            required = false)
                    String model,
            @ToolParam(
                            name = "language",
                            description = "The language of the audio (ISO-639-1 code, optional)",
                            required = false)
                    String language,
            @ToolParam(
                            name = "prompt",
                            description = "Optional text to guide the model's style",
                            required = false)
                    String prompt,
            @ToolParam(
                            name = "response_format",
                            description =
                                    "The format of the response: 'json', 'text', 'verbose_json',"
                                            + " etc.",
                            required = false)
                    String responseFormat,
            @ToolParam(
                            name = "temperature",
                            description = "The temperature for sampling (0.0 to 1.0)",
                            required = false)
                    Double temperature) {

        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("whisper-1");
        String finalResponseFormat =
                Optional.ofNullable(responseFormat).filter(s -> !s.trim().isEmpty()).orElse("text");

        log.debug(
                "openai_audio_to_text called: audioUrl='{}', model='{}', language='{}',"
                        + " prompt='{}', responseFormat='{}', temperature='{}'",
                audioUrl,
                finalModel,
                language,
                prompt,
                finalResponseFormat,
                temperature);

        return Mono.fromCallable(
                        () -> {
                            // Download audio from URL and convert to base64
                            String audioBase64;
                            String mediaType;
                            try {
                                mediaType = MediaUtils.determineMediaType(audioUrl);
                                audioBase64 = MediaUtils.downloadUrlToBase64(audioUrl);
                            } catch (IOException e) {
                                log.error(
                                        "Failed to download audio from URL: {}", e.getMessage(), e);
                                return ToolResultBlock.error(
                                        "Failed to download audio: " + e.getMessage());
                            }

                            if (audioBase64 == null || audioBase64.trim().isEmpty()) {
                                log.error("Failed to convert audio to base64.");
                                return ToolResultBlock.error("Failed to process audio.");
                            }

                            // TODO: Implement multipart/form-data upload for
                            // /v1/audio/transcriptions
                            // For now, return an error indicating this feature needs multipart
                            // support
                            return ToolResultBlock.error(
                                    "Audio transcription requires multipart/form-data upload, which"
                                        + " is not yet fully implemented. Please use the OpenAI SDK"
                                        + " directly for this feature.");
                        })
                .onErrorMap(
                        ex -> {
                            log.error("Failed to transcribe audio: {}", ex.getMessage(), ex);
                            return new RuntimeException(
                                    "Failed to transcribe audio: " + ex.getMessage(), ex);
                        });
    }
}
