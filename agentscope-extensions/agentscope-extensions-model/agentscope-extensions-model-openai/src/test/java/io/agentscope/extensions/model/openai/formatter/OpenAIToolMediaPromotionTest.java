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
package io.agentscope.extensions.model.openai.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Chat Completions only accepts media parts in user messages. A tool result that carries an image
 * therefore has to become two messages, otherwise the request body is invalid and providers either
 * reject it or silently ignore the media.
 */
class OpenAIToolMediaPromotionTest {

    @Test
    void toolImageIsPromotedToAFollowingUserMessage() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();

        ImageBlock image =
                ImageBlock.builder()
                        .source(
                                Base64Source.builder()
                                        .data("iVBORw0KGgoAAAANSUhEUg==")
                                        .mediaType("image/png")
                                        .build())
                        .build();
        ToolResultBlock result =
                new ToolResultBlock(
                        "call_123",
                        "screenshot_tool",
                        List.of(TextBlock.builder().text("Screenshot taken").build(), image),
                        null);
        Msg toolMessage = Msg.builder().role(MsgRole.TOOL).content(List.of(result)).build();

        List<OpenAIMessage> messages = formatter.format(List.of(toolMessage));

        assertEquals(
                2,
                messages.size(),
                "a tool result with media must expand into a tool message and a user message");

        OpenAIMessage tool = messages.get(0);
        assertEquals("tool", tool.getRole());
        assertEquals("call_123", tool.getToolCallId());
        assertFalse(tool.isMultimodal(), "a tool message must not carry media parts");
        assertTrue(tool.getContentAsString().contains("Screenshot taken"));

        OpenAIMessage media = messages.get(1);
        assertEquals("user", media.getRole());
        assertTrue(media.isMultimodal(), "the media must follow in a user message");
    }

    @Test
    void textOnlyToolResultStaysASingleMessage() {
        OpenAIChatFormatter formatter = new OpenAIChatFormatter();
        ToolResultBlock result =
                new ToolResultBlock(
                        "call_1",
                        "echo",
                        List.<ContentBlock>of(TextBlock.builder().text("ok").build()),
                        null);
        Msg toolMessage = Msg.builder().role(MsgRole.TOOL).content(List.of(result)).build();

        List<OpenAIMessage> messages = formatter.format(List.of(toolMessage));

        assertEquals(1, messages.size());
        assertEquals("tool", messages.get(0).getRole());
        assertEquals("ok", messages.get(0).getContentAsString());
    }
}
