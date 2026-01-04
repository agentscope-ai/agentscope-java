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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
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
                ThinkingBlock.builder().thinking("Let me think about this...").build();
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

    @Test
    void testGetTextContentWithSingleTextBlock() {
        TextBlock textBlock = TextBlock.builder().text("Hello, world!").build();
        Msg msg = Msg.builder().name("user").role(MsgRole.USER).content(textBlock).build();

        assertEquals("Hello, world!", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithMultipleTextBlocks() {
        TextBlock textBlock1 = TextBlock.builder().text("First line").build();
        TextBlock textBlock2 = TextBlock.builder().text("Second line").build();
        TextBlock textBlock3 = TextBlock.builder().text("Third line").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(textBlock1, textBlock2, textBlock3)
                        .build();

        assertEquals("First line\nSecond line\nThird line", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithMixedContentTypes() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();
        TextBlock textBlock1 = TextBlock.builder().text("Here's an image for you:").build();
        TextBlock textBlock2 = TextBlock.builder().text("I hope you like it!").build();

        Msg msg =
                Msg.builder()
                        .name("multimodal-assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(textBlock1, imageBlock, textBlock2)
                        .build();

        assertEquals("Here's an image for you:\nI hope you like it!", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithNoTextBlocks() {
        URLSource urlSource = URLSource.builder().url("https://example.com/image.jpg").build();
        ImageBlock imageBlock = ImageBlock.builder().source(urlSource).build();

        Msg msg =
                Msg.builder().name("image-bot").role(MsgRole.ASSISTANT).content(imageBlock).build();

        assertEquals("", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithEmptyMessage() {
        Msg msg = Msg.builder().name("empty-bot").role(MsgRole.ASSISTANT).build();

        assertEquals("", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithToolBlocks() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("tool-123")
                        .name("calculator")
                        .input(Map.of("x", 5, "y", 3))
                        .build();
        TextBlock textBlock = TextBlock.builder().text("I'll calculate that for you.").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(textBlock, toolUseBlock)
                        .build();

        assertEquals("I'll calculate that for you.", msg.getTextContent());
    }

    @Test
    void testGetTextContentWithEmptyTextBlock() {
        TextBlock emptyTextBlock = TextBlock.builder().text("").build();
        TextBlock normalTextBlock = TextBlock.builder().text("Normal text").build();

        Msg msg =
                Msg.builder()
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(emptyTextBlock, normalTextBlock)
                        .build();

        assertEquals("\nNormal text", msg.getTextContent());
    }

    @Test
    void testTextContentConvenienceMethod() {
        // Test the convenience method for setting text content directly
        Msg msg = Msg.builder().name("user").textContent("Hello World").build();

        assertEquals("user", msg.getName());
        assertEquals(MsgRole.USER, msg.getRole());
        assertEquals("Hello World", msg.getTextContent());
        assertTrue(msg.getFirstContentBlock() instanceof TextBlock);
        assertEquals("Hello World", ((TextBlock) msg.getFirstContentBlock()).getText());
    }
}
