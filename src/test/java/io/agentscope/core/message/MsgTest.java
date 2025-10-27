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
package io.agentscope.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(textBlock, msg.getFirstContentBlock());
    }

    @Test
    void testBuilderWithTextBlock() {
        TextBlock textBlock = TextBlock.builder().text("Hello World").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(textBlock).build();

        assertTrue(msg.getFirstContentBlock() instanceof TextBlock);
        assertEquals("Hello World", ((TextBlock) msg.getFirstContentBlock()).getText());
    }

    @Test
    void testBuilderWithImageBlock() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(imageBlock).build();

        assertTrue(msg.getFirstContentBlock() instanceof ImageBlock);
    }

    @Test
    void testBuilderWithAudioBlock() {
        Base64Source base64Source =
                Base64Source.builder().mediaType("audio/mp3").data("base64audiodata").build();
        AudioBlock audioBlock = AudioBlock.builder().source(base64Source).build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(audioBlock).build();

        assertTrue(msg.getFirstContentBlock() instanceof AudioBlock);
    }

    @Test
    void testBuilderWithVideoBlock() {
        URLSource urlSource = URLSource.builder().url("https://example.com/video.mp4").build();
        VideoBlock videoBlock = VideoBlock.builder().source(urlSource).build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(videoBlock).build();

        assertTrue(msg.getFirstContentBlock() instanceof VideoBlock);
    }

    @Test
    void testBuilderWithThinkingBlock() {
        ThinkingBlock thinkingBlock =
                ThinkingBlock.builder().text("Let me think about this...").build();
        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(thinkingBlock)
                        .build();

        assertTrue(msg.getFirstContentBlock() instanceof ThinkingBlock);
        assertEquals(
                "Let me think about this...",
                ((ThinkingBlock) msg.getFirstContentBlock()).getThinking());
    }

    @Test
    void testBuilderPattern() {
        // Test that builder methods can be chained
        TextBlock textBlock = TextBlock.builder().text("System message").build();
        Msg msg = Msg.builder().name("test").role(MsgRole.SYSTEM).content(textBlock).build();

        assertNotNull(msg);
        assertEquals("test", msg.getName());
        assertEquals(MsgRole.SYSTEM, msg.getRole());
        assertEquals("System message", ((TextBlock) msg.getFirstContentBlock()).getText());
    }
}
