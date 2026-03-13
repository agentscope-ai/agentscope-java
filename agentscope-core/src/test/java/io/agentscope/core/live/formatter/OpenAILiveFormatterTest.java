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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.LiveEventType;
import io.agentscope.core.live.config.GenerationConfig;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAILiveFormatter Tests")
class OpenAILiveFormatterTest {

    private OpenAILiveFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter =
                OpenAILiveFormatter.builder()
                        .sessionType("realtime")
                        .noiseReduction("near_field")
                        .speed(1.0f)
                        .transcriptionModel("gpt-4o-transcribe")
                        .streamingTranscription(true)
                        .semanticVad(true)
                        .vadThreshold(0.5f)
                        .silenceDurationMs(800)
                        .prefixPaddingMs(300)
                        .idleTimeoutMs(30000)
                        .vadEnabled(true)
                        .build();
    }

    @Nested
    @DisplayName("Input Formatting Tests")
    class InputFormattingTests {

        @Test
        @DisplayName("Should format audio input")
        void shouldFormatAudioInput() {
            byte[] audioData = new byte[] {1, 2, 3, 4};
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    AudioBlock.builder()
                                            .source(RawSource.pcm24kMono(audioData))
                                            .build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_buffer.append"));
            assertTrue(result.contains("audio"));
        }

        @Test
        @DisplayName("Should format text input")
        void shouldFormatTextInput() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hello, how are you?").build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("conversation.item.create"));
            assertTrue(result.contains("input_text"));
            assertTrue(result.contains("Hello, how are you?"));
        }

        @Test
        @DisplayName("Should format tool result")
        void shouldFormatToolResult() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.TOOL)
                            .content(
                                    ToolResultBlock.builder()
                                            .id("call_abc123")
                                            .output(
                                                    TextBlock.builder()
                                                            .text("{\"temperature\": 25}")
                                                            .build())
                                            .build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("function_call_output"));
            assertTrue(result.contains("call_abc123"));
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
        @DisplayName("Should return null for null message")
        void shouldReturnNullForNullMessage() {
            String result = formatter.formatInput(null);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Session Config Tests")
    class SessionConfigTests {

        @Test
        @DisplayName("Should build session config with semantic VAD")
        void shouldBuildSessionConfigWithSemanticVad() {
            LiveConfig config =
                    LiveConfig.builder()
                            .voice("alloy")
                            .instructions("You are a helpful assistant.")
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("session.update"));
            assertTrue(result.contains("alloy"));
            assertTrue(result.contains("semantic_vad"));
            assertTrue(result.contains("threshold"));
            assertTrue(result.contains("near_field"));
        }

        @Test
        @DisplayName("Should build session config with tools")
        void shouldBuildSessionConfigWithTools() {
            ToolSchema tool =
                    ToolSchema.builder()
                            .name("get_weather")
                            .description("Get weather information")
                            .parameters(Map.of("type", "object"))
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = formatter.buildSessionConfig(config, List.of(tool));

            assertNotNull(result);
            assertTrue(result.contains("tools"));
            assertTrue(result.contains("get_weather"));
            assertTrue(result.contains("function"));
        }

        @Test
        @DisplayName("Should build session config without VAD")
        void shouldBuildSessionConfigWithoutVad() {
            OpenAILiveFormatter noVadFormatter =
                    OpenAILiveFormatter.builder().vadEnabled(false).build();

            LiveConfig config = LiveConfig.builder().voice("alloy").build();

            String result = noVadFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("session.update"));
            assertTrue(result.contains("\"turn_detection\":null"));
        }

        @Test
        @DisplayName("Should include generation config parameters")
        void shouldIncludeGenerationConfigParameters() {
            LiveConfig config =
                    LiveConfig.builder()
                            .generationConfig(
                                    GenerationConfig.builder()
                                            .temperature(0.7f)
                                            .maxTokens(1000)
                                            .build())
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("temperature"));
            assertTrue(result.contains("max_response_output_tokens"));
        }

        @Test
        @DisplayName("Should include input transcription config when enabled")
        void shouldIncludeInputTranscriptionConfig() {
            LiveConfig config = LiveConfig.builder().enableInputTranscription(true).build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("input_audio_transcription"));
            assertTrue(result.contains("gpt-4o-transcribe"));
        }

        @Test
        @DisplayName("Should include prompt configuration")
        void shouldIncludePromptConfiguration() {
            OpenAILiveFormatter promptFormatter =
                    OpenAILiveFormatter.builder()
                            .promptId("prompt_123")
                            .promptVersion("v1")
                            .promptVariables(Map.of("name", "Alice"))
                            .vadEnabled(true)
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = promptFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("prompt"));
            assertTrue(result.contains("prompt_123"));
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - GA Version")
    class GaVersionOutputParsingTests {

        @Test
        @DisplayName("Should parse session.created event")
        void shouldParseSessionCreatedEvent() {
            String serverMsg = "{\"type\":\"session.created\",\"session\":{}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SESSION_CREATED, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse GA audio delta event")
        void shouldParseGaAudioDeltaEvent() {
            String serverMsg = "{\"type\":\"response.output_audio.delta\",\"delta\":\"AQIDBA==\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.AUDIO_DELTA, event.type());
            assertFalse(event.isLast());
            assertNotNull(event.message());
        }

        @Test
        @DisplayName("Should parse GA text delta event")
        void shouldParseGaTextDeltaEvent() {
            String serverMsg = "{\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TEXT_DELTA, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse GA output transcription delta event")
        void shouldParseGaOutputTranscriptionDeltaEvent() {
            String serverMsg =
                    "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"Hello\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse GA output transcription done event")
        void shouldParseGaOutputTranscriptionDoneEvent() {
            String serverMsg =
                    "{\"type\":\"response.output_audio_transcript.done\","
                            + "\"transcript\":\"Hello world\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Input Transcription")
    class InputTranscriptionParsingTests {

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
        @DisplayName("Should parse input transcription delta event")
        void shouldParseInputTranscriptionDeltaEvent() {
            String serverMsg =
                    "{\"type\":\"conversation.item.input_audio_transcription.delta\","
                            + "\"delta\":\"User\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.INPUT_TRANSCRIPTION, event.type());
            assertFalse(event.isLast());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Tool Call")
    class ToolCallParsingTests {

        @Test
        @DisplayName("Should parse tool call event")
        void shouldParseToolCallEvent() {
            String serverMsg =
                    "{\"type\":\"response.function_call_arguments.done\","
                            + "\"call_id\":\"call_123\",\"name\":\"get_weather\","
                            + "\"arguments\":\"{\\\"location\\\":\\\"Beijing\\\"}\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TOOL_CALL, event.type());
            assertTrue(event.isLast());
            assertNotNull(event.message());

            ContentBlock block = event.message().getContent().get(0);
            assertInstanceOf(ToolUseBlock.class, block);
            ToolUseBlock toolUse = (ToolUseBlock) block;
            assertEquals("call_123", toolUse.getId());
            assertEquals("get_weather", toolUse.getName());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - VAD Events")
    class VadEventsParsingTests {

        @Test
        @DisplayName("Should parse speech started event")
        void shouldParseSpeechStartedEvent() {
            String serverMsg = "{\"type\":\"input_audio_buffer.speech_started\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SPEECH_STARTED, event.type());
        }

        @Test
        @DisplayName("Should parse speech stopped event")
        void shouldParseSpeechStoppedEvent() {
            String serverMsg = "{\"type\":\"input_audio_buffer.speech_stopped\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SPEECH_STOPPED, event.type());
        }

        @Test
        @DisplayName("Should parse timeout triggered event")
        void shouldParseTimeoutTriggeredEvent() {
            String serverMsg = "{\"type\":\"input_audio_buffer.timeout_triggered\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.ERROR, event.type());
            assertTrue(event.isError());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Turn Complete and Error")
    class TurnCompleteAndErrorParsingTests {

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
    @DisplayName("Output Parsing Tests - Beta Version Compatibility")
    class BetaVersionCompatibilityTests {

        @Test
        @DisplayName("Should parse Beta audio delta event")
        void shouldParseBetaAudioDeltaEvent() {
            String serverMsg = "{\"type\":\"response.audio.delta\",\"delta\":\"AQIDBA==\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.AUDIO_DELTA, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse Beta text delta event")
        void shouldParseBetaTextDeltaEvent() {
            String serverMsg = "{\"type\":\"response.text.delta\",\"delta\":\"Hello\"}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TEXT_DELTA, event.type());
            assertFalse(event.isLast());
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create formatter with default values")
        void shouldCreateFormatterWithDefaults() {
            OpenAILiveFormatter defaultFormatter = OpenAILiveFormatter.builder().build();

            assertNotNull(defaultFormatter);
        }

        @Test
        @DisplayName("Should create formatter with all custom values")
        void shouldCreateFormatterWithAllCustomValues() {
            OpenAILiveFormatter customFormatter =
                    OpenAILiveFormatter.builder()
                            .sessionType("realtime")
                            .promptId("prompt_123")
                            .promptVersion("v1")
                            .promptVariables(Map.of("key", "value"))
                            .noiseReduction("far_field")
                            .speed(1.5f)
                            .transcriptionModel("whisper-1")
                            .streamingTranscription(false)
                            .semanticVad(false)
                            .vadThreshold(0.6f)
                            .silenceDurationMs(1000)
                            .prefixPaddingMs(400)
                            .idleTimeoutMs(60000)
                            .vadEnabled(true)
                            .build();

            assertNotNull(customFormatter);
        }
    }
}
