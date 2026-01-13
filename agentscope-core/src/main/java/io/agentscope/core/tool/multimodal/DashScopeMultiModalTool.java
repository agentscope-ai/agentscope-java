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

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisOutput;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;
import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Role;
import io.agentscope.core.Version;
import io.agentscope.core.formatter.MediaUtils;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DashScope multimodal tool.
 * convert text to images, convert images to text, convert text to audio, and convert audio to text.
 * Please refer to the <a href="https://dashscope.aliyun.com/">`dashscope documentation`</a> for more details.
 */
public class DashScopeMultiModalTool {

    private static final Logger log = LoggerFactory.getLogger(DashScopeMultiModalTool.class);

    /**
     * DashScope API key.
     */
    private final String apiKey;

    public DashScopeMultiModalTool(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("DashScope API key cannot be empty.");
        }
        this.apiKey = apiKey;
    }

    /**
     * Generate image(s) based on the given prompt, and return image url(s) or base64 data.
     *
     * @param prompt    The text prompt to generate image
     * @param model     The model to use, e.g., 'wanx-v1', 'qwen-image', 'wan2.2-t2i-flash', etc.
     * @param n         The number of images to generate
     * @param size      Size of the image, e.g., '1024*1024', '1280*1280', '800*1200', etc.
     * @param useBase64 Whether to use base64 data for images
     * @return A ToolResultBlock containing the generated image url, base64 data, or error message.
     */
    @Tool(
            name = "dashscope_text_to_image",
            description =
                    "Generate image(s) based on the given prompt, and return image url(s) or base64"
                            + " data.")
    public Mono<ToolResultBlock> dashscopeTextToImage(
            @ToolParam(name = "prompt", description = "The text prompt to generate image")
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use, e.g., 'wanx-v1', 'qwen-image',"
                                            + " 'wan2.2-t2i-flash', etc.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "n",
                            description = "The number of images to generate",
                            required = false)
                    Integer n,
            @ToolParam(
                            name = "size",
                            description =
                                    "Size of the image, e.g., '1024*1024', '1280*1280', '800*1200',"
                                            + " etc.",
                            required = false)
                    String size,
            @ToolParam(
                            name = "use_base64",
                            description = "Whether to use base64 data for images",
                            required = false)
                    Boolean useBase64) {

        Integer finalN = Optional.ofNullable(n).orElse(1);
        String finalSize =
                Optional.ofNullable(size).filter(s -> !s.trim().isEmpty()).orElse("1024*1024");
        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("wanx-v1");
        Boolean finalUseBase64 = Optional.ofNullable(useBase64).orElse(false);

        log.debug(
                "dashscope_text_to_image called: prompt='{}', n='{}', size='{}', model='{}',"
                        + " useBase64='{}'",
                prompt,
                finalN,
                finalSize,
                finalModel,
                useBase64);

        return Mono.fromCallable(
                        () -> {
                            ImageSynthesisParam param =
                                    ImageSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .prompt(prompt)
                                            .model(finalModel)
                                            .n(finalN)
                                            .size(finalSize)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            ImageSynthesis imageSynthesis = new ImageSynthesis();
                            ImageSynthesisResult response = imageSynthesis.call(param);

                            List<String> urls =
                                    Optional.ofNullable(response)
                                            .map(ImageSynthesisResult::getOutput)
                                            .map(ImageSynthesisOutput::getResults)
                                            .map(
                                                    results ->
                                                            results.stream()
                                                                    .map(item -> item.get("url"))
                                                                    .toList())
                                            .orElse(null);

                            if (urls == null || urls.isEmpty()) {
                                log.error("No image url returned.");
                                return ToolResultBlock.error("Failed to generate images.");
                            }

                            List<ContentBlock> contentBlocks = new ArrayList<>();
                            for (String url : urls) {
                                if (finalUseBase64) {
                                    String mediaType;
                                    String data;
                                    try {
                                        mediaType = MediaUtils.determineMediaType(url);
                                        data = MediaUtils.downloadUrlToBase64(url);
                                    } catch (IOException e) {
                                        log.error("Failed to generate images.");
                                        return ToolResultBlock.error(e.getMessage());
                                    }
                                    if (data == null || data.trim().isEmpty()) {
                                        log.error("Failed to convert image url to base64.");
                                        return ToolResultBlock.error("Failed to generate images.");
                                    }

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .mediaType(mediaType)
                                                                    .data(data)
                                                                    .build())
                                                    .build());
                                } else {

                                    contentBlocks.add(
                                            ImageBlock.builder()
                                                    .source(URLSource.builder().url(url).build())
                                                    .build());
                                }
                            }
                            return ToolResultBlock.of(contentBlocks);
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate images '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Generate text based on the given images.
     *
     * @param imageUrls The URL(s) of image(s) to be converted into text.
     * @param prompt    The text prompt.
     * @param model     The model to use, e.g., 'qwen3-vl-plus', qwen-vl-plus' 'qvq-plus', etc.
     * @return A ToolResultBlock containing the generated text or error message.
     */
    @Tool(
            name = "dashscope_image_to_text",
            description = "Generate text based on the given images.")
    public Mono<ToolResultBlock> dashscopeImageToText(
            @ToolParam(
                            name = "image_urls",
                            description = "The URL(s) of image(s) to be converted into text.")
                    List<String> imageUrls,
            @ToolParam(name = "prompt", description = "The text prompt.", required = false)
                    String prompt,
            @ToolParam(
                            name = "model",
                            description =
                                    "The model to use, e.g., 'qwen3-vl-plus', qwen-vl-plus'"
                                            + " 'qwen-vl-max', etc.",
                            required = false)
                    String model) {

        String finalPrompt = Optional.ofNullable(prompt).orElse("Describe the image");
        String finalModel =
                Optional.ofNullable(model).filter(s -> !s.trim().isEmpty()).orElse("qwen3-vl-plus");
        log.debug("dashscope_image_to_text called: prompt='{}', model='{}'", prompt, finalModel);

        return Mono.fromCallable(
                        () -> {
                            List<MultiModalMessage> multiModalMessages = new ArrayList<>();
                            MultiModalMessage systemMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.SYSTEM.getValue())
                                            .content(
                                                    List.of(
                                                            Map.of(
                                                                    "text",
                                                                    "You are a helpful"
                                                                            + " assistant.")))
                                            .build();

                            List<Map<String, Object>> content = new ArrayList<>();

                            for (String url : imageUrls) {
                                try {
                                    content.add(Map.of("image", MediaUtils.urlToProtocolUrl(url)));
                                } catch (IOException e) {
                                    return ToolResultBlock.error(e.getMessage());
                                }
                            }

                            content.add(Map.of("text", finalPrompt));
                            MultiModalMessage userMessage =
                                    MultiModalMessage.builder()
                                            .role(Role.USER.getValue())
                                            .content(content)
                                            .build();

                            multiModalMessages.add(systemMessage);
                            multiModalMessages.add(userMessage);

                            MultiModalConversationParam param =
                                    MultiModalConversationParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .messages(multiModalMessages)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();

                            MultiModalConversation conv = new MultiModalConversation();
                            MultiModalConversationResult result = conv.call(param);

                            String text =
                                    Optional.ofNullable(result)
                                            .map(MultiModalConversationResult::getOutput)
                                            .map(MultiModalConversationOutput::getChoices)
                                            .flatMap(choices -> choices.stream().findFirst())
                                            .map(MultiModalConversationOutput.Choice::getMessage)
                                            .map(MultiModalMessage::getContent)
                                            .flatMap(contents -> contents.stream().findFirst())
                                            .map(contentMap -> contentMap.get("text"))
                                            .map(Object::toString)
                                            .orElse(null);

                            if (text == null) {
                                log.error("MultiModalConversation response text is empty.");
                                return ToolResultBlock.error("Failed to generate text.");
                            }

                            return ToolResultBlock.of(TextBlock.builder().text(text).build());
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to generate text '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Convert the given text to audio.
     *
     * <p>Supports two types of TTS models:
     * <ul>
     *   <li>Qwen TTS models (qwen3-tts-flash, qwen-tts) - uses multimodal-generation API</li>
     *   <li>Sambert models (sambert-*) - uses speech synthesis SDK</li>
     * </ul>
     *
     * @param text       The text to be converted into audio.
     * @param model      The TTS model to use. For Qwen TTS: 'qwen3-tts-flash', 'qwen-tts'.
     *                   For Sambert: 'sambert-zhinan-v1', 'sambert-zhiqi-v1', 'sambert-zhichu-v1', etc.
     * @param voice      Voice name for Qwen TTS models, e.g., 'Cherry', 'Serena'. Ignored for Sambert models.
     * @param language   Language type for Qwen TTS, e.g., 'Chinese', 'English'. Ignored for Sambert models.
     * @param sampleRate Sample rate of the audio (e.g., 16000, 24000, 48000).
     * @return A ToolResultBlock containing the base64 data of audio or error message.
     */
    @Tool(name = "dashscope_text_to_audio", description = "Convert the given text to audio.")
    public Mono<ToolResultBlock> dashscopeTextToAudio(
            @ToolParam(name = "text", description = "The text to be converted into audio.")
                    String text,
            @ToolParam(
                            name = "model",
                            description =
                                    "The TTS model to use. For Qwen TTS: 'qwen3-tts-flash',"
                                            + " 'qwen-tts'. For Sambert: 'sambert-zhinan-v1',"
                                            + " 'sambert-zhiqi-v1', 'sambert-zhichu-v1', etc.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "voice",
                            description =
                                    "Voice name for Qwen TTS models, e.g., 'Cherry', 'Serena'."
                                            + " Ignored for Sambert models.",
                            required = false)
                    String voice,
            @ToolParam(
                            name = "language",
                            description =
                                    "Language type for Qwen TTS, e.g., 'Chinese', 'English'."
                                            + " Ignored for Sambert models.",
                            required = false)
                    String language,
            @ToolParam(
                            name = "sample_rate",
                            description = "Sample rate of the audio (e.g., 16000, 24000, 48000).",
                            required = false)
                    Integer sampleRate) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("qwen3-tts-flash");
        Integer finalSampleRate = Optional.ofNullable(sampleRate).orElse(24000);

        // Check if it's a Qwen TTS model
        boolean isQwenTTS = finalModel.startsWith("qwen3-tts") || finalModel.startsWith("qwen-tts");

        log.debug(
                "dashscope_text_to_audio called: text='{}', model='{}', voice='{}', language='{}',"
                        + " sampleRate='{}', isQwenTTS='{}'",
                text,
                finalModel,
                voice,
                language,
                finalSampleRate,
                isQwenTTS);

        if (isQwenTTS) {
            return synthesizeWithQwenTTS(text, finalModel, voice, language);
        } else {
            return synthesizeWithSambert(text, finalModel, finalSampleRate);
        }
    }

    /**
     * Synthesizes audio using Qwen TTS models via the multimodal-generation API.
     *
     * <p>This method handles the HTTP communication with DashScope's Qwen TTS endpoint,
     * building the request payload with text, voice, and language parameters, then
     * parsing the response to extract audio data.
     *
     * <p>The method uses the multimodal-generation API endpoint which differs from
     * the standard speech synthesis endpoint used by other models.
     *
     * @param text the text to synthesize into speech
     * @param model the Qwen TTS model name (e.g., "qwen3-tts-flash", "qwen-tts")
     * @param voice the voice name for synthesis, defaults to "Cherry" if null/empty
     * @param language the language type, defaults to "Chinese" if null/empty
     * @return a Mono containing ToolResultBlock with AudioBlock on success,
     *         or an error ToolResultBlock on failure
     */
    private Mono<ToolResultBlock> synthesizeWithQwenTTS(
            String text, String model, String voice, String language) {
        String finalVoice =
                Optional.ofNullable(voice).filter(s -> !s.trim().isEmpty()).orElse("Cherry");
        String finalLanguage =
                Optional.ofNullable(language).filter(s -> !s.trim().isEmpty()).orElse("Chinese");

        return Mono.fromCallable(
                        () -> {
                            // Build request for Qwen TTS API
                            Map<String, Object> input = new java.util.HashMap<>();
                            input.put("text", text);
                            input.put("voice", finalVoice);
                            input.put("language_type", finalLanguage);

                            Map<String, Object> request = new java.util.HashMap<>();
                            request.put("model", model);
                            request.put("input", input);

                            String requestBody =
                                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                                            .toJson(request);

                            // Call DashScope API using Java HttpClient
                            java.net.http.HttpClient client =
                                    java.net.http.HttpClient.newHttpClient();
                            java.net.http.HttpRequest httpRequest =
                                    java.net.http.HttpRequest.newBuilder()
                                            .uri(
                                                    URI.create(
                                                            "https://dashscope.aliyuncs.com/api/v1/services"
                                                                + "/aigc/multimodal-generation/generation"))
                                            .header("Authorization", "Bearer " + this.apiKey)
                                            .header("Content-Type", "application/json")
                                            .header("User-Agent", Version.getUserAgent())
                                            .POST(
                                                    java.net.http.HttpRequest.BodyPublishers
                                                            .ofString(requestBody))
                                            .build();

                            java.net.http.HttpResponse<String> response =
                                    client.send(
                                            httpRequest,
                                            java.net.http.HttpResponse.BodyHandlers.ofString());

                            if (response.statusCode() != 200) {
                                log.error(
                                        "Qwen TTS API failed: status={}, body={}",
                                        response.statusCode(),
                                        response.body());
                                return ToolResultBlock.error(
                                        "TTS API failed: " + response.statusCode());
                            }

                            return parseQwenTTSResponse(response.body());
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Failed to generate audio with Qwen TTS: '{}'",
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Parses the Qwen TTS API response and extracts audio data.
     *
     * <p>The response structure from Qwen TTS API is:
     * <pre>{@code
     * {
     *   "request_id": "...",
     *   "output": {
     *     "audio": {
     *       "url": "https://..."  // or "data": "base64..."
     *     }
     *   }
     * }
     * }</pre>
     *
     * <p>The method handles two audio formats:
     * <ul>
     *   <li>URL-based: returns AudioBlock with URLSource</li>
     *   <li>Base64-encoded: returns AudioBlock with Base64Source</li>
     * </ul>
     *
     * @param responseBody the raw JSON response body from the API
     * @return ToolResultBlock containing AudioBlock on success,
     *         or an error ToolResultBlock if parsing fails or response contains an error
     */
    private ToolResultBlock parseQwenTTSResponse(String responseBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response =
                    io.agentscope.core.util.JsonUtils.getJsonCodec()
                            .fromJson(responseBody, Map.class);

            // Check for error
            if (response.containsKey("code") && response.get("code") != null) {
                String message =
                        response.containsKey("message")
                                ? response.get("message").toString()
                                : "Unknown error";
                log.error("Qwen TTS error: {}", message);
                return ToolResultBlock.error(message);
            }

            // Extract audio from output
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) response.get("output");
            if (output == null) {
                return ToolResultBlock.error("No output in response");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> audio = (Map<String, Object>) output.get("audio");
            if (audio == null) {
                return ToolResultBlock.error("No audio in response");
            }

            // Check for URL or base64 data
            if (audio.containsKey("url") && audio.get("url") != null) {
                String url = audio.get("url").toString();
                return ToolResultBlock.of(
                        AudioBlock.builder().source(URLSource.builder().url(url).build()).build());
            } else if (audio.containsKey("data") && audio.get("data") != null) {
                String data = audio.get("data").toString();
                return ToolResultBlock.of(
                        AudioBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .mediaType("audio/wav")
                                                .data(data)
                                                .build())
                                .build());
            } else {
                return ToolResultBlock.error("No audio data in response");
            }
        } catch (Exception e) {
            log.error("Failed to parse Qwen TTS response: {}", e.getMessage());
            return ToolResultBlock.error("Failed to parse response: " + e.getMessage());
        }
    }

    /**
     * Synthesize audio using Sambert models via speech synthesis SDK.
     */
    private Mono<ToolResultBlock> synthesizeWithSambert(
            String text, String model, Integer sampleRate) {
        return Mono.fromCallable(
                        () -> {
                            SpeechSynthesisParam param =
                                    SpeechSynthesisParam.builder()
                                            .apiKey(this.apiKey)
                                            .text(text)
                                            .model(model)
                                            .sampleRate(sampleRate)
                                            .format(SpeechSynthesisAudioFormat.WAV)
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            SpeechSynthesizer synthesizer = new SpeechSynthesizer();
                            ByteBuffer byteBuffer = synthesizer.call(param);
                            if (byteBuffer == null || byteBuffer.remaining() == 0) {
                                log.error("Audio byte buffer is empty.");
                                return ToolResultBlock.error("Failed to generate audio.");
                            }
                            byte[] bytes = new byte[byteBuffer.remaining()];
                            byteBuffer.get(bytes);
                            String data = Base64.getEncoder().encodeToString(bytes);

                            return ToolResultBlock.of(
                                    AudioBlock.builder()
                                            .source(
                                                    Base64Source.builder()
                                                            .mediaType("audio/wav")
                                                            .data(data)
                                                            .build())
                                            .build());
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "Failed to generate audio with Sambert: '{}'",
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Convert the given audio to text.
     *
     * @param audioUrl   The file path or URL of audio to be converted into text.
     * @param model      The speech recognition model to use, e.g., 'paraformer-realtime-v1', 'paraformer-realtime-8k-v1',
     *                  'paraformer-realtime-v2', 'paraformer-realtime-8k-v2'.
     * @param sampleRate Sample rate of the audio (e.g., 8000, 16000).
     * @return A ToolResultBlock containing the recognition text or error message.
     */
    @Tool(name = "dashscope_audio_to_text", description = "Convert the given audio to text.")
    public Mono<ToolResultBlock> dashscopeAudioToText(
            @ToolParam(
                            name = "audio_url",
                            description =
                                    "The file path or URL of audio to be converted into text.")
                    String audioUrl,
            @ToolParam(
                            name = "model",
                            description =
                                    "The TTS model to use, e.g., 'paraformer-realtime-v1',"
                                        + " 'paraformer-realtime-8k-v1', 'paraformer-realtime-v2',"
                                        + " 'paraformer-realtime-8k-v2'.",
                            required = false)
                    String model,
            @ToolParam(
                            name = "sample_rate",
                            description = "Sample rate of the audio (e.g., 8000, 16000).",
                            required = false)
                    Integer sampleRate) {

        String finalModel =
                Optional.ofNullable(model)
                        .filter(s -> !s.trim().isEmpty())
                        .orElse("paraformer-realtime-v2");
        Integer finalSampleRate = Optional.ofNullable(sampleRate).orElse(16000);
        log.debug(
                "dashscope_audio_to_text called: audioUrl='{}', model='{}', sampleRate='{}'",
                audioUrl,
                finalModel,
                finalSampleRate);

        return Mono.fromCallable(
                        () -> {
                            AtomicReference<TextBlock> textBlockRef = new AtomicReference<>(null);
                            CountDownLatch stopLatch = new CountDownLatch(1);

                            RecognitionParam param =
                                    RecognitionParam.builder()
                                            .apiKey(this.apiKey)
                                            .model(finalModel)
                                            .format(MediaUtils.getExtension(audioUrl))
                                            .sampleRate(finalSampleRate)
                                            // "language_hints" only support for
                                            // paraformer-realtime-v2 model
                                            .parameter("language_hints", new String[] {"zh", "en"})
                                            .header("user-agent", Version.getUserAgent())
                                            .build();
                            Recognition recognizer = new Recognition();
                            ResultCallback<RecognitionResult> callback =
                                    new ResultCallback<>() {
                                        @Override
                                        public void onEvent(RecognitionResult message) {
                                            String text = message.getSentence().getText();
                                            if (message.isSentenceEnd()) {
                                                log.debug("Final Result: " + text);
                                                textBlockRef.set(
                                                        TextBlock.builder().text(text).build());
                                            } else {
                                                log.debug("Intermediate Result: " + text);
                                            }
                                        }

                                        @Override
                                        public void onComplete() {
                                            stopLatch.countDown();
                                            log.debug("Recognition complete");
                                        }

                                        @Override
                                        public void onError(Exception e) {
                                            stopLatch.countDown();
                                            log.error(
                                                    "RecognitionCallback error: " + e.getMessage());
                                        }
                                    };

                            try {
                                recognizer.call(param, callback);

                                this.sendAudioChunk(audioUrl, recognizer);

                                recognizer.stop();

                                if (!stopLatch.await(60, TimeUnit.SECONDS)) {
                                    log.error("Timeout waiting for recognition to complete.");
                                    return ToolResultBlock.error("Failed to transcribe audio.");
                                }

                                TextBlock textBlock = textBlockRef.get();
                                if (textBlock == null) {
                                    log.error("Generate text is empty.");
                                    return ToolResultBlock.error("Failed to transcribe audio.");
                                }
                                return ToolResultBlock.of(textBlock);
                            } finally {
                                // Close Websocket until task is finished
                                log.debug("Finish transcribe audio.");
                                recognizer.getDuplexApi().close(1000, "bye");
                            }
                        })
                .onErrorResume(
                        e -> {
                            log.error("Failed to transcribe audio, '{}'", e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error(e.getMessage()));
                        });
    }

    /**
     * Send audio input stream by chunk to DashScope.
     * marked as public for unit test.
     *
     * @param audioUrl   The file path or URL of audio.
     * @param recognizer DashScope Recognition instance
     * @throws IOException          if read failed
     * @throws InterruptedException if interrupted
     */
    public void sendAudioChunk(String audioUrl, Recognition recognizer)
            throws IOException, InterruptedException {
        // chunk size set to 1 seconds for 16KHz sample rate
        byte[] buffer = new byte[3200];
        int bytesRead;
        if (MediaUtils.isFileExists(audioUrl)) {
            try ( // Read file and send audio by chunks
            InputStream is = Files.newInputStream(Paths.get(audioUrl))) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    recognizer.sendAudioFrame(byteBuffer);

                    // Prevent high CPU usage
                    Thread.sleep(100);
                }
            }
        } else {
            try ( // Read web url and send audio by chunks
            InputStream is = URI.create(audioUrl).toURL().openStream()) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead);
                    recognizer.sendAudioFrame(byteBuffer);

                    // Prevent high CPU usage
                    Thread.sleep(100);
                }
            }
        }
    }
}
