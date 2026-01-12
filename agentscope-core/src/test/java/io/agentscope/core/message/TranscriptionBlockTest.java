/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TranscriptionBlock Tests")
class TranscriptionBlockTest {

    @Test
    @DisplayName("Should create input transcription")
    void shouldCreateInputTranscription() {
        TranscriptionBlock block = TranscriptionBlock.input("Hello world");

        assertEquals("Hello world", block.getText());
        assertEquals(TranscriptionType.INPUT, block.getType());
        assertFalse(block.isPartial());
        assertNull(block.getLanguage());
        assertNull(block.getConfidence());
    }

    @Test
    @DisplayName("Should create output transcription")
    void shouldCreateOutputTranscription() {
        TranscriptionBlock block = TranscriptionBlock.output("Hi there");

        assertEquals("Hi there", block.getText());
        assertEquals(TranscriptionType.OUTPUT, block.getType());
        assertFalse(block.isPartial());
    }

    @Test
    @DisplayName("Should create partial input transcription")
    void shouldCreatePartialInputTranscription() {
        TranscriptionBlock block = TranscriptionBlock.inputPartial("Hel");

        assertEquals("Hel", block.getText());
        assertEquals(TranscriptionType.INPUT, block.getType());
        assertTrue(block.isPartial());
    }

    @Test
    @DisplayName("Should create partial output transcription")
    void shouldCreatePartialOutputTranscription() {
        TranscriptionBlock block = TranscriptionBlock.outputPartial("Hi");

        assertEquals("Hi", block.getText());
        assertEquals(TranscriptionType.OUTPUT, block.getType());
        assertTrue(block.isPartial());
    }

    @Test
    @DisplayName("Should build with all fields")
    void shouldBuildWithAllFields() {
        TranscriptionBlock block =
                TranscriptionBlock.builder()
                        .text("你好")
                        .type(TranscriptionType.INPUT)
                        .partial(false)
                        .language("zh")
                        .confidence(0.95f)
                        .build();

        assertEquals("你好", block.getText());
        assertEquals(TranscriptionType.INPUT, block.getType());
        assertFalse(block.isPartial());
        assertEquals("zh", block.getLanguage());
        assertEquals(0.95f, block.getConfidence());
    }

    @Test
    @DisplayName("Should throw on null text")
    void shouldThrowOnNullText() {
        assertThrows(NullPointerException.class, () -> TranscriptionBlock.input(null));
    }

    @Test
    @DisplayName("Should throw on null type")
    void shouldThrowOnNullType() {
        assertThrows(
                NullPointerException.class,
                () -> new TranscriptionBlock("text", null, false, null, null));
    }

    @Test
    @DisplayName("Should implement equals correctly")
    void shouldImplementEqualsCorrectly() {
        TranscriptionBlock block1 = TranscriptionBlock.input("Hello");
        TranscriptionBlock block2 = TranscriptionBlock.input("Hello");
        TranscriptionBlock block3 = TranscriptionBlock.output("Hello");

        assertEquals(block1, block2);
        assertNotEquals(block1, block3);
    }

    @Test
    @DisplayName("Should implement hashCode correctly")
    void shouldImplementHashCodeCorrectly() {
        TranscriptionBlock block1 = TranscriptionBlock.input("Hello");
        TranscriptionBlock block2 = TranscriptionBlock.input("Hello");

        assertEquals(block1.hashCode(), block2.hashCode());
    }

    @Test
    @DisplayName("Should implement toString correctly")
    void shouldImplementToStringCorrectly() {
        TranscriptionBlock block =
                TranscriptionBlock.builder()
                        .text("Hello")
                        .type(TranscriptionType.INPUT)
                        .language("en")
                        .confidence(0.9f)
                        .build();

        String str = block.toString();
        assertTrue(str.contains("TranscriptionBlock"));
        assertTrue(str.contains("Hello"));
        assertTrue(str.contains("INPUT"));
        assertTrue(str.contains("en"));
        assertTrue(str.contains("0.9"));
    }

    @Test
    @DisplayName("Should be usable with Msg builder")
    void shouldBeUsableWithMsgBuilder() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TranscriptionBlock.output("Hello"))
                        .build();

        assertEquals(MsgRole.ASSISTANT, msg.getRole());
        assertFalse(msg.getContent().isEmpty());
        assertInstanceOf(TranscriptionBlock.class, msg.getContent().get(0));

        TranscriptionBlock block = (TranscriptionBlock) msg.getContent().get(0);
        assertEquals(TranscriptionType.OUTPUT, block.getType());
        assertEquals("Hello", block.getText());
    }
}
