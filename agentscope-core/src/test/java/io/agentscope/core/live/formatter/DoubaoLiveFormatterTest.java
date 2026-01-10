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
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ControlBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.RawSource;
import io.agentscope.core.message.TextBlock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DoubaoLiveFormatter Tests")
class DoubaoLiveFormatterTest {

    private DoubaoLiveFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter =
                DoubaoLiveFormatter.builder()
                        .appId("test_app_id")
                        .token("test_token")
                        .uid("user_123")
                        .botName("test_bot")
                        .voiceType("zh_female_shuangkuaisisi_moon_bigtts")
                        .endSmoothWindowMs(2000)
                        .outputAudioFormat("ogg_opus")
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

            byte[] result = formatter.formatInput(msg);

            assertNotNull(result);
            assertTrue(result.length > 8);

            // Verify event_id = 200 (AUDIO_ONLY_REQUEST)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(200, eventId);
        }

        @Test
        @DisplayName("Should format text input")
        void shouldFormatTextInput() {
            Msg msg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(TextBlock.builder().text("Hello").build())
                            .build();

            byte[] result = formatter.formatInput(msg);

            assertNotNull(result);

            // Verify event_id = 501 (CHAT_TEXT_QUERY)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(501, eventId);
        }

        @Test
        @DisplayName("Should return null for control signals (not supported)")
        void shouldReturnNullForControlSignals() {
            Msg msg = Msg.builder().role(MsgRole.CONTROL).content(ControlBlock.interrupt()).build();

            byte[] result = formatter.formatInput(msg);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for null message")
        void shouldReturnNullForNullMessage() {
            byte[] result = formatter.formatInput(null);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Session Config Tests")
    class SessionConfigTests {

        @Test
        @DisplayName("Should build session config (StartConnection)")
        void shouldBuildSessionConfig() {
            LiveConfig config = LiveConfig.defaults();

            byte[] result = formatter.buildSessionConfig(config, null);

            assertNotNull(result);

            // Verify event_id = 100 (START_CONNECTION)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(100, eventId);
        }

        @Test
        @DisplayName("Should build StartSession message")
        void shouldBuildStartSession() {
            LiveConfig config =
                    LiveConfig.builder().instructions("You are a helpful assistant.").build();

            byte[] result = formatter.buildStartSession(config, "dialog_123");

            assertNotNull(result);

            // Verify event_id = 110 (START_SESSION)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(110, eventId);
        }

        @Test
        @DisplayName("Should build FinishConnection message")
        void shouldBuildFinishConnection() {
            byte[] result = formatter.buildFinishConnection();

            assertNotNull(result);

            // Verify event_id = 102 (FINISH_CONNECTION)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(102, eventId);
        }
    }

    @Nested
    @DisplayName("Output Parsing Tests")
    class OutputParsingTests {

        @Test
        @DisplayName("Should parse ConnectionStarted")
        void shouldParseConnectionStarted() {
            String payload = "{\"connection_id\":\"conn_123\"}";
            byte[] frame = createFrame(101, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.CONNECTION_STATE, event.type());
        }

        @Test
        @DisplayName("Should parse SessionStarted with dialog_id")
        void shouldParseSessionStartedWithDialogId() {
            String payload = "{\"session_id\":\"sess_123\",\"dialog_id\":\"dialog_456\"}";
            byte[] frame = createFrame(150, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.SESSION_RESUMPTION, event.type());
            assertEquals("dialog_456", event.getMetadata("live.session.resumption_handle"));
            assertEquals("dialog_456", formatter.getCurrentDialogId());
        }

        @Test
        @DisplayName("Should parse SessionStarted without dialog_id")
        void shouldParseSessionStartedWithoutDialogId() {
            String payload = "{\"session_id\":\"sess_123\"}";
            byte[] frame = createFrame(150, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.SESSION_CREATED, event.type());
        }

        @Test
        @DisplayName("Should parse TTS audio response")
        void shouldParseTtsAudioResponse() {
            byte[] audioData = new byte[] {1, 2, 3, 4};
            byte[] frame = createFrame(352, audioData);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.AUDIO_DELTA, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse ASR response with final flag")
        void shouldParseAsrResponseFinal() {
            String payload = "{\"text\":\"Hello world\",\"is_final\":true}";
            byte[] frame = createFrame(451, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.INPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse ASR response partial")
        void shouldParseAsrResponsePartial() {
            String payload = "{\"text\":\"Hello\",\"is_final\":false}";
            byte[] frame = createFrame(451, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.INPUT_TRANSCRIPTION, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse ASR info (speech started)")
        void shouldParseAsrInfo() {
            String payload = "{\"type\":\"first_word\"}";
            byte[] frame = createFrame(450, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.SPEECH_STARTED, event.type());
        }

        @Test
        @DisplayName("Should parse ASR ended (speech stopped)")
        void shouldParseAsrEnded() {
            byte[] frame = createFrame(459, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.SPEECH_STOPPED, event.type());
        }

        @Test
        @DisplayName("Should parse Chat response")
        void shouldParseChatResponse() {
            String payload = "{\"text\":\"Hello, how can I help?\"}";
            byte[] frame = createFrame(550, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.TEXT_DELTA, event.type());
        }

        @Test
        @DisplayName("Should parse TTS sentence start")
        void shouldParseTtsSentenceStart() {
            String payload = "{\"tts_type\":\"default\"}";
            byte[] frame = createFrame(350, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertFalse(event.isLast());
        }

        @Test
        @DisplayName("Should parse TTS sentence end")
        void shouldParseTtsSentenceEnd() {
            byte[] frame = createFrame(351, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.OUTPUT_TRANSCRIPTION, event.type());
            assertTrue(event.isLast());
        }

        @Test
        @DisplayName("Should parse TTS ended")
        void shouldParseTtsEnded() {
            byte[] frame = createFrame(359, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.TURN_COMPLETE, event.type());
        }

        @Test
        @DisplayName("Should parse Chat ended")
        void shouldParseChatEnded() {
            byte[] frame = createFrame(559, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.GENERATION_COMPLETE, event.type());
        }

        @Test
        @DisplayName("Should parse Session failed with error code")
        void shouldParseSessionFailed() {
            String payload = "{\"error_code\":45000003,\"error_message\":\"Silence timeout\"}";
            byte[] frame = createFrame(153, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.ERROR, event.type());
            assertTrue(event.isError());
        }

        @Test
        @DisplayName("Should parse Dialog common error")
        void shouldParseDialogCommonError() {
            String payload = "{\"error_code\":55000001,\"error_message\":\"Server processing\"}";
            byte[] frame = createFrame(599, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.ERROR, event.type());
            assertTrue(event.isError());
        }

        @Test
        @DisplayName("Should parse Usage response")
        void shouldParseUsageResponse() {
            String payload = "{\"input_tokens\":100,\"output_tokens\":50}";
            byte[] frame = createFrame(154, payload.getBytes(StandardCharsets.UTF_8));

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.USAGE_METADATA, event.type());
        }

        @Test
        @DisplayName("Should parse Connection finished")
        void shouldParseConnectionFinished() {
            byte[] frame = createFrame(103, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.SESSION_ENDED, event.type());
        }

        @Test
        @DisplayName("Should handle unknown event type")
        void shouldHandleUnknownEventType() {
            byte[] frame = createFrame(999, null);

            LiveEvent event = formatter.parseOutput(frame);

            assertEquals(LiveEventType.UNKNOWN, event.type());
        }

        @Test
        @DisplayName("Should handle invalid frame")
        void shouldHandleInvalidFrame() {
            byte[] invalidFrame = new byte[] {1, 2, 3}; // Too short

            LiveEvent event = formatter.parseOutput(invalidFrame);

            assertEquals(LiveEventType.UNKNOWN, event.type());
        }
    }

    @Nested
    @DisplayName("Special Methods Tests")
    class SpecialMethodsTests {

        @Test
        @DisplayName("Should format RAG text")
        void shouldFormatRagText() {
            byte[] result = formatter.formatRagText("External knowledge content");

            assertNotNull(result);

            // Verify event_id = 502 (CHAT_RAG_TEXT)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(502, eventId);
        }

        @Test
        @DisplayName("Should format TTS text")
        void shouldFormatTtsText() {
            byte[] result = formatter.formatTtsText("Text to synthesize");

            assertNotNull(result);

            // Verify event_id = 500 (CHAT_TTS_TEXT)
            ByteBuffer buffer = ByteBuffer.wrap(result);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.getInt(); // skip header
            int eventId = buffer.getInt();
            assertEquals(500, eventId);
        }
    }

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("Should create formatter with default values")
        void shouldCreateFormatterWithDefaults() {
            DoubaoLiveFormatter defaultFormatter = DoubaoLiveFormatter.builder().build();

            assertNotNull(defaultFormatter);
        }

        @Test
        @DisplayName("Should create formatter with all custom values")
        void shouldCreateFormatterWithAllCustomValues() {
            DoubaoLiveFormatter customFormatter =
                    DoubaoLiveFormatter.builder()
                            .appId("custom_app")
                            .token("custom_token")
                            .uid("custom_user")
                            .botName("custom_bot")
                            .voiceType("custom_voice")
                            .endSmoothWindowMs(3000)
                            .outputAudioFormat("pcm")
                            .build();

            assertNotNull(customFormatter);
        }
    }

    /**
     * Creates a test binary frame.
     *
     * @param eventId the event ID
     * @param payload the payload bytes
     * @return encoded frame bytes
     */
    private byte[] createFrame(int eventId, byte[] payload) {
        int payloadLength = payload != null ? payload.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(8 + payloadLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Header
        buffer.put((byte) 0x11);
        buffer.put((byte) 0x10);
        buffer.put((byte) 0x01);
        buffer.put((byte) 0x00);

        // Event ID
        buffer.putInt(eventId);

        // Payload
        if (payload != null) {
            buffer.put(payload);
        }

        return buffer.array();
    }
}
