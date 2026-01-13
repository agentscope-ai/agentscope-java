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
package io.agentscope.core.model.tts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * DashScope Realtime TTS Model with streaming input support.
 *
 * <p>This model supports both streaming output (SSE) and streaming input,
 * allowing text to be pushed incrementally while receiving audio chunks in real-time.
 * This enables "speak as you generate" scenarios.
 *
 * <p>Based on Python agentscope's DashScopeRealtimeTTSModel:
 * <a href="https://github.com/agentscope-ai/agentscope/blob/main/examples/functionality/tts/main.py">
 * agentscope TTS example</a>
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>{@code supportsStreamingInput = true} - Can receive text incrementally</li>
 *   <li>{@code push(text)} - Push text chunk, get available audio (non-blocking)</li>
 *   <li>{@code finish()} - Signal end of input, get remaining audio</li>
 *   <li>Automatic batching of small text chunks for efficiency</li>
 * </ul>
 *
 * <p><b>Usage Example (Streaming Input):</b>
 * <pre>{@code
 * DashScopeRealtimeTTSModel tts = DashScopeRealtimeTTSModel.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen3-tts-flash")
 *     .voice("Cherry")
 *     .build();
 *
 * // Start a streaming session
 * tts.startSession();
 *
 * // Push text chunks as LLM generates them
 * tts.push("你好，").subscribe(audio -> player.play(audio));
 * tts.push("欢迎使用").subscribe(audio -> player.play(audio));
 * tts.push("语音合成。").subscribe(audio -> player.play(audio));
 *
 * // Finish and get remaining audio
 * tts.finish().subscribe(audio -> player.play(audio));
 * }</pre>
 */
