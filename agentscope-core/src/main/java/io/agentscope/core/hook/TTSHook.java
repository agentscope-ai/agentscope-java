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
package io.agentscope.core.hook;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Hook for real-time Text-to-Speech synthesis during agent execution.
 *
 * <p>This hook implements "speak as you generate" by listening to streaming
 * reasoning events and synthesizing speech in real-time.
 *
 * <p><b>Two Usage Modes:</b>
 * <ul>
 *   <li><b>Local Playback (CLI/Desktop):</b> Use audioPlayer for direct playback</li>
 *   <li><b>Server Mode (Web/SSE):</b> Use audioCallback to return audio to frontend</li>
 * </ul>
 *
 * <p><b>Example 1: Local Playback (CLI/Testing)</b>
 * <pre>{@code
 * AudioPlayer player = AudioPlayer.builder().sampleRate(24000).build();
 *
 * TTSHook ttsHook = TTSHook.builder()
 *     .ttsModel(ttsModel)
 *     .audioPlayer(player)  // Local playback
 *     .build();
 * }</pre>
 *
 * <p><b>Example 2: Server Mode (Return to Frontend via SSE)</b>
 * <pre>{@code
 * TTSHook ttsHook = TTSHook.builder()
 *     .ttsModel(ttsModel)
 *     .audioCallback(audio -> {
 *         // Send via SSE/WebSocket to frontend
 *         sseEmitter.send(audio);
 *     })
 *     .build();
 * }</pre>
 *
 * <p><b>Example 3: Get Audio Stream (Reactive)</b>
 * <pre>{@code
 * TTSHook ttsHook = TTSHook.builder()
 *     .ttsModel(ttsModel)
 *     .build();
 *
 * // Subscribe to audio stream
 * ttsHook.getAudioStream()
 *     .subscribe(audio -> sendToClient(audio));
 * }</pre>
 */
