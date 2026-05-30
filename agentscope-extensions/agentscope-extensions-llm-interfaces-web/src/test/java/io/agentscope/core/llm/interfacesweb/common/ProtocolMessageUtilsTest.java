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
package io.agentscope.core.llm.interfacesweb.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolMessageUtils Tests")
class ProtocolMessageUtilsTest {

    @Test
    @DisplayName("Should create and read text messages safely")
    void shouldCreateAndReadTextMessagesSafely() {
        Msg text = ProtocolMessageUtils.textMessage(MsgRole.USER, null);

        assertEquals(MsgRole.USER, text.getRole());
        assertEquals("", ProtocolMessageUtils.textContent(text));
        assertEquals("", ProtocolMessageUtils.textContent(null));
        assertTrue(ProtocolMessageUtils.contentParts(null, false).isEmpty());
        assertTrue(
                ProtocolMessageUtils.contentParts(Msg.builder().role(MsgRole.USER).build(), false)
                        .isEmpty());
    }

    @Test
    @DisplayName("Should convert content blocks to Responses-style parts")
    void shouldConvertContentBlocksToResponsesParts() {
        Msg message =
                ProtocolMessageUtils.message(
                        MsgRole.ASSISTANT,
                        List.of(
                                TextBlock.builder().text("hello").build(),
                                ThinkingBlock.builder().thinking("reasoning").build(),
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/a.png")
                                                        .build())
                                        .build(),
                                ToolUseBlock.builder()
                                        .id("call_1")
                                        .name("lookup")
                                        .input(Map.of("q", "Paris"))
                                        .build(),
                                ToolResultBlock.builder()
                                        .id("call_1")
                                        .name("lookup")
                                        .output(TextBlock.builder().text("Sunny").build())
                                        .build()));

        List<Map<String, Object>> parts = ProtocolMessageUtils.contentParts(message, false);

        assertEquals("hello", parts.get(0).get("text"));
        assertEquals("reasoning", parts.get(1).get("thinking"));
        assertEquals("input_image", parts.get(2).get("type"));
        assertEquals("https://example.com/a.png", parts.get(2).get("image_url"));
        assertEquals(Map.of("q", "Paris"), parts.get(3).get("input"));
        assertEquals("Sunny", parts.get(4).get("content"));
    }

    @Test
    @DisplayName("Should convert fallback content parts")
    void shouldConvertFallbackContentParts() {
        Msg message =
                ProtocolMessageUtils.message(
                        MsgRole.USER,
                        List.of(
                                ToolUseBlock.builder().id("call_1").name("lookup").build(),
                                ImageBlock.builder()
                                        .source(
                                                Base64Source.builder()
                                                        .mediaType("image/png")
                                                        .data("abc")
                                                        .build())
                                        .build(),
                                ImageBlock.builder().source(new Source()).build()));

        List<Map<String, Object>> parts = ProtocolMessageUtils.contentParts(message, false);

        assertEquals(Map.of(), parts.get(0).get("input"));
        assertEquals("data:image/png;base64,abc", parts.get(1).get("image_url"));
        assertEquals("[Unsupported image source]", parts.get(2).get("text"));
    }

    @Test
    @DisplayName("Should convert image blocks to Anthropic-style parts")
    void shouldConvertImageBlocksToAnthropicParts() {
        Msg message =
                ProtocolMessageUtils.message(
                        MsgRole.USER,
                        List.of(
                                ImageBlock.builder()
                                        .source(
                                                Base64Source.builder()
                                                        .mediaType("image/png")
                                                        .data("abc")
                                                        .build())
                                        .build(),
                                ImageBlock.builder()
                                        .source(
                                                URLSource.builder()
                                                        .url("https://example.com/a.png")
                                                        .build())
                                        .build()));

        List<Map<String, Object>> parts = ProtocolMessageUtils.contentParts(message, true);

        Map<?, ?> base64Source = (Map<?, ?>) parts.get(0).get("source");
        assertEquals("base64", base64Source.get("type"));
        assertEquals("image/png", base64Source.get("media_type"));
        assertEquals("abc", base64Source.get("data"));

        Map<?, ?> urlSource = (Map<?, ?>) parts.get(1).get("source");
        assertEquals("url", urlSource.get("type"));
        assertEquals("https://example.com/a.png", urlSource.get("url"));
    }

    @Test
    @DisplayName("Should handle tool result text fallbacks")
    void shouldHandleToolResultTextFallbacks() {
        assertEquals("", ProtocolMessageUtils.toolResultText(null));
        assertEquals(
                "",
                ProtocolMessageUtils.toolResultText(
                        ToolResultBlock.builder()
                                .output(ThinkingBlock.builder().thinking("internal").build())
                                .build()));
    }
}
