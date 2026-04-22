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

import io.agentscope.core.message.AudioBlock;
import reactor.core.publisher.Flux;

/**
 * Interface for real-time TTS models that support streaming input.
 *
 * <p>This interface extends {@link TTSModel} with methods for streaming text input,
 * enabling "speak as you generate" functionality. Unlike the base {@link TTSModel}
 * which requires complete text upfront, this interface allows pushing text
 * incrementally while maintaining context continuity.
 *
 * <p><b>Key difference from TTSModel:</b>
 * <ul>
 *   <li>{@link TTSModel}: One-time input, streaming output (e.g., HTTP + SSE)</li>
 *   <li>{@link RealtimeTTSModel}: Streaming input + streaming output (e.g., WebSocket)</li>
 * </ul>
 *
 * <p><b>Typical models:</b>
 * <ul>
 *   <li>{@code qwen3-tts-flash-realtime} - WebSocket real-time model</li>
 *   <li>{@code cosyvoice-v2} - WebSocket streaming model</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * RealtimeTTSModel tts = DashScopeRealtimeTTSModel.builder()
 *     .apiKey(apiKey)
 *     .modelName("qwen3-tts-flash-realtime")
 *     .voice("Cherry")
 *     .build();
 *
 * // Start streaming session
 * tts.startSession();
 *
 * // Push text chunks (context maintained for natural prosody)
 * tts.push("Hello, ").subscribe(audio -> player.play(audio));
 * tts.push("welcome to ").subscribe(audio -> player.play(audio));
 * tts.push("AgentScope.").subscribe(audio -> player.play(audio));
 *
 * // Finish session and get remaining audio
 * tts.finish().blockLast();
 *
 * // Clean up
 * tts.close();
 * }</pre>
 *
 * @see TTSModel
 * @see DashScopeRealtimeTTSModel
 */
public interface RealtimeTTSModel extends TTSModel {

    /**
     * Starts a new streaming session.
     *
     * <p>This typically establishes a WebSocket connection to the TTS service.
     * Must be called before {@link #push(String)} or {@link #finish()}.
     *
     * @throws TTSException if session cannot be started
     */
    void startSession();

    /**
     * Pushes text incrementally to the TTS service.
     *
     * <p>Text is buffered and synthesized while maintaining context continuity,
     * resulting in natural prosody and intonation across chunks.
     *
     * @param text the text chunk to synthesize
     * @return Flux of AudioBlock containing synthesized audio
     */
    Flux<AudioBlock> push(String text);

    /**
     * Signals end of input and flushes remaining audio.
     *
     * <p>Call this when all text has been pushed to receive any remaining
     * synthesized audio.
     *
     * @return Flux of AudioBlock containing remaining audio
     */
    Flux<AudioBlock> finish();

    /**
     * Synthesizes text using streaming and returns audio blocks.
     *
     * <p>This is a convenience method that handles the full session lifecycle
     * (start, push, finish) for a single text input.
     *
     * @param text the complete text to synthesize
     * @return Flux of AudioBlock as audio is synthesized
     */
    Flux<AudioBlock> synthesizeStream(String text);

    /**
     * Closes the TTS session and releases resources.
     *
     * <p>Should be called when the model is no longer needed to close
     * WebSocket connections and clean up resources.
     */
    void close();

    /**
     * Gets the audio stream for receiving synthesized audio.
     *
     * <p>This method returns a Flux that emits audio blocks as they are
     * synthesized. Subscribe to this stream once after calling
     * {@link #startSession()} to receive audio data.
     *
     * <p><b>Important:</b> Only subscribe once per session. Multiple
     * subscriptions may cause duplicate audio playback.
     *
     * @return Flux of AudioBlock that emits audio as it's synthesized
     */
    Flux<AudioBlock> getAudioStream();
}
