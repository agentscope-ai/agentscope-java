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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.live.LiveEvent;
import io.agentscope.core.live.config.LiveConfig;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AbstractBinaryLiveFormatter Tests")
class AbstractBinaryLiveFormatterTest {

    /** Test implementation for AbstractBinaryLiveFormatter. */
    static class TestBinaryFormatter extends AbstractBinaryLiveFormatter {
        @Override
        public byte[] formatInput(Msg msg) {
            return encodeFrame(1, stringToPayload("test"));
        }

        @Override
        public LiveEvent parseOutput(byte[] data) {
            DecodedFrame frame = decodeFrame(data);
            if (frame != null) {
                return LiveEvent.unknown("binary", payloadToString(frame.payload()));
            }
            return null;
        }

        @Override
        public byte[] buildSessionConfig(LiveConfig config, List<ToolSchema> toolSchemas) {
            return encodeFrame(0, stringToPayload("config"));
        }

        // Expose protected methods for testing
        public byte[] testEncodeFrame(int eventId, byte[] payload) {
            return encodeFrame(eventId, payload);
        }

        public DecodedFrame testDecodeFrame(byte[] data) {
            return decodeFrame(data);
        }

        public String testPayloadToString(byte[] payload) {
            return payloadToString(payload);
        }

        public byte[] testStringToPayload(String str) {
            return stringToPayload(str);
        }
    }

    @Test
    @DisplayName("Should encode and decode frame correctly")
    void shouldEncodeAndDecodeFrameCorrectly() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();
        byte[] payload = "hello".getBytes();
        int eventId = 42;

        byte[] encoded = formatter.testEncodeFrame(eventId, payload);
        AbstractBinaryLiveFormatter.DecodedFrame decoded = formatter.testDecodeFrame(encoded);

        assertNotNull(decoded);
        assertEquals(eventId, decoded.eventId());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    @DisplayName("Should handle null payload in frame")
    void shouldHandleNullPayloadInFrame() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();
        int eventId = 1;

        byte[] encoded = formatter.testEncodeFrame(eventId, null);
        AbstractBinaryLiveFormatter.DecodedFrame decoded = formatter.testDecodeFrame(encoded);

        assertNotNull(decoded);
        assertEquals(eventId, decoded.eventId());
        assertNull(decoded.payload());
    }

    @Test
    @DisplayName("Should return null for invalid frame data")
    void shouldReturnNullForInvalidFrameData() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        // Too short data
        AbstractBinaryLiveFormatter.DecodedFrame decoded =
                formatter.testDecodeFrame(new byte[] {1, 2, 3});

        assertNull(decoded);
    }

    @Test
    @DisplayName("Should return null for null frame data")
    void shouldReturnNullForNullFrameData() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        AbstractBinaryLiveFormatter.DecodedFrame decoded = formatter.testDecodeFrame(null);

        assertNull(decoded);
    }

    @Test
    @DisplayName("Should convert string to payload and back")
    void shouldConvertStringToPayloadAndBack() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();
        String original = "test message";

        byte[] payload = formatter.testStringToPayload(original);
        String result = formatter.testPayloadToString(payload);

        assertEquals(original, result);
    }

    @Test
    @DisplayName("Should handle null string to payload")
    void shouldHandleNullStringToPayload() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        byte[] result = formatter.testStringToPayload(null);

        assertNull(result);
    }

    @Test
    @DisplayName("Should handle null payload to string")
    void shouldHandleNullPayloadToString() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        String result = formatter.testPayloadToString(null);

        assertNull(result);
    }

    @Test
    @DisplayName("Should format input message")
    void shouldFormatInputMessage() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        byte[] result = formatter.formatInput(null);

        assertNotNull(result);
    }

    @Test
    @DisplayName("Should parse output data")
    void shouldParseOutputData() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();
        byte[] data = formatter.testEncodeFrame(1, "test".getBytes());

        LiveEvent event = formatter.parseOutput(data);

        assertNotNull(event);
    }

    @Test
    @DisplayName("Should build session config")
    void shouldBuildSessionConfig() {
        TestBinaryFormatter formatter = new TestBinaryFormatter();

        byte[] result = formatter.buildSessionConfig(LiveConfig.defaults(), null);

        assertNotNull(result);
    }
}