public class TTSHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(TTSHook.class);

    private final DashScopeRealtimeTTSModel ttsModel;
    private final AudioPlayer audioPlayer;
    private final boolean autoStartPlayer;
    private final boolean realtimeMode;
    private final Consumer<AudioBlock> audioCallback;

    // Reactive audio stream for external consumers
    private final Sinks.Many<AudioBlock> audioSink =
            Sinks.many().multicast().onBackpressureBuffer();

    private boolean playerStarted = false;
    private boolean sessionStarted = false;

    private TTSHook(Builder builder) {
        this.ttsModel = builder.ttsModel;
        this.audioPlayer = builder.audioPlayer;
        this.autoStartPlayer = builder.autoStartPlayer;
        this.realtimeMode = builder.realtimeMode;
        this.audioCallback = builder.audioCallback;
    }

    /**
     * Gets the reactive audio stream.
     *
     * <p>Use this to subscribe to audio blocks as they are generated.
     * This is useful for SSE/WebSocket streaming to frontend.
     *
     * <p>Example:
     * <pre>{@code
     * ttsHook.getAudioStream()
     *     .map(audio -> ((Base64Source) audio.getSource()).getData())
     *     .subscribe(base64 -> sseEmitter.send(base64));
     * }</pre>
     *
     * @return Flux of AudioBlock that emits audio as it's synthesized
     */
    public reactor.core.publisher.Flux<AudioBlock> getAudioStream() {
        return audioSink.asFlux();
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (realtimeMode) {
            return handleRealtimeMode(event);
        } else {
            return handleBatchMode(event);
        }
    }

    /**
     * Handle real-time mode: synthesize on each chunk.
     */
    private <T extends HookEvent> Mono<T> handleRealtimeMode(T event) {
        if (event instanceof ReasoningChunkEvent) {
            ReasoningChunkEvent e = (ReasoningChunkEvent) event;
            Msg incrementalChunk = e.getIncrementalChunk();

            if (incrementalChunk != null) {
                String text = incrementalChunk.getTextContent();
                if (text != null && !text.isEmpty()) {
                    if (!sessionStarted) {
                        ttsModel.startSession();
                        sessionStarted = true;
                        ensurePlayerStarted();
                    }

                    ttsModel.push(text).doOnNext(this::emitAudio).subscribe();
                }
            }
        } else if (event instanceof PostReasoningEvent) {
            if (sessionStarted) {
                ttsModel.finish()
                        .doOnNext(this::emitAudio)
                        .doOnComplete(this::drainPlayer)
                        .blockLast();
                sessionStarted = false;
            }
        }

        return Mono.just(event);
    }

    /**
     * Handle batch mode: wait for complete response then synthesize.
     */
    private <T extends HookEvent> Mono<T> handleBatchMode(T event) {
        if (event instanceof PostReasoningEvent) {
            PostReasoningEvent e = (PostReasoningEvent) event;
            Msg msg = e.getReasoningMessage();

            if (msg != null) {
                String text = msg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    synthesizeAndEmit(text);
                }
            }
        }

        return Mono.just(event);
    }

    /**
     * Emit audio to all consumers (player, callback, stream).
     */
    private void emitAudio(AudioBlock audio) {
        // 1. Emit to reactive stream (for SSE/WebSocket consumers)
        audioSink.tryEmitNext(audio);

        // 2. Call callback if provided
        if (audioCallback != null) {
            audioCallback.accept(audio);
        }

        // 3. Play locally if player is configured
        if (audioPlayer != null) {
            audioPlayer.play(audio);
        }
    }

    /**
     * Ensure audio player is started.
     */
    private void ensurePlayerStarted() {
        if (audioPlayer != null && autoStartPlayer && !playerStarted) {
            audioPlayer.start();
            playerStarted = true;
        }
    }

    /**
     * Drain the audio player.
     */
    private void drainPlayer() {
        if (audioPlayer != null) {
            audioPlayer.drain();
        }
    }

    /**
     * Synthesize complete text and emit (for batch mode).
     */
    private void synthesizeAndEmit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        log.debug("Synthesizing text: {}...", text.substring(0, Math.min(50, text.length())));

        ensurePlayerStarted();

        ttsModel.synthesizeStream(text)
                .doOnNext(this::emitAudio)
                .doOnComplete(this::drainPlayer)
                .blockLast();
    }

    /**
     * Stop the audio player and clean up resources.
     */
    public void stop() {
        if (audioPlayer != null && playerStarted) {
            audioPlayer.stop();
            playerStarted = false;
        }
        sessionStarted = false;
        audioSink.tryEmitComplete();
    }

    /**
     * Creates a new builder for TTSHook.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing TTSHook instances.
     */
    public static class Builder {
        private DashScopeRealtimeTTSModel ttsModel;
        private AudioPlayer audioPlayer;
        private boolean autoStartPlayer = true;
        private boolean realtimeMode = true;
        private Consumer<AudioBlock> audioCallback;

        /**
         * Sets the TTS model for speech synthesis. (Required)
         *
         * @param ttsModel the realtime TTS model
         * @return this builder
         */
        public Builder ttsModel(DashScopeRealtimeTTSModel ttsModel) {
            this.ttsModel = ttsModel;
            return this;
        }

        /**
         * Sets the audio player for local playback. (Optional)
         *
         * <p>If not set, audio will only be available via audioCallback or getAudioStream().
         * This is suitable for server-side usage where audio should be returned to frontend.
         *
         * @param audioPlayer the audio player, or null for server mode
         * @return this builder
         */
        public Builder audioPlayer(AudioPlayer audioPlayer) {
            this.audioPlayer = audioPlayer;
            return this;
        }

        /**
         * Sets whether to auto-start the audio player.
         *
         * @param autoStartPlayer true to auto-start (default: true)
         * @return this builder
         */
        public Builder autoStartPlayer(boolean autoStartPlayer) {
            this.autoStartPlayer = autoStartPlayer;
            return this;
        }

        /**
         * Sets whether to use real-time mode.
         *
         * <p>When true (default), TTS is triggered on each text chunk as LLM generates.
         * When false, TTS waits for complete response before synthesis.
         *
         * @param realtimeMode true for real-time "speak as you generate" (default)
         * @return this builder
         */
        public Builder realtimeMode(boolean realtimeMode) {
            this.realtimeMode = realtimeMode;
            return this;
        }

        /**
         * Sets a callback for receiving audio blocks. (Optional)
         *
         * <p>This is the recommended way for server-side usage to handle audio.
         * The callback is invoked for each audio block as it's synthesized.
         *
         * <p>Example for SSE:
         * <pre>{@code
         * .audioCallback(audio -> {
         *     Base64Source src = (Base64Source) audio.getSource();
         *     sseEmitter.send(SseEmitter.event()
         *         .name("audio")
         *         .data(src.getData()));
         * })
         * }</pre>
         *
         * @param audioCallback callback to receive audio blocks
         * @return this builder
         */
        public Builder audioCallback(Consumer<AudioBlock> audioCallback) {
            this.audioCallback = audioCallback;
            return this;
        }

        /**
         * Builds the TTSHook instance.
         *
         * @return configured TTSHook
         * @throws IllegalArgumentException if ttsModel is not set
         */
        public TTSHook build() {
            if (ttsModel == null) {
                throw new IllegalArgumentException("TTS model is required");
            }
            // audioPlayer is now optional - server mode doesn't need it
            return new TTSHook(this);
        }
    }
}
