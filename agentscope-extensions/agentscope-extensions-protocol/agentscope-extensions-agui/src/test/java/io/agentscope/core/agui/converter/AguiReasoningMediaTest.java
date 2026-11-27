/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.DocumentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AguiReasoningMediaTest {

    private static final String URL = "https://example.com/generated-output";
    private final AguiMessageConverter converter = new AguiMessageConverter();

    @ParameterizedTest
    @MethodSource("urlMedia")
    void reasoningWithMediaUsesProtocolValidTextContent(ContentBlock media, String mimeType) {
        List<AguiMessage> messages = converter.toAguiMessages(reasoningWith(media));

        assertEquals(2, messages.size());
        assertTrue(messages.get(0).isReasoningMessage());
        assertEquals("Generate the requested output", messages.get(0).getTextContent());
        assertTrue(messages.get(1).isAssistantMessage());
        assertFalse(messages.get(1).hasBlocks());
        String text = messages.get(1).getTextContent();
        assertTrue(text.contains(URL));
        assertTrue(text.contains(mimeType));
    }

    @Test
    void historyConversionKeepsTextReferenceAndFollowingMessages() {
        Msg media = reasoningWith(ImageBlock.builder().source(source("image/png")).build());
        Msg following =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("next").build())
                        .build();

        List<AguiMessage> messages = converter.toAguiMessageList(List.of(media, following));

        assertEquals(3, messages.size());
        assertTrue(messages.get(0).isReasoningMessage());
        assertTrue(messages.get(1).isAssistantMessage());
        assertFalse(messages.get(1).hasBlocks());
        assertTrue(messages.get(1).getTextContent().contains(URL));
        assertEquals("next", messages.get(2).getTextContent());
    }

    @Test
    void reasoningOnlyStillProducesOneReasoningMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("Only reasoning").build())
                        .build();

        List<AguiMessage> messages = converter.toAguiMessages(msg);

        assertEquals(1, messages.size());
        assertTrue(messages.get(0).isReasoningMessage());
        assertEquals("Only reasoning", messages.get(0).getTextContent());
    }

    private static Msg reasoningWith(ContentBlock media) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(
                        ThinkingBlock.builder().thinking("Generate the requested output").build(),
                        media)
                .build();
    }

    private static URLSource source(String mimeType) {
        return URLSource.builder().url(URL).mimeType(mimeType).build();
    }

    private static Stream<Arguments> urlMedia() {
        return Stream.of(
                Arguments.of(ImageBlock.builder().source(source("image/png")).build(), "image/png"),
                Arguments.of(
                        AudioBlock.builder().source(source("audio/mpeg")).build(), "audio/mpeg"),
                Arguments.of(VideoBlock.builder().source(source("video/mp4")).build(), "video/mp4"),
                Arguments.of(
                        DocumentBlock.builder().source(source("application/pdf")).build(),
                        "application/pdf"),
                Arguments.of(DataBlock.builder().source(source("image/png")).build(), "image/png"),
                Arguments.of(
                        DataBlock.builder().source(source("application/pdf")).build(),
                        "application/pdf"));
    }
}
