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
package io.agentscope.core.live;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LiveProtocolException Tests")
class LiveProtocolExceptionTest {

    @Test
    @DisplayName("Should create exception with message and cause")
    void shouldCreateExceptionWithMessageAndCause() {
        byte[] rawData = "test data".getBytes(StandardCharsets.UTF_8);
        RuntimeException cause = new RuntimeException("Parse error");

        LiveProtocolException exception =
                new LiveProtocolException("Failed to parse", cause, rawData);

        assertTrue(exception.getMessage().contains("Failed to parse"));
        assertSame(cause, exception.getCause());
        assertArrayEquals(rawData, exception.getRawData());
    }

    @Test
    @DisplayName("Should create exception without cause")
    void shouldCreateExceptionWithoutCause() {
        byte[] rawData = "test data".getBytes(StandardCharsets.UTF_8);

        LiveProtocolException exception = new LiveProtocolException("Failed to parse", rawData);

        assertTrue(exception.getMessage().contains("Failed to parse"));
        assertNull(exception.getCause());
        assertArrayEquals(rawData, exception.getRawData());
    }

    @Test
    @DisplayName("Should include data preview in message")
    void shouldIncludeDataPreviewInMessage() {
        byte[] rawData = "{\"type\":\"error\"}".getBytes(StandardCharsets.UTF_8);

        LiveProtocolException exception = new LiveProtocolException("Parse failed", rawData);

        assertTrue(exception.getMessage().contains("{\"type\":\"error\"}"));
        assertEquals("{\"type\":\"error\"}", exception.getDataPreview());
    }

    @Test
    @DisplayName("Should truncate long data preview")
    void shouldTruncateLongDataPreview() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("x");
        }
        byte[] rawData = sb.toString().getBytes(StandardCharsets.UTF_8);

        LiveProtocolException exception = new LiveProtocolException("Parse failed", rawData);

        assertTrue(exception.getDataPreview().length() <= 203); // 200 + "..."
        assertTrue(exception.getDataPreview().endsWith("..."));
    }

    @Test
    @DisplayName("Should handle null raw data")
    void shouldHandleNullRawData() {
        LiveProtocolException exception = new LiveProtocolException("Parse failed", null);

        assertNull(exception.getRawData());
        assertEquals("<empty>", exception.getDataPreview());
    }

    @Test
    @DisplayName("Should handle empty raw data")
    void shouldHandleEmptyRawData() {
        LiveProtocolException exception = new LiveProtocolException("Parse failed", new byte[0]);

        assertNotNull(exception.getRawData());
        assertEquals(0, exception.getRawData().length);
        assertEquals("<empty>", exception.getDataPreview());
    }
}
