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

import reactor.core.publisher.Mono;

/**
 * Text-to-Speech model interface.
 *
 * <p>This interface defines the contract for TTS models that convert text
 * to audio. Implementations may support various TTS providers such as
 * DashScope, OpenAI, etc.
 *
 * <p>Example usage:
 * <pre>{@code
 * TTSModel tts = DashScopeTTSModel.builder()
 *     .apiKey("your-api-key")
 *     .modelName("qwen3-tts-flash")
 *     .voice("Cherry")
 *     .build();
 *
 * TTSResponse response = tts.synthesize("Hello, world!", null).block();
 * byte[] audioData = response.getAudioData();
 * }</pre>
 */
public interface TTSModel {

    /**
     * Synthesize text to audio.
     *
     * @param text the text to synthesize
     * @param options optional TTS options (null to use defaults)
     * @return Mono of TTSResponse containing audio data
     */
    Mono<TTSResponse> synthesize(String text, TTSOptions options);

    /**
     * Get model name for logging and identification.
     *
     * @return the model name
     */
    String getModelName();
}
