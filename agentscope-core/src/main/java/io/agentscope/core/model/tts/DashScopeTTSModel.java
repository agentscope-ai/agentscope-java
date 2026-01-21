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

import io.agentscope.core.Version;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportException;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DashScope TTS Model implementation.
 *
 * <p>This implementation uses direct HTTP calls to DashScope's TTS API,
 * supporting multiple TTS models including:
 * <ul>
 *   <li>qwen3-tts-flash - Qwen3 TTS fast model</li>
 *   <li>qwen-tts - Qwen TTS model</li>
 *   <li>sambert-* - Sambert series (e.g., sambert-zhimao-v1)</li>
 *   <li>cosyvoice-* - CosyVoice series</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * DashScopeTTSModel tts = DashScopeTTSModel.builder()
 *     .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *     .modelName("qwen3-tts-flash")
 *     .voice("Cherry")
 *     .build();
 *
 * TTSResponse response = tts.synthesize("你好，世界！", null).block();
 * byte[] audioData = response.getAudioData();
 * }</pre>
 *
 * @see <a href="https://help.aliyun.com/document_detail/2950054.html">DashScope TTS API Documentation</a>
 */
public class DashScopeTTSModel implements TTSModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeTTSModel.class);

    /** Default base URL for DashScope API. */
    public static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";

    /** TTS API endpoint (uses multimodal-generation for qwen-tts models). */
    public static final String TTS_ENDPOINT =
            "/api/v1/services/aigc/multimodal-generation/generation";

    private final String apiKey;
    private final String modelName;
    private final String voice;
    private final TTSOptions defaultOptions;
    private final String baseUrl;
    private final HttpTransport transport;

    private DashScopeTTSModel(Builder builder) {
        this.apiKey = builder.apiKey;
        this.modelName = builder.modelName;
        this.voice = builder.voice;
        this.defaultOptions = builder.defaultOptions;
        this.baseUrl = builder.baseUrl != null ? builder.baseUrl : DEFAULT_BASE_URL;
        this.transport =
                builder.transport != null ? builder.transport : HttpTransportFactory.getDefault();
    }

    /**
     * Synthesizes speech from text using DashScope TTS API.
     *
     * @param text the text to convert to speech
     * @param options optional TTS configuration, uses defaults if null
     * @return a Mono containing the TTS response with audio data
     */
    @Override
    public Mono<TTSResponse> synthesize(String text, TTSOptions options) {
        return Mono.fromCallable(() -> doSynthesize(text, options));
    }

    /**
     * Performs the actual TTS synthesis by calling the DashScope API.
     *
     * <p>This method builds the HTTP request, sends it to DashScope,
     * and parses the response. If the API returns a URL instead of
     * inline audio data, the audio is automatically downloaded.
     *
     * @param text the text to synthesize
     * @param options optional TTS configuration
     * @return TTSResponse containing audio data and metadata
     * @throws TTSException if the API call fails or response parsing fails
     */
    private TTSResponse doSynthesize(String text, TTSOptions options) {
        TTSOptions effectiveOptions = options != null ? options : defaultOptions;
        String effectiveVoice =
                effectiveOptions != null && effectiveOptions.getVoice() != null
                        ? effectiveOptions.getVoice()
                        : voice;

        log.debug(
                "TTS synthesize - model: {}, voice: {}, text length: {}",
                modelName,
                effectiveVoice,
                text.length());

        try {
            String requestBody = buildRequestBody(text, effectiveVoice, effectiveOptions);
            log.debug("DashScope TTS request body: {}", requestBody);

            HttpRequest request =
                    HttpRequest.builder()
                            .url(baseUrl + TTS_ENDPOINT)
                            .method("POST")
                            .headers(buildHeaders())
                            .body(requestBody)
                            .build();

            log.debug("Sending TTS request to: {}", request.getUrl());
            HttpResponse response = transport.execute(request);
            log.debug("TTS response status: {}", response.getStatusCode());

            if (!response.isSuccessful()) {
                log.error(
                        "TTS request failed - status: {}, body: {}",
                        response.getStatusCode(),
                        response.getBody());
                throw new TTSException(
                        "TTS request failed with status " + response.getStatusCode(),
                        response.getStatusCode(),
                        response.getBody());
            }

            return parseResponse(response);

        } catch (JsonException e) {
            log.error("Failed to build TTS request: {}", e.getMessage());
            throw new TTSException("Failed to build TTS request", e);
        } catch (HttpTransportException e) {
            log.error("HTTP transport error: {}", e.getMessage());
            throw new TTSException("HTTP transport error: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the request body for DashScope TTS API.
     *
     * <p>Request format (per official API docs):
     * <pre>{@code
     * {
     *   "model": "qwen3-tts-flash",
     *   "input": {
     *     "text": "要合成的文本",
     *     "voice": "Cherry",
     *     "language_type": "Chinese"
     *   },
     *   "parameters": {
     *     "sample_rate": 24000,
     *     "format": "wav",
     *     "rate": 1.0,
     *     "volume": 50,
     *     "pitch": 1.0
     *   }
     * }
     * }</pre>
     */
    private String buildRequestBody(String text, String voiceName, TTSOptions options) {
        DashScopeTTSRequest.TTSInput.Builder inputBuilder =
                DashScopeTTSRequest.TTSInput.builder().text(text);
        if (voiceName != null) {
            inputBuilder.voice(voiceName);
        }
        if (options != null && options.getLanguage() != null) {
            inputBuilder.languageType(options.getLanguage());
        }

        DashScopeTTSRequest.Builder requestBuilder =
                DashScopeTTSRequest.builder().model(modelName).input(inputBuilder.build());

        if (options != null) {
            DashScopeTTSRequest.TTSParameters.Builder paramsBuilder =
                    DashScopeTTSRequest.TTSParameters.builder();
            if (options.getSampleRate() != null) {
                paramsBuilder.sampleRate(options.getSampleRate());
            }
            if (options.getFormat() != null) {
                paramsBuilder.format(options.getFormat());
            }
            if (options.getSpeed() != null) {
                paramsBuilder.rate(options.getSpeed().doubleValue());
            }
            if (options.getVolume() != null) {
                paramsBuilder.volume(options.getVolume().intValue());
            }
            if (options.getPitch() != null) {
                paramsBuilder.pitch(options.getPitch().doubleValue());
            }
            DashScopeTTSRequest.TTSParameters params = paramsBuilder.build();
            // Only add parameters if at least one is set
            if (params.getSampleRate() != null
                    || params.getFormat() != null
                    || params.getRate() != null
                    || params.getVolume() != null
                    || params.getPitch() != null) {
                requestBuilder.parameters(params);
            }
        }

        DashScopeTTSRequest request = requestBuilder.build();
        return JsonUtils.getJsonCodec().toJson(request);
    }

    /**
     * Builds HTTP headers for the DashScope API request.
     *
     * <p>Includes Authorization header with Bearer token, Content-Type,
     * and User-Agent for request tracking.
     *
     * @return map of header names to values
     */
    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", Version.getUserAgent());
        return headers;
    }

    /**
     * Parses the TTS response.
     *
     * <p>DashScope TTS API response format:
     * <pre>{@code
     * {
     *   "request_id": "xxx",
     *   "output": {
     *     "audio": {
     *       "url": "https://...",
     *       "data": "base64_encoded_audio"  // or raw bytes
     *     }
     *   },
     *   "usage": {...}
     * }
     * }</pre>
     */
    private TTSResponse parseResponse(HttpResponse httpResponse) {
        String responseBody = httpResponse.getBody();
        log.debug("DashScope TTS raw response: {}", responseBody);

        try {
            DashScopeTTSResponse response =
                    JsonUtils.getJsonCodec().fromJson(responseBody, DashScopeTTSResponse.class);

            // Check for errors
            if (response.getCode() != null) {
                String code = response.getCode();
                String message =
                        response.getMessage() != null ? response.getMessage() : "Unknown error";
                log.error("DashScope TTS API error - code: {}, message: {}", code, message);
                throw new TTSException("DashScope TTS error: " + message, code, responseBody);
            }

            String requestId = response.getRequestId();
            log.debug("TTS request_id: {}", requestId);

            TTSResponse.Builder builder = TTSResponse.builder().requestId(requestId);

            String audioUrl = null;
            byte[] audioData = null;

            // Parse output
            DashScopeTTSResponse.Output output = response.getOutput();
            if (output != null) {
                DashScopeTTSResponse.Audio audio = output.getAudio();
                if (audio != null) {
                    // Check for URL
                    if (audio.getUrl() != null && !audio.getUrl().isEmpty()) {
                        audioUrl = audio.getUrl();
                        log.debug("TTS audio URL present: {}", !audioUrl.isEmpty());
                        if (!audioUrl.isEmpty()) {
                            builder.audioUrl(audioUrl);
                        }
                    }
                    // Check for base64 data
                    if (audio.getData() != null && !audio.getData().isEmpty()) {
                        String base64Data = audio.getData();
                        log.debug(
                                "TTS audio data present: {}, length: {}",
                                base64Data != null && !base64Data.isEmpty(),
                                base64Data != null ? base64Data.length() : 0);
                        if (base64Data != null && !base64Data.isEmpty()) {
                            audioData = Base64.getDecoder().decode(base64Data);
                            builder.audioData(audioData);
                            log.debug("Decoded base64 audio: {} bytes", audioData.length);
                        }
                    }
                }
            } else {
                log.warn("TTS response has no 'output' field");
            }

            // If no audioData but has URL, download the audio
            if ((audioData == null || audioData.length == 0)
                    && audioUrl != null
                    && !audioUrl.isEmpty()) {
                log.debug("No inline audio data, downloading from URL...");
                try {
                    audioData = downloadAudio(audioUrl);
                    builder.audioData(audioData);
                    log.debug("Downloaded audio from URL: {} bytes", audioData.length);
                } catch (Exception e) {
                    log.warn("Failed to download audio from URL: {}", e.getMessage());
                }
            }

            // Set default format if not specified
            if (defaultOptions != null && defaultOptions.getFormat() != null) {
                builder.format(defaultOptions.getFormat());
            } else {
                builder.format("wav");
            }

            TTSResponse result = builder.build();
            log.debug(
                    "TTS synthesis complete - audioData: {} bytes, audioUrl: {}",
                    result.getAudioData() != null ? result.getAudioData().length : 0,
                    result.getAudioUrl() != null ? "present" : "null");

            return result;

        } catch (JsonException e) {
            log.error("Failed to parse TTS response: {}", e.getMessage());
            throw new TTSException("Failed to parse TTS response: " + e.getMessage(), e);
        }
    }

    /**
     * Downloads audio data from a URL.
     *
     * <p>Used when DashScope returns an audio URL instead of inline base64 data.
     * This typically happens in non-streaming mode.
     *
     * @param audioUrl the URL to download audio from
     * @return the raw audio bytes
     * @throws Exception if download fails (network error, invalid URL, etc.)
     */
    private byte[] downloadAudio(String audioUrl) throws Exception {
        java.net.URL url = new java.net.URL(audioUrl);
        try (java.io.InputStream is = url.openStream()) {
            return is.readAllBytes();
        }
    }

    /**
     * Returns the name of the TTS model being used.
     *
     * @return the model name (e.g., "qwen3-tts-flash", "qwen-tts")
     */
    @Override
    public String getModelName() {
        return modelName;
    }

    /**
     * Creates a new builder for DashScopeTTSModel.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing DashScopeTTSModel instances.
     */
    public static class Builder {
        private String apiKey;
        private String modelName = "qwen3-tts-flash";
        private String voice = "Cherry";
        private TTSOptions defaultOptions;
        private String baseUrl;
        private HttpTransport transport;

        /**
         * Sets the API key for DashScope authentication.
         *
         * @param apiKey the API key
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the TTS model name.
         *
         * <p>Available models:
         * <ul>
         *   <li>qwen3-tts-flash - Fast Qwen3 TTS</li>
         *   <li>qwen-tts - Standard Qwen TTS</li>
         *   <li>sambert-zhimao-v1 - Sambert Chinese voice</li>
         *   <li>cosyvoice-v1 - CosyVoice model</li>
         * </ul>
         *
         * @param modelName the model name
         * @return this builder
         */
        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        /**
         * Sets the default voice for synthesis.
         *
         * <p>Common voices include: Cherry, zhimao, etc.
         *
         * @param voice the voice name
         * @return this builder
         */
        public Builder voice(String voice) {
            this.voice = voice;
            return this;
        }

        /**
         * Sets the default TTS options.
         *
         * @param options default options
         * @return this builder
         */
        public Builder defaultOptions(TTSOptions options) {
            this.defaultOptions = options;
            return this;
        }

        /**
         * Sets a custom base URL for DashScope API.
         *
         * @param baseUrl the base URL
         * @return this builder
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets the HTTP transport to use.
         *
         * @param transport the HTTP transport
         * @return this builder
         */
        public Builder httpTransport(HttpTransport transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Builds the DashScopeTTSModel instance.
         *
         * @return a configured DashScopeTTSModel
         * @throws IllegalArgumentException if apiKey is not set
         */
        public DashScopeTTSModel build() {
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalArgumentException("API key is required");
            }
            return new DashScopeTTSModel(this);
        }
    }
}
