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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Unit tests for TTSHook.
 */
class TTSHookTest {

    private DashScopeRealtimeTTSModel mockTtsModel;
    private AudioPlayer mockPlayer;
    private Agent mockAgent;
    private GenerateOptions mockGenerateOptions;

    @BeforeEach
    void setUp() {
        mockTtsModel = mock(DashScopeRealtimeTTSModel.class);
        mockPlayer = mock(AudioPlayer.class);
        mockAgent = mock(Agent.class);
        mockGenerateOptions = mock(GenerateOptions.class);
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should throw when no TTS model is set")
        void shouldThrowWhenNoTtsModelSet() {
            assertThrows(IllegalArgumentException.class, () -> TTSHook.builder().build());
        }

        @Test
        @DisplayName("should build with TTS model only")
        void shouldBuildWithTtsModelOnly() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).build();

            assertNotNull(hook);
        }

        @Test
        @DisplayName("should build with all options")
        void shouldBuildWithAllOptions() {
            List<AudioBlock> receivedAudio = new ArrayList<>();

            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .audioPlayer(mockPlayer)
                            .autoStartPlayer(false)
                            .realtimeMode(true)
                            .audioCallback(receivedAudio::add)
                            .build();

            assertNotNull(hook);
        }
    }

    @Nested
    @DisplayName("Audio Stream Tests")
    class AudioStreamTests {

        @Test
        @DisplayName("should provide audio stream")
        void shouldProvideAudioStream() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).build();

            assertNotNull(hook.getAudioStream());
        }
    }

    @Nested
    @DisplayName("Realtime Mode Tests")
    class RealtimeModeTests {

        @Test
        @DisplayName("should process reasoning chunk in realtime mode")
        void shouldProcessReasoningChunkInRealtimeMode() {
            AudioBlock mockAudio =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            when(mockTtsModel.push(any())).thenReturn(Flux.just(mockAudio));
            when(mockTtsModel.getAudioStream()).thenReturn(Flux.empty());

            // Use audioCallback to avoid creating AudioPlayer in CI environment
            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .realtimeMode(true)
                            .audioCallback(audio -> {}) // Avoid AudioPlayer creation
                            .build();

            Msg chunk =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            mockAgent, "test-model", mockGenerateOptions, chunk, chunk);
            hook.onEvent(event).block();

            verify(mockTtsModel).startSession();
            verify(mockTtsModel).push("Hello");
        }

        @Test
        @DisplayName("should handle empty text in chunk")
        void shouldHandleEmptyTextInChunk() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).realtimeMode(true).build();

            Msg chunk =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("").build())
                            .build();

            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            mockAgent, "test-model", mockGenerateOptions, chunk, chunk);
            hook.onEvent(event).block();

            verify(mockTtsModel, never()).startSession();
            verify(mockTtsModel, never()).push(any());
        }
    }

    @Nested
    @DisplayName("Batch Mode Tests")
    class BatchModeTests {

        @Test
        @DisplayName("should process complete response in batch mode")
        void shouldProcessCompleteResponseInBatchMode() {
            AudioBlock mockAudio =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            when(mockTtsModel.synthesizeStream(any())).thenReturn(Flux.just(mockAudio));

            // Use audioCallback to avoid creating AudioPlayer in CI environment
            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .realtimeMode(false)
                            .audioCallback(audio -> {}) // Avoid AudioPlayer creation
                            .build();

            Msg response =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Complete response").build())
                            .build();

            PostReasoningEvent event =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, response);
            hook.onEvent(event).block();

            verify(mockTtsModel).synthesizeStream("Complete response");
        }
    }

    @Nested
    @DisplayName("Callback Tests")
    class CallbackTests {

        @Test
        @DisplayName("should invoke audio callback")
        void shouldInvokeAudioCallback() {
            AudioBlock mockAudio =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            when(mockTtsModel.synthesizeStream(any())).thenReturn(Flux.just(mockAudio));

            List<AudioBlock> receivedAudio = new ArrayList<>();

            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .realtimeMode(false)
                            .audioCallback(receivedAudio::add)
                            .build();

            Msg response =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Test").build())
                            .build();

            PostReasoningEvent event =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, response);
            hook.onEvent(event).block();

            assertEquals(1, receivedAudio.size());
        }
    }

    @Nested
    @DisplayName("Stop Tests")
    class StopTests {

        @Test
        @DisplayName("should stop without error when player is null")
        void shouldStopWithoutErrorWhenPlayerIsNull() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).build();

            // Should not throw
            hook.stop();
        }

        @Test
        @DisplayName("should not stop player if not started")
        void shouldNotStopPlayerIfNotStarted() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).audioPlayer(mockPlayer).build();

            // Player was never started, so stop() should not call audioPlayer.stop()
            hook.stop();

            verify(mockPlayer, never()).stop();
        }

        @Test
        @DisplayName("should close TTS model on stop")
        void shouldCloseTtsModelOnStop() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).build();

            hook.stop();

            verify(mockTtsModel).close();
        }
    }

    @Nested
    @DisplayName("Realtime Mode Finish Tests")
    class RealtimeModeFinishTests {

        @Test
        @DisplayName("should call finish on PostReasoningEvent in realtime mode")
        void shouldCallFinishOnPostReasoningEventInRealtimeMode() {
            AudioBlock mockAudio =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            when(mockTtsModel.push(any())).thenReturn(Flux.just(mockAudio));
            when(mockTtsModel.finish()).thenReturn(Flux.just(mockAudio));
            when(mockTtsModel.getAudioStream()).thenReturn(Flux.empty());

            // Use audioCallback to avoid creating AudioPlayer in CI environment
            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .realtimeMode(true)
                            .audioCallback(audio -> {}) // Avoid AudioPlayer creation
                            .build();

            // First send a chunk to start session
            Msg chunk =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();
            ReasoningChunkEvent chunkEvent =
                    new ReasoningChunkEvent(
                            mockAgent, "test-model", mockGenerateOptions, chunk, chunk);
            hook.onEvent(chunkEvent).block();

            // Then send PostReasoningEvent
            Msg response =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Complete").build())
                            .build();
            PostReasoningEvent postEvent =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, response);
            hook.onEvent(postEvent).block();

            verify(mockTtsModel).finish();
        }
    }

    @Nested
    @DisplayName("Batch Mode Edge Cases")
    class BatchModeEdgeCases {

        @Test
        @DisplayName("should handle null message in batch mode")
        void shouldHandleNullMessageInBatchMode() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).realtimeMode(false).build();

            PostReasoningEvent event =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, null);
            hook.onEvent(event).block();

            verify(mockTtsModel, never()).synthesizeStream(any());
        }

        @Test
        @DisplayName("should handle empty text in batch mode")
        void shouldHandleEmptyTextInBatchMode() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).realtimeMode(false).build();

            Msg response =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("").build())
                            .build();

            PostReasoningEvent event =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, response);
            hook.onEvent(event).block();

            verify(mockTtsModel, never()).synthesizeStream(any());
        }
    }

    @Nested
    @DisplayName("Realtime Mode Edge Cases")
    class RealtimeModeEdgeCases {

        @Test
        @DisplayName("should handle chunk with empty text content")
        void shouldHandleChunkWithEmptyTextContent() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).realtimeMode(true).build();

            // Message with empty text
            Msg chunk =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("").build())
                            .build();

            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            mockAgent, "test-model", mockGenerateOptions, chunk, chunk);
            hook.onEvent(event).block();

            verify(mockTtsModel, never()).startSession();
        }

        @Test
        @DisplayName("should handle chunk with null text content")
        void shouldHandleChunkWithNullTextContent() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).realtimeMode(true).build();

            // Message with no content blocks
            Msg chunk = Msg.builder().role(MsgRole.ASSISTANT).build();

            ReasoningChunkEvent event =
                    new ReasoningChunkEvent(
                            mockAgent, "test-model", mockGenerateOptions, chunk, chunk);
            hook.onEvent(event).block();

            verify(mockTtsModel, never()).startSession();
        }
    }

    @Nested
    @DisplayName("Audio Player Integration")
    class AudioPlayerIntegration {

        @Test
        @DisplayName("should play audio when player is configured")
        void shouldPlayAudioWhenPlayerConfigured() {
            AudioBlock mockAudio =
                    AudioBlock.builder()
                            .source(
                                    Base64Source.builder()
                                            .mediaType("audio/wav")
                                            .data("dGVzdA==")
                                            .build())
                            .build();

            when(mockTtsModel.synthesizeStream(any())).thenReturn(Flux.just(mockAudio));

            TTSHook hook =
                    TTSHook.builder()
                            .ttsModel(mockTtsModel)
                            .realtimeMode(false)
                            .audioPlayer(mockPlayer)
                            .autoStartPlayer(false) // Disable auto-start
                            .build();

            Msg response =
                    Msg.builder()
                            .role(MsgRole.ASSISTANT)
                            .content(TextBlock.builder().text("Test").build())
                            .build();

            PostReasoningEvent event =
                    new PostReasoningEvent(mockAgent, "test-model", mockGenerateOptions, response);
            hook.onEvent(event).block();

            verify(mockPlayer).play(any(AudioBlock.class));
        }
    }

    @Nested
    @DisplayName("Other Event Types")
    class OtherEventTypes {

        @Test
        @DisplayName("should pass through unknown event types")
        void shouldPassThroughUnknownEventTypes() {
            TTSHook hook = TTSHook.builder().ttsModel(mockTtsModel).build();

            // Create a PreReasoningEvent (not handled by TTSHook)
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("User message").build())
                            .build();
            PreReasoningEvent event =
                    new PreReasoningEvent(
                            mockAgent, "test-model", mockGenerateOptions, java.util.List.of(msg));

            var result = hook.onEvent(event).block();

            assertNotNull(result);
            assertEquals(event, result);
        }
    }
}
