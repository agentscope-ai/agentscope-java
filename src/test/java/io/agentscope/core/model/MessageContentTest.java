/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for MessageContent interface and ContentKind enum.
 */
@Tag("unit")
@DisplayName("MessageContent Tests")
class MessageContentTest {

    @Test
    @DisplayName("Should have all ContentKind enum values")
    void testContentKindEnumValues() {
        MessageContent.ContentKind[] values = MessageContent.ContentKind.values();

        assertNotNull(values);
        assertEquals(4, values.length);

        assertTrue(containsValue(values, MessageContent.ContentKind.TEXT));
        assertTrue(containsValue(values, MessageContent.ContentKind.IMAGE));
        assertTrue(containsValue(values, MessageContent.ContentKind.AUDIO));
        assertTrue(containsValue(values, MessageContent.ContentKind.VIDEO));
    }

    @Test
    @DisplayName("Should get ContentKind by name")
    void testContentKindValueOf() {
        assertEquals(MessageContent.ContentKind.TEXT, MessageContent.ContentKind.valueOf("TEXT"));
        assertEquals(MessageContent.ContentKind.IMAGE, MessageContent.ContentKind.valueOf("IMAGE"));
        assertEquals(MessageContent.ContentKind.AUDIO, MessageContent.ContentKind.valueOf("AUDIO"));
        assertEquals(MessageContent.ContentKind.VIDEO, MessageContent.ContentKind.valueOf("VIDEO"));
    }

    @Test
    @DisplayName("Should throw exception for invalid ContentKind name")
    void testContentKindValueOfInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    MessageContent.ContentKind.valueOf("INVALID");
                });
    }

    @Test
    @DisplayName("Should have correct enum ordinal values")
    void testContentKindOrdinals() {
        assertEquals(0, MessageContent.ContentKind.TEXT.ordinal());
        assertEquals(1, MessageContent.ContentKind.IMAGE.ordinal());
        assertEquals(2, MessageContent.ContentKind.AUDIO.ordinal());
        assertEquals(3, MessageContent.ContentKind.VIDEO.ordinal());
    }

    @Test
    @DisplayName("Should have correct enum names")
    void testContentKindNames() {
        assertEquals("TEXT", MessageContent.ContentKind.TEXT.name());
        assertEquals("IMAGE", MessageContent.ContentKind.IMAGE.name());
        assertEquals("AUDIO", MessageContent.ContentKind.AUDIO.name());
        assertEquals("VIDEO", MessageContent.ContentKind.VIDEO.name());
    }

    @Test
    @DisplayName("Should support enum comparison")
    void testContentKindComparison() {
        assertTrue(MessageContent.ContentKind.TEXT == MessageContent.ContentKind.TEXT);
        assertFalse(MessageContent.ContentKind.TEXT == MessageContent.ContentKind.IMAGE);

        assertTrue(MessageContent.ContentKind.TEXT.equals(MessageContent.ContentKind.TEXT));
        assertFalse(MessageContent.ContentKind.TEXT.equals(MessageContent.ContentKind.IMAGE));
    }

    @Test
    @DisplayName("Should support enum in switch statement")
    void testContentKindSwitch() {
        String result = getContentKindDescription(MessageContent.ContentKind.TEXT);
        assertEquals("Text content", result);

        result = getContentKindDescription(MessageContent.ContentKind.IMAGE);
        assertEquals("Image content", result);

        result = getContentKindDescription(MessageContent.ContentKind.AUDIO);
        assertEquals("Audio content", result);

        result = getContentKindDescription(MessageContent.ContentKind.VIDEO);
        assertEquals("Video content", result);
    }

    @Test
    @DisplayName("Should create MessageContent implementation")
    void testMessageContentImplementation() {
        MessageContent textContent = new TestMessageContent(MessageContent.ContentKind.TEXT);
        assertEquals(MessageContent.ContentKind.TEXT, textContent.getKind());

        MessageContent imageContent = new TestMessageContent(MessageContent.ContentKind.IMAGE);
        assertEquals(MessageContent.ContentKind.IMAGE, imageContent.getKind());

        MessageContent audioContent = new TestMessageContent(MessageContent.ContentKind.AUDIO);
        assertEquals(MessageContent.ContentKind.AUDIO, audioContent.getKind());

        MessageContent videoContent = new TestMessageContent(MessageContent.ContentKind.VIDEO);
        assertEquals(MessageContent.ContentKind.VIDEO, videoContent.getKind());
    }

    private boolean containsValue(
            MessageContent.ContentKind[] values, MessageContent.ContentKind target) {
        for (MessageContent.ContentKind value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private String getContentKindDescription(MessageContent.ContentKind kind) {
        switch (kind) {
            case TEXT:
                return "Text content";
            case IMAGE:
                return "Image content";
            case AUDIO:
                return "Audio content";
            case VIDEO:
                return "Video content";
            default:
                return "Unknown content";
        }
    }

    private static class TestMessageContent implements MessageContent {
        private final ContentKind kind;

        TestMessageContent(ContentKind kind) {
            this.kind = kind;
        }

        @Override
        public ContentKind getKind() {
            return kind;
        }
    }
}
