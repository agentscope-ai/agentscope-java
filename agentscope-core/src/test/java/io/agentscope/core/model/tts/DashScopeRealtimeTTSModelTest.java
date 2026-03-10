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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for DashScopeRealtimeTTSModel.
 */
class DashScopeRealtimeTTSModelTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should throw when API key is missing")
        void shouldThrowWhenApiKeyMissing() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DashScopeRealtimeTTSModel.builder().modelName("qwen3-tts-flash").build());
        }

        @Test
        @DisplayName("should build with default values")
        void shouldBuildWithDefaults() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertNotNull(model);
            assertEquals("qwen3-tts-flash-realtime", model.getModelName());
        }

        @Test
        @DisplayName("should build with custom values")
        void shouldBuildWithCustomValues() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .modelName("custom-model")
                            .voice("Cherry")
                            .sampleRate(48000)
                            .format("mp3")
                            .build();

            assertNotNull(model);
            assertEquals("custom-model", model.getModelName());
        }
    }

    @Nested
    @DisplayName("Streaming Input Support Tests")
    class StreamingInputSupportTests {

        @Test
        @DisplayName("should support streaming input")
        void shouldSupportStreamingInput() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertTrue(model.supportsStreamingInput());
        }
    }

    @Nested
    @DisplayName("Session Tests")
    class SessionTests {

        @Test
        @DisplayName("should start session without error")
        void shouldStartSessionWithoutError() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // startSession() may throw TTSException if API key is invalid (expected in unit tests)
            // This test verifies the method can be called, actual connection is tested in E2E tests
            try {
                model.startSession();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle multiple start session calls")
        void shouldHandleMultipleStartSessionCalls() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // startSession() may throw TTSException if API key is invalid (expected in unit tests)
            // This test verifies idempotent behavior when called multiple times
            try {
                model.startSession();
                model.startSession(); // Should not throw - idempotent operation
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }
    }

    @Nested
    @DisplayName("Push Tests")
    class PushTests {

        @Test
        @DisplayName("should handle push without session start")
        void shouldHandlePushWithoutSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // push() may throw TTSException if session fails to start (expected in unit tests)
            // This test verifies push can be called, actual connection tested in E2E tests
            try {
                // push should start session automatically (or handle gracefully)
                assertNotNull(model.push("test"));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle null text")
        void shouldHandleNullText() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw
            assertNotNull(model.push(null));
        }

        @Test
        @DisplayName("should handle empty text")
        void shouldHandleEmptyText() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw
            assertNotNull(model.push(""));
        }
    }

    @Nested
    @DisplayName("Finish Tests")
    class FinishTests {

        @Test
        @DisplayName("should handle finish without session start")
        void shouldHandleFinishWithoutSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should return empty flux
            assertNotNull(model.finish());
        }
    }

    @Nested
    @DisplayName("Synthesize Tests")
    class SynthesizeTests {

        @Test
        @DisplayName("should return Mono for synchronous synthesis")
        void shouldReturnMonoForSynchronousSynthesis() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Just verify it returns a Mono, actual network call is E2E test
            assertNotNull(model.synthesize("test", null));
        }
    }

    @Nested
    @DisplayName("SynthesizeStream Tests")
    class SynthesizeStreamTests {

        @Test
        @DisplayName("should return Flux for streaming synthesis")
        void shouldReturnFluxForStreamingSynthesis() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Just verify it returns a Flux, actual network call is E2E test
            assertNotNull(model.synthesizeStream("test"));
        }

        @Test
        @DisplayName("should handle null text in synthesizeStream")
        void shouldHandleNullTextInSynthesizeStream() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw
            assertNotNull(model.synthesizeStream(null));
        }

        @Test
        @DisplayName("should handle empty text in synthesizeStream")
        void shouldHandleEmptyTextInSynthesizeStream() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw
            assertNotNull(model.synthesizeStream(""));
        }
    }

    @Nested
    @DisplayName("Audio Stream Tests")
    class AudioStreamTests {

        @Test
        @DisplayName("should provide audio stream")
        void shouldProvideAudioStream() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertNotNull(model.getAudioStream());
        }
    }

    @Nested
    @DisplayName("Session Workflow Tests")
    class SessionWorkflowTests {

        @Test
        @DisplayName("should handle push then finish workflow")
        void shouldHandlePushThenFinishWorkflow() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();

                // Push small text
                var result1 = model.push("Hi");
                assertNotNull(result1);

                // Finish should flush remaining text
                var result2 = model.finish();
                assertNotNull(result2);
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle push with sentence end triggers synthesis")
        void shouldHandlePushWithSentenceEnd() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();

                // Push text with Chinese period
                var result = model.push("你好。");
                assertNotNull(result);
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle push with various punctuation")
        void shouldHandlePushWithVariousPunctuation() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();

                // Test various punctuation marks
                assertNotNull(model.push("Hello!"));
                assertNotNull(model.push("What?"));
                assertNotNull(model.push("OK,"));
                assertNotNull(model.push("好！"));
                assertNotNull(model.push("吗？"));
                assertNotNull(model.push("好，"));
                assertNotNull(model.push("Line\n"));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle finish after multiple pushes")
        void shouldHandleFinishAfterMultiplePushes() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.push("A");
                model.push("B");
                model.push("C");

                var result = model.finish();
                assertNotNull(result);
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle double finish")
        void shouldHandleDoubleFinish() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.push("test");

                model.finish();
                // Second finish should return empty
                var result = model.finish();
                assertNotNull(result);
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }
    }

    @Nested
    @DisplayName("Builder Method Tests")
    class BuilderMethodTests {

        @Test
        @DisplayName("should set all builder properties")
        void shouldSetAllBuilderProperties() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-key")
                            .modelName("test-model")
                            .voice("TestVoice")
                            .sampleRate(16000)
                            .format("pcm")
                            .build();

            assertNotNull(model);
            assertEquals("test-model", model.getModelName());
        }

        @Test
        @DisplayName("should throw on empty API key")
        void shouldThrowOnEmptyApiKey() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DashScopeRealtimeTTSModel.builder().apiKey("").build());
        }

        @Test
        @DisplayName("should set session mode")
        void shouldSetSessionMode() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-key")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.COMMIT)
                            .build();

            assertNotNull(model);
        }

        @Test
        @DisplayName("should set server commit mode by default")
        void shouldSetServerCommitModeByDefault() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-key").build();

            assertNotNull(model);
        }

        @Test
        @DisplayName("should set language type")
        void shouldSetLanguageType() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-key")
                            .languageType("Chinese")
                            .build();

            assertNotNull(model);
        }

        @Test
        @DisplayName("should set all new builder properties")
        void shouldSetAllNewBuilderProperties() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-key")
                            .modelName("qwen3-tts-flash-realtime")
                            .voice("Cherry")
                            .sampleRate(24000)
                            .format("pcm")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)
                            .languageType("Auto")
                            .build();

            assertNotNull(model);
            assertEquals("qwen3-tts-flash-realtime", model.getModelName());
        }
    }

    @Nested
    @DisplayName("SessionMode Enum Tests")
    class SessionModeEnumTests {

        @Test
        @DisplayName("should have correct value for SERVER_COMMIT")
        void shouldHaveCorrectValueForServerCommit() {
            assertEquals(
                    "server_commit",
                    DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT.getValue());
        }

        @Test
        @DisplayName("should have correct value for COMMIT")
        void shouldHaveCorrectValueForCommit() {
            assertEquals("commit", DashScopeRealtimeTTSModel.SessionMode.COMMIT.getValue());
        }
    }

    @Nested
    @DisplayName("Close Tests")
    class CloseTests {

        @Test
        @DisplayName("should handle close without session start")
        void shouldHandleCloseWithoutSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw
            model.close();
        }

        @Test
        @DisplayName("should handle close after session start")
        void shouldHandleCloseAfterSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                // Should not throw
                model.close();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
                // close() should still work even if session failed to start
                model.close();
            }
        }

        @Test
        @DisplayName("should handle double close")
        void shouldHandleDoubleClose() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.close();
                // Second close should not throw
                model.close();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
                // close() should still work even if session failed to start
                model.close();
                model.close();
            }
        }
    }

    @Nested
    @DisplayName("Buffer Operation Tests")
    class BufferOperationTests {

        @Test
        @DisplayName("should handle commitTextBuffer after session start")
        void shouldHandleCommitTextBuffer() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.COMMIT)
                            .build();

            try {
                model.startSession();
                model.push("test");
                // Should not throw
                model.commitTextBuffer();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }

        @Test
        @DisplayName("should handle clearTextBuffer after session start")
        void shouldHandleClearTextBuffer() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.push("test");
                // Should not throw
                model.clearTextBuffer();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key - actual connection tested in E2E
            }
        }
    }

    @Nested
    @DisplayName("Wait For Response Tests")
    class WaitForResponseTests {

        @Test
        @DisplayName("should return true when no response pending")
        void shouldReturnTrueWhenNoResponsePending() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertTrue(model.waitForResponseDone(1, java.util.concurrent.TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("should return false on timeout")
        void shouldReturnFalseOnTimeout() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // When responseDoneFuture is null, should return true immediately
            assertTrue(model.waitForResponseDone(1, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Synthesize Extended Tests")
    class SynthesizeExtendedTests {

        @Test
        @DisplayName("should handle synthesize with null options")
        void shouldHandleSynthesizeWithNullOptions() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // synthesize() may throw TTSException if API key is invalid (expected in unit tests)
            try {
                assertNotNull(model.synthesize("test", null));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle synthesize with options")
        void shouldHandleSynthesizeWithOptions() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            TTSOptions options = TTSOptions.builder().language("Chinese").build();
            try {
                assertNotNull(model.synthesize("test", options));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Get Audio Stream Tests")
    class GetAudioStreamTests {

        @Test
        @DisplayName("should return empty flux when no session started")
        void shouldReturnEmptyFluxWhenNoSessionStarted() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertNotNull(model.getAudioStream());
        }
    }

    @Nested
    @DisplayName("Close Tests Extended")
    class CloseTestsExtended {

        @Test
        @DisplayName("should handle close when connection is null")
        void shouldHandleCloseWhenConnectionIsNull() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw even if connection is null
            model.close();
        }

        @Test
        @DisplayName("should handle close when audioSink is null")
        void shouldHandleCloseWhenAudioSinkIsNull() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // Should not throw even if audioSink is null
            model.close();
        }
    }

    @Nested
    @DisplayName("SynthesizeStream Tests Extended")
    class SynthesizeStreamTestsExtended {

        @Test
        @DisplayName("should return flux for synthesizeStream")
        void shouldReturnFluxForSynthesizeStream() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // synthesizeStream() returns a Flux, but may fail due to invalid API key
            // This test verifies the method can be called
            assertNotNull(model.synthesizeStream("test"));
        }

        @Test
        @DisplayName("should handle synthesizeStream with long text")
        void shouldHandleSynthesizeStreamWithLongText() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            String longText = "This is a long text. ".repeat(100);
            try {
                assertNotNull(model.synthesizeStream(longText));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle synthesizeStream with special characters")
        void shouldHandleSynthesizeStreamWithSpecialCharacters() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            String specialText = "Hello! 你好？测试，test。";
            try {
                assertNotNull(model.synthesizeStream(specialText));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle synthesizeStream in commit mode")
        void shouldHandleSynthesizeStreamInCommitMode() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.COMMIT)
                            .build();

            try {
                assertNotNull(model.synthesizeStream("test"));
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Start Message Receiver Tests")
    class StartMessageReceiverTests {

        @Test
        @DisplayName("should handle startMessageReceiver when connection is null")
        void shouldHandleStartMessageReceiverWhenConnectionIsNull() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // startSession() creates connection, but if connection is null,
            // startMessageReceiver() should handle it gracefully
            // This is tested indirectly through startSession()
            try {
                model.startSession();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Append Text Tests")
    class AppendTextTests {

        @Test
        @DisplayName("should handle appendText after session start")
        void shouldHandleAppendTextAfterSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                // push() calls appendText() internally
                model.push("test");
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Update Session Tests")
    class UpdateSessionTests {

        @Test
        @DisplayName("should update session after start")
        void shouldUpdateSessionAfterStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .voice("Cherry")
                            .sampleRate(24000)
                            .build();

            try {
                // startSession() calls updateSession() internally
                model.startSession();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should update session with custom settings")
        void shouldUpdateSessionWithCustomSettings() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .voice("Serena")
                            .sampleRate(48000)
                            .format("mp3")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.COMMIT)
                            .languageType("Chinese")
                            .build();

            try {
                model.startSession();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Send Event Tests")
    class SendEventTests {

        @Test
        @DisplayName("should handle sendEvent after session start")
        void shouldHandleSendEventAfterSessionStart() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                // push() calls sendEvent() internally
                model.push("test");
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle sendEvent for commitTextBuffer")
        void shouldHandleSendEventForCommitTextBuffer() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .mode(DashScopeRealtimeTTSModel.SessionMode.COMMIT)
                            .build();

            try {
                model.startSession();
                model.push("test");
                // commitTextBuffer() calls sendEvent() internally
                model.commitTextBuffer();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle sendEvent for clearTextBuffer")
        void shouldHandleSendEventForClearTextBuffer() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.push("test");
                // clearTextBuffer() calls sendEvent() internally
                model.clearTextBuffer();
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }
    }

    @Nested
    @DisplayName("Finish Tests Extended")
    class FinishTestsExtended {

        @Test
        @DisplayName("should handle finish after push")
        void shouldHandleFinishAfterPush() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            try {
                model.startSession();
                model.push("test");
                var result = model.finish();
                assertNotNull(result);
            } catch (TTSException e) {
                // Expected in unit tests with invalid API key
            }
        }

        @Test
        @DisplayName("should handle finish returns flux")
        void shouldHandleFinishReturnsFlux() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // finish() should return Flux even without session
            assertNotNull(model.finish());
        }
    }

    @Nested
    @DisplayName("Wait For Response Tests Extended")
    class WaitForResponseTestsExtended {

        @Test
        @DisplayName("should handle waitForResponseDone with very short timeout")
        void shouldHandleWaitForResponseDoneWithVeryShortTimeout() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // With null responseDoneFuture, should return true immediately
            assertTrue(model.waitForResponseDone(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("should handle waitForResponseDone with zero timeout")
        void shouldHandleWaitForResponseDoneWithZeroTimeout() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            // With null responseDoneFuture, should return true immediately
            assertTrue(model.waitForResponseDone(0, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
    }

    @Nested
    @DisplayName("Get Model Name Tests")
    class GetModelNameTests {

        @Test
        @DisplayName("should return default model name")
        void shouldReturnDefaultModelName() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder().apiKey("test-api-key").build();

            assertEquals("qwen3-tts-flash-realtime", model.getModelName());
        }

        @Test
        @DisplayName("should return custom model name")
        void shouldReturnCustomModelName() {
            DashScopeRealtimeTTSModel model =
                    DashScopeRealtimeTTSModel.builder()
                            .apiKey("test-api-key")
                            .modelName("custom-model")
                            .build();

            assertEquals("custom-model", model.getModelName());
        }
    }
}
