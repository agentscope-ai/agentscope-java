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

@DisplayName("GeminiLiveFormatter Tests")
class GeminiLiveFormatterTest {

    private GeminiLiveFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter =
                GeminiLiveFormatter.builder()
                        .modelName("gemini-2.0-flash-exp")
                        .proactiveAudio(true)
                        .affectiveDialog(true)
                        .enableThinking(false)
                        .contextWindowCompression(true)
                        .triggerTokens(16000)
                        .slidingWindowTokens(10000)
                        .sessionResumption(true)
                        .activityHandling("START_OF_ACTIVITY_INTERRUPTS")
                        .startOfSpeechSensitivity(GeminiLiveFormatter.SpeechSensitivity.HIGH)
                        .endOfSpeechSensitivity(GeminiLiveFormatter.SpeechSensitivity.LOW)
                        .silenceDurationMs(1000)
                        .prefixPaddingMs(300)
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
                                            .source(RawSource.pcm16kMono(audioData))
                                            .build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("realtimeInput"));
            assertTrue(result.contains("\"audio\""));
            assertTrue(result.contains("audio/pcm;rate=16000"));
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
            assertTrue(result.contains("clientContent"));
            assertTrue(result.contains("turns"));
            assertTrue(result.contains("Hello, how are you?"));
            assertTrue(result.contains("turnComplete"));
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
                                            .name("get_weather")
                                            .output(
                                                    TextBlock.builder()
                                                            .text("{\"temperature\": 25}")
                                                            .build())
                                            .build())
                            .build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("toolResponse"));
            assertTrue(result.contains("functionResponses"));
            assertTrue(result.contains("call_abc123"));
        }

        @Test
        @DisplayName("Should format INTERRUPT control signal")
        void shouldFormatInterruptControl() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();

            String result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.contains("clientContent"));
            assertTrue(result.contains("turnComplete"));
        }

        @Test
        @DisplayName("Should return null for unsupported control signals")
        void shouldReturnNullForUnsupportedControlSignals() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.commit()).build();

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
    @DisplayName("Session Config Tests")
    class SessionConfigTests {

        @Test
        @DisplayName("Should build session config with voice and instructions")
        void shouldBuildSessionConfigWithVoiceAndInstructions() {
            LiveConfig config =
                    LiveConfig.builder()
                            .voice("Aoede")
                            .instructions("You are a helpful assistant.")
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("setup"));
            assertTrue(result.contains("Aoede"));
            assertTrue(result.contains("You are a helpful assistant."));
            assertTrue(result.contains("systemInstruction"));
        }

        @Test
        @DisplayName("Should build session config with all Gemini features")
        void shouldBuildSessionConfigWithAllGeminiFeatures() {
            LiveConfig config = LiveConfig.builder().voice("Aoede").build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("sessionResumption"));
            assertTrue(result.contains("contextWindowCompression"));
            assertTrue(result.contains("proactiveAudio"));
            assertTrue(result.contains("affectiveDialog"));
            assertTrue(result.contains("automaticActivityDetection"));
            assertTrue(result.contains("startOfSpeechSensitivity"));
            assertTrue(result.contains("endOfSpeechSensitivity"));
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
            assertTrue(result.contains("functionDeclarations"));
            assertTrue(result.contains("get_weather"));
        }

        @Test
        @DisplayName("Should include generation config parameters")
        void shouldIncludeGenerationConfigParameters() {
            LiveConfig config =
                    LiveConfig.builder()
                            .generationConfig(
                                    GenerationConfig.builder()
                                            .temperature(0.7f)
                                            .topP(0.9f)
                                            .topK(40)
                                            .maxTokens(1000)
                                            .build())
                            .build();

            String result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("temperature"));
            assertTrue(result.contains("topP"));
            assertTrue(result.contains("topK"));
            assertTrue(result.contains("maxOutputTokens"));
        }

        @Test
        @DisplayName("Should build session config with thinking enabled")
        void shouldBuildSessionConfigWithThinking() {
            GeminiLiveFormatter thinkingFormatter =
                    GeminiLiveFormatter.builder().enableThinking(true).thinkingBudget(1024).build();

            LiveConfig config = LiveConfig.builder().build();

            String result = thinkingFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("thinking"));
            assertTrue(result.contains("thinkingBudget"));
        }

        @Test
        @DisplayName("Should build session config with session resumption handle")
        void shouldBuildSessionConfigWithResumptionHandle() {
            GeminiLiveFormatter resumptionFormatter =
                    GeminiLiveFormatter.builder()
                            .sessionResumption(true)
                            .sessionResumptionHandle("previous-handle-123")
                            .build();

            LiveConfig config = LiveConfig.builder().build();

            String result = resumptionFormatter.buildSessionConfig(config, null);

            assertNotNull(result);
            assertTrue(result.contains("sessionResumption"));
            assertTrue(result.contains("previous-handle-123"));
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Session Events")
    class SessionEventsParsingTests {

        @Test
        @DisplayName("Should parse setupComplete event")
        void shouldParseSetupCompleteEvent() {
            String serverMsg = "{\"setupComplete\":{}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SESSION_CREATED, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse sessionResumptionUpdate event")
        void shouldParseSessionResumptionUpdateEvent() {
            String serverMsg =
                    "{\"sessionResumptionUpdate\":{\"newHandle\":\"handle-123\",\"resumable\":true}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.SESSION_RESUMPTION, event.type());
            assertEquals("handle-123", event.getMetadata("live.session.resumption_handle"));
            assertEquals("true", event.getMetadata("live.session.resumable"));
        }

        @Test
        @DisplayName("Should parse goAway event")
        void shouldParseGoAwayEvent() {
            String serverMsg = "{\"goAway\":{\"timeLeft\":{\"seconds\":60}}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.GO_AWAY, event.type());
            assertEquals("60000", event.getMetadata("live.go_away.time_left_ms"));
        }

        @Test
        @DisplayName("Should parse usageMetadata event")
        void shouldParseUsageMetadataEvent() {
            String serverMsg = "{\"usageMetadata\":{\"totalTokenCount\":100}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.USAGE_METADATA, event.type());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Content Events")
    class ContentEventsParsingTests {

        @Test
        @DisplayName("Should parse audio output")
        void shouldParseAudioOutput() {
            String serverMsg =
                    "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AQIDBA==\"}}]}}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.AUDIO_DELTA, event.type());
            assertFalse(event.isLast());
            assertNotNull(event.message());
        }

        @Test
        @DisplayName("Should parse text output")
        void shouldParseTextOutput() {
            String serverMsg =
                    "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"text\":\"Hello world\"}]}}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TEXT_DELTA, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse turnComplete event")
        void shouldParseTurnCompleteEvent() {
            String serverMsg = "{\"serverContent\":{\"turnComplete\":true}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TURN_COMPLETE, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse generationComplete event")
        void shouldParseGenerationCompleteEvent() {
            String serverMsg = "{\"serverContent\":{\"generationComplete\":true}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.GENERATION_COMPLETE, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse interrupted event")
        void shouldParseInterruptedEvent() {
            String serverMsg = "{\"serverContent\":{\"interrupted\":true}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.INTERRUPTED, event.type());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Transcription Events")
    class TranscriptionEventsParsingTests {

        @Test
        @DisplayName("Should parse inputTranscription event")
        void shouldParseInputTranscriptionEvent() {
            String serverMsg =
                    "{\"serverContent\":{\"inputTranscription\":{\"text\":\"User said hello\"}}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.INPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse outputTranscription event")
        void shouldParseOutputTranscriptionEvent() {
            String serverMsg =
                    "{\"serverContent\":{\"outputTranscription\":{\"text\":\"Model response\"}}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Tool Events")
    class ToolEventsParsingTests {

        @Test
        @DisplayName("Should parse toolCall event")
        void shouldParseToolCallEvent() {
            String serverMsg =
                    "{\"toolCall\":{\"functionCalls\":[{\"id\":\"call_123\",\"name\":\"get_weather\",\"args\":{\"location\":\"Beijing\"}}]}}";

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

        @Test
        @DisplayName("Should parse toolCallCancellation event")
        void shouldParseToolCallCancellationEvent() {
            String serverMsg = "{\"toolCallCancellation\":{\"ids\":[\"call_123\",\"call_456\"]}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.TOOL_CALL_CANCELLATION, event.type());
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests - Error Events")
    class ErrorEventsParsingTests {

        @Test
        @DisplayName("Should parse error event")
        void shouldParseErrorEvent() {
            String serverMsg =
                    "{\"error\":{\"code\":\"invalid_request\",\"message\":\"Bad request\"}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.ERROR, event.type());
            assertTrue(event.isError());
        }

        @Test
        @DisplayName("Should handle unknown event type")
        void shouldHandleUnknownEventType() {
            String serverMsg = "{\"unknownEvent\":{\"data\":\"test\"}}";

            LiveEvent event = formatter.parseOutput(serverMsg);

            assertEquals(LiveEventType.UNKNOWN, event.type());
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create formatter with default values")
        void shouldCreateFormatterWithDefaults() {
            GeminiLiveFormatter defaultFormatter = GeminiLiveFormatter.builder().build();

            assertNotNull(defaultFormatter);
        }

        @Test
        @DisplayName("Should create formatter with all custom values")
        void shouldCreateFormatterWithAllCustomValues() {
            GeminiLiveFormatter customFormatter =
                    GeminiLiveFormatter.builder()
                            .proactiveAudio(true)
                            .affectiveDialog(true)
                            .enableThinking(true)
                            .thinkingBudget(2048)
                            .contextWindowCompression(true)
                            .slidingWindowTokens(20000)
                            .sessionResumption(true)
                            .sessionResumptionHandle("handle-xyz")
                            .activityHandling("START_OF_ACTIVITY_INTERRUPTS")
                            .mediaResolution("MEDIA_RESOLUTION_LOW")
                            .startOfSpeechSensitivity(GeminiLiveFormatter.SpeechSensitivity.LOW)
                            .endOfSpeechSensitivity(GeminiLiveFormatter.SpeechSensitivity.HIGH)
                            .silenceDurationMs(2000)
                            .prefixPaddingMs(500)
                            .build();

            assertNotNull(customFormatter);
        }
    }
}