public class DashScopeRealtimeTTSModel implements TTSModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeRealtimeTTSModel.class);

    private static final String API_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    // Minimum characters before sending to TTS (for batching efficiency)
    private static final int MIN_BATCH_SIZE = 5;

    private final String apiKey;
    private final String modelName;
    private final String voice;
    private final int sampleRate;
    private final String format;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Streaming input support
    private final boolean supportsStreamingInput = true;
    private final StringBuilder textBuffer = new StringBuilder();
    private final BlockingQueue<AudioBlock> audioQueue = new LinkedBlockingQueue<>();
    private final Sinks.Many<AudioBlock> audioSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private Thread synthesisThread;

    private DashScopeRealtimeTTSModel(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.voice = builder.voice;
        this.sampleRate = builder.sampleRate;
        this.format = builder.format;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Returns true if this model supports streaming input (push/finish pattern).
     */
    public boolean supportsStreamingInput() {
        return supportsStreamingInput;
    }

    /**
     * Starts a new TTS session for streaming input.
     *
     * <p>Call this before using push()/finish() pattern.
     */
    public void startSession() {
        if (sessionActive.compareAndSet(false, true)) {
            textBuffer.setLength(0);
            audioQueue.clear();
            log.debug("TTS session started");
        }
    }

    /**
     * Pushes a text chunk for synthesis.
     *
     * <p>This is non-blocking and may not immediately produce audio.
     * Audio chunks are emitted through the returned Flux as they become available.
     *
     * <p>Text chunks are batched for efficiency - small chunks are accumulated
     * until they reach a minimum size before synthesis.
     *
     * @param text the text chunk to synthesize
     * @return Flux of AudioBlock containing any available audio (may be empty)
     */
    public Flux<AudioBlock> push(String text) {
        if (!sessionActive.get()) {
            startSession();
        }

        if (text == null || text.isEmpty()) {
            return Flux.empty();
        }

        synchronized (textBuffer) {
            textBuffer.append(text);

            // Check if we have enough text to synthesize
            if (textBuffer.length() >= MIN_BATCH_SIZE || containsSentenceEnd(text)) {
                String toSynthesize = textBuffer.toString();
                textBuffer.setLength(0);

                // Synthesize in background and return available audio
                return synthesizeAsync(toSynthesize);
            }
        }

        // Not enough text yet, return empty
        return Flux.empty();
    }

    /**
     * Finishes the streaming session and retrieves remaining audio.
     *
     * <p>Call this after pushing all text chunks to flush any buffered text
     * and get the final audio.
     *
     * @return Flux of remaining AudioBlock chunks
     */
    public Flux<AudioBlock> finish() {
        if (!sessionActive.get()) {
            return Flux.empty();
        }

        String remainingText;
        synchronized (textBuffer) {
            remainingText = textBuffer.toString();
            textBuffer.setLength(0);
        }

        sessionActive.set(false);
        log.debug("TTS session finished");

        if (remainingText.isEmpty()) {
            return Flux.empty();
        }

        // Synthesize remaining text
        return synthesizeStream(remainingText);
    }

    /**
     * Gets the audio stream for listening to all audio chunks.
     *
     * @return Flux of AudioBlock that emits audio as it's received
     */
    public Flux<AudioBlock> getAudioStream() {
        return audioSink.asFlux();
    }

    /**
     * Synthesizes text to audio with streaming output (SSE).
     *
     * @param text the complete text to synthesize
     * @return Flux of AudioBlock chunks as they are generated
     */
    public Flux<AudioBlock> synthesizeStream(String text) {
        return Flux.create(
                sink -> {
                    try {
                        String requestBody = buildRequestBody(text);

                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(URI.create(API_ENDPOINT))
                                        .header("Authorization", "Bearer " + apiKey)
                                        .header("Content-Type", "application/json")
                                        .header("X-DashScope-SSE", "enable")
                                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                                        .build();

                        HttpResponse<java.io.InputStream> response =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                        if (response.statusCode() != 200) {
                            sink.error(new TTSException("HTTP error: " + response.statusCode()));
                            return;
                        }

                        // Process SSE stream
                        try (BufferedReader reader =
                                new BufferedReader(new InputStreamReader(response.body()))) {
                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if (!data.isEmpty()) {
                                        AudioBlock audio = processSSEData(data);
                                        if (audio != null) {
                                            sink.next(audio);
                                            audioSink.tryEmitNext(audio);
                                        }
                                    }
                                }
                            }
                        }

                        sink.complete();

                    } catch (Exception e) {
                        log.error("SSE stream error: {}", e.getMessage());
                        sink.error(e);
                    }
                });
    }

    /**
     * Synthesizes text asynchronously and returns available audio.
     */
    private Flux<AudioBlock> synthesizeAsync(String text) {
        return synthesizeStream(text)
                .doOnNext(
                        audio -> {
                            audioQueue.offer(audio);
                        });
    }

    private AudioBlock processSSEData(String jsonData) {
        try {
            JsonNode root = objectMapper.readTree(jsonData);

            // Check for errors
            if (root.has("code") && !root.get("code").isNull()) {
                String code = root.get("code").asText();
                if (!code.isEmpty()) {
                    log.warn(
                            "TTS API error: {}",
                            root.has("message") ? root.get("message").asText() : code);
                    return null;
                }
            }

            // Extract audio from output
            JsonNode output = root.get("output");
            if (output != null) {
                JsonNode audio = output.get("audio");
                if (audio != null) {
                    // Check for base64 data
                    if (audio.has("data") && !audio.get("data").isNull()) {
                        String base64Data = audio.get("data").asText();
                        if (base64Data != null && !base64Data.isEmpty()) {
                            return AudioBlock.builder()
                                    .source(
                                            Base64Source.builder()
                                                    .mediaType("audio/" + format)
                                                    .data(base64Data)
                                                    .build())
                                    .build();
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse SSE data: {}", e.getMessage());
        }
        return null;
    }

    private String buildRequestBody(String text) throws JsonProcessingException {
        Map<String, Object> request = new HashMap<>();
        request.put("model", modelName);
        request.put("stream", true);

        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        request.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("sample_rate", sampleRate);
        parameters.put("format", format);
        request.put("parameters", parameters);

        return objectMapper.writeValueAsString(request);
    }

    private boolean containsSentenceEnd(String text) {
        return text.contains("。")
                || text.contains("！")
                || text.contains("？")
                || text.contains(".")
                || text.contains("!")
                || text.contains("?")
                || text.contains("，")
                || text.contains(",")
                || text.contains("\n");
    }

    @Override
    public Mono<TTSResponse> synthesize(String text, TTSOptions options) {
        return Mono.fromCallable(
                () -> {
                    ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();

                    synthesizeStream(text)
                            .doOnNext(
                                    audioBlock -> {
                                        if (audioBlock.getSource() instanceof Base64Source src) {
                                            if (src.getData() != null) {
                                                byte[] data =
                                                        Base64.getDecoder().decode(src.getData());
                                                audioBuffer.write(data, 0, data.length);
                                            }
                                        }
                                    })
                            .blockLast();

                    return TTSResponse.builder()
                            .audioData(audioBuffer.toByteArray())
                            .format(format)
                            .sampleRate(sampleRate)
                            .build();
                });
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Creates a new builder for DashScopeRealtimeTTSModel.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DashScopeRealtimeTTSModel instances.
     */
    public static class Builder {
        private String apiKey;
        private String modelName = "qwen3-tts-flash";
        private String voice = "Cherry";
        private int sampleRate = 24000;
        private String format = "wav";

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder format(String format) {
            this.format = format;
            return this;
        }

        public DashScopeRealtimeTTSModel build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new DashScopeRealtimeTTSModel(this);
        }
    }
}
