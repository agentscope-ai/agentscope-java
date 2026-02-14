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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.audio.AudioFormat;
import io.agentscope.core.live.audio.DashScopeModality;
import io.agentscope.core.live.config.GenerationConfig;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DashScopeLiveFormatter Tests")
class DashScopeLiveFormatterTest {

    private DashScopeLiveFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter =
                DashScopeLiveFormatter.builder()
                        .smoothOutput(true)
                        .repetitionPenalty(1.05f)
                        .presencePenalty(0.0f)
                        .vadEnabled(true)
                        .build();
    }

    @Nested
    @DisplayName("Input Formatting Tests")
    class InputFormattingTests {

        @Test
        @DisplayName("Should format audio input correctly")
        void shouldFormatAudioInput() {
            byte[] audioData = new byte[] {1, 2, 3, 4};
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    AudioBlock.builder()
                                            .source(RawSource.pcm16kMono(audioData))
                                            .build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_buffer.append"));
            assertTrue(result.contains("audio"));
            // Base64 of {1, 2, 3, 4} is "AQIDBA=="
            assertTrue(result.contains("AQIDBA=="));
        }

        @Test
        @DisplayName("Should format INTERRUPT control signal")
        void shouldFormatInterruptControl() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("response.cancel"));
        }

        @Test
        @DisplayName("Should format COMMIT control signal")
        void shouldFormatCommitControl() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.commit()).build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_buffer.commit"));
        }

        @Test
        @DisplayName("Should format CLEAR control signal")
        void shouldFormatClearControl() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.clear()).build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_buffer.clear"));
        }

        @Test
        @DisplayName("Should format CREATE_RESPONSE control signal")
        void shouldFormatCreateResponseControl() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.CONTROL)
                            .content(ControlBlock.createResponse())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("response.create"));
        }

        @Test
        @DisplayName("Should return null for text input (not supported)")
        void shouldReturnNullForTextInput() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for null message")
        void shouldReturnNullForNullMessage() {
            String result = formatter.formatInput(null);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests")
    class OutputParsingTests {

        @Test
        @DisplayName("Should parse session.created event")
        void shouldParseSessionCreatedEvent() {
            String serverMsg = "{\"type\":\"session.created\",\"session\":{}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SESSION_CREATED, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse response.audio.delta event")
        void shouldParseAudioDeltaEvent() {
            // Base64 of {1, 2, 3, 4} is "AQIDBA=="
            String serverMsg = "{\"type\":\"response.audio.delta\",\"delta\":\"AQIDBA==\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.AUDIO_DELTA, event.type());
            assertFalse(event.isLast());
            assertNotNull(event.message());
            assertEquals(MsgRole.ASSISTANT, event.message().getRole());
        }

        @Test
        @DisplayName("Should parse response.audio_transcript.delta event")
        void shouldParseOutputTranscriptionDeltaEvent() {
            String serverMsg = "{\"type\":\"response.audio_transcript.delta\",\"delta\":\"Hello\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse response.audio_transcript.done event")
        void shouldParseOutputTranscriptionDoneEvent() {
            String serverMsg =
                    "{\"type\":\"response.audio_transcript.done\",\"transcript\":\"Hello world\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse input transcription completed event")
        void shouldParseInputTranscriptionCompletedEvent() {
            String serverMsg =
                    "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                            + "\"transcript\":\"User said hello\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.INPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse speech_started event")
        void shouldParseSpeechStartedEvent() {
            String serverMsg = "{\"type\":\"input_audio_buffer.speech_started\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SPEECH_STARTED, event.type());
        }

        @Test
        @DisplayName("Should parse speech_stopped event")
        void shouldParseSpeechStoppedEvent() {
            String serverMsg = "{\"type\":\"input_audio_buffer.speech_stopped\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SPEECH_STOPPED, event.type());
        }

        @Test
        @DisplayName("Should parse response.done event")
        void shouldParseTurnCompleteEvent() {
            String serverMsg = "{\"type\":\"response.done\",\"response\":{}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TURN_COMPLETE, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse error event")
        void shouldParseErrorEvent() {
            String serverMsg =
                    "{\"type\":\"error\",\"error\":{\"code\":\"invalid_request\","
                            + "\"message\":\"Bad request\"}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.ERROR, event.type());
            assertTrue(event.isError());
        }

        @Test
        @DisplayName("Should handle unknown event type")
        void shouldHandleUnknownEventType() {
            String serverMsg = "{\"type\":\"unknown.event\",\"data\":{}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.UNKNOWN, event.type());
        }
    }

    @Nested
    @DisplayName("Session Config Tests")
    class SessionConfigTests {

        @Test
        @DisplayName("Should build session config with VAD enabled")
        void shouldBuildSessionConfigWithVad() {
            LiveConfig config =
                    LiveConfig.builder()
                            .voice("Cherry")
                            .instructions("You are an assistant")
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("session.update"));
            assertTrue(result.contains("Cherry"));
            assertTrue(result.contains("server_vad"));
            assertTrue(result.contains("pcm16"));
        }

        @Test
        @DisplayName("Should build session config without VAD")
        void shouldBuildSessionConfigWithoutVad() {
            DashScopeLiveFormatter noVadFormatter =
                    DashScopeLiveFormatter.builder().vadEnabled(false).build();

            LiveConfig config = LiveConfig.builder().voice("Cherry").build();

            String result = noVadFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("session.update"));
            // turn_detection should be null when VAD is disabled
            assertTrue(result.contains("\"turn_detection\":null"));
        }

        @Test
        @DisplayName("Should include DashScope-specific parameters")
        void shouldIncludeDashScopeSpecificParameters() {
            DashScopeLiveFormatter customFormatter =
                    DashScopeLiveFormatter.builder()
                            .smoothOutput(true)
                            .repetitionPenalty(1.1f)
                            .presencePenalty(0.5f)
                            .seed(42L)
                            .maxTokens(1000)
                            .topK(50)
                            .vadEnabled(true)
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = customFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("smooth_output"));
            assertTrue(result.contains("repetition_penalty"));
            assertTrue(result.contains("presence_penalty"));
            assertTrue(result.contains("seed"));
            assertTrue(result.contains("max_tokens"));
            assertTrue(result.contains("top_k"));
        }

        @Test
        @DisplayName("Should include generation config parameters")
        void shouldIncludeGenerationConfigParameters() {
            LiveConfig config =
                    LiveConfig.builder()
                            .generationConfig(
                                    GenerationConfig.builder().temperature(0.7f).topP(0.9f).build())
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("temperature"));
            assertTrue(result.contains("top_p"));
        }

        @Test
        @DisplayName("Should include input transcription config when enabled")
        void shouldIncludeInputTranscriptionConfig() {
            LiveConfig config = LiveConfig.builder().enableInputTranscription(true).build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_transcription"));
            assertTrue(result.contains("gummy-realtime-v1"));
        }

        @Test
        @DisplayName("Should include modalities in session config")
        void shouldIncludeModalitiesInSessionConfig() {
            LiveConfig config = LiveConfig.builder().build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("modalities"));
            assertTrue(result.contains("text"));
            assertTrue(result.contains("audio"));
        }

        @Test
        @DisplayName("Should use text-only modality when configured")
        void shouldUseTextOnlyModality() {
            DashScopeLiveFormatter textOnlyFormatter =
                    DashScopeLiveFormatter.builder()
                            .modality(DashScopeModality.TEXT)
                            .vadEnabled(true)
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = textOnlyFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("modalities"));
            assertTrue(result.contains("text"));
            // Should not contain "audio" in modalities (but may contain elsewhere)
        }

        @Test
        @DisplayName("Should use PCM24 output format for flash models")
        void shouldUsePcm24OutputForFlashModels() {
            DashScopeLiveFormatter flashFormatter =
                    DashScopeLiveFormatter.builder()
                            .outputAudioFormat(AudioFormat.PCM24_OUTPUT)
                            .vadEnabled(true)
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = flashFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("\"output_audio_format\":\"pcm24\""));
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create formatter with default values")
        void shouldCreateFormatterWithDefaults() {
            DashScopeLiveFormatter defaultFormatter = DashScopeLiveFormatter.builder().build();

            assertNotNull(defaultFormatter);
        }

        @Test
        @DisplayName("Should create formatter with all custom values")
        void shouldCreateFormatterWithAllCustomValues() {
            DashScopeLiveFormatter customFormatter =
                    DashScopeLiveFormatter.builder()
                            .smoothOutput(false)
                            .repetitionPenalty(1.2f)
                            .presencePenalty(0.3f)
                            .seed(123L)
                            .maxTokens(500)
                            .topK(40)
                            .vadEnabled(false)
                            .build();

            assertNotNull(customFormatter);
        }

        @Test
        @DisplayName("Should create formatter with audio format configuration")
        void shouldCreateFormatterWithAudioFormatConfig() {
            DashScopeLiveFormatter customFormatter =
                    DashScopeLiveFormatter.builder()
                            .inputAudioFormat(AudioFormat.PCM_16K_16BIT_MONO)
                            .outputAudioFormat(AudioFormat.PCM24_OUTPUT)
                            .modality(DashScopeModality.TEXT_AND_AUDIO)
                            .vadEnabled(true)
                            .build();

            assertNotNull(customFormatter);

            LiveConfig config = LiveConfig.builder().build();
            String result = customFormatter.buildSessionConfig(config, null);

            assertTrue(result.contains("\"input_audio_format\":\"pcm16\""));
            assertTrue(result.contains("\"output_audio_format\":\"pcm24\""));
        }
    }
}
