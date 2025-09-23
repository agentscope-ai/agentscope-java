/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MsgTest {

    @Test
    void testBasicMsgCreation() {
        TextBlock textBlock = TextBlock.builder().text("Hello World").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(textBlock).build();

        assertEquals("user", msg.getName());
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals(textBlock, msg.getContent());
    }

    @Test
    void testConvenienceMethodsForTextContent() {
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).textContent("Hello World").build();

        assertTrue(msg.hasTextContent());
        assertFalse(msg.hasMediaContent());
        assertEquals("Hello World", msg.getTextContent());
        assertEquals("Hello World", msg.getContentAsText());
        assertTrue(msg.getContent() instanceof TextBlock);
    }

    @Test
    void testConvenienceMethodsForImageContent() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).imageContent(urlSource).build();

        assertFalse(msg.hasTextContent());
        assertTrue(msg.hasMediaContent());
        assertEquals("", msg.getTextContent());
        assertTrue(msg.getContentAsText().contains("[Image content"));
        assertTrue(msg.getContent() instanceof ImageBlock);
    }

    @Test
    void testConvenienceMethodsForAudioContent() {
        Base64Source base64Source =
                Base64Source.builder().mediaType("audio/mp3").data("base64audiodata").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).audioContent(base64Source).build();

        assertFalse(msg.hasTextContent());
        assertTrue(msg.hasMediaContent());
        assertTrue(msg.getContentAsText().contains("[Audio content"));
        assertTrue(msg.getContent() instanceof AudioBlock);
    }

    @Test
    void testConvenienceMethodsForVideoContent() {
        URLSource urlSource = URLSource.builder().url("https://example.com/video.mp4").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).videoContent(urlSource).build();

        assertFalse(msg.hasTextContent());
        assertTrue(msg.hasMediaContent());
        assertTrue(msg.getContentAsText().contains("[Video content"));
        assertTrue(msg.getContent() instanceof VideoBlock);
    }

    @Test
    void testConvenienceMethodsForThinkingContent() {
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .thinkingContent("Let me think about this...")
                        .build();

        assertTrue(msg.hasTextContent());
        assertFalse(msg.hasMediaContent());
        assertEquals("Let me think about this...", msg.getTextContent());
        assertTrue(msg.getContent() instanceof ThinkingBlock);
    }

    @Test
    void testBuilderPattern() {
        // Test that builder methods can be chained
        Msg msg =
                Msg.builder()
                        .name("test")
                        .role(MsgRole.SYSTEM)
                        .textContent("System message")
                        .build();

        assertNotNull(msg);
        assertEquals("test", msg.getName());
        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("System message", msg.getTextContent());
    }
}
