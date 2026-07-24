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
package io.agentscope.extensions.model.openai.compat.deepseek;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIFunction;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("Compact DeepSeekFormatter Unit Tests")
class DeepSeekFormatterTest {

    @Test
    @DisplayName("Formats all DeepSeek message roles")
    void formatsAllDeepSeekMessageRoles() {
        DeepSeekFormatter formatter = new DeepSeekFormatter();
        List<OpenAIMessage> messages =
                formatter.format(
                        List.of(
                                Msg.builder()
                                        .role(MsgRole.SYSTEM)
                                        .name("planner")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Keep replies short.")
                                                                .build()))
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.USER)
                                        .name("tester")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Find weather.")
                                                                .build()))
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .name("weather_agent")
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call_weather_1")
                                                                .name("get_weather")
                                                                .input(Map.of("city", "Beijing"))
                                                                .build()))
                                        .build(),
                                Msg.builder()
                                        .role(MsgRole.TOOL)
                                        .content(
                                                List.of(
                                                        new ToolResultBlock(
                                                                "call_weather_1",
                                                                "get_weather",
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text("Sunny")
                                                                                .build()),
                                                                null)))
                                        .build(),
                                Msg.builder().role(MsgRole.SYSTEM).content(List.of()).build()));

        assertEquals(5, messages.size());
        assertEquals("system", messages.get(0).getRole());
        assertEquals("planner", messages.get(0).getName());
        assertEquals("Keep replies short.", messages.get(0).getContentAsString());

        assertEquals("user", messages.get(1).getRole());
        assertEquals("tester", messages.get(1).getName());
        assertEquals("Find weather.", messages.get(1).getContentAsString());

        assertEquals("assistant", messages.get(2).getRole());
        assertEquals("weather_agent", messages.get(2).getName());
        assertEquals("", messages.get(2).getContentAsString());
        assertEquals(1, messages.get(2).getToolCalls().size());
        assertEquals("call_weather_1", messages.get(2).getToolCalls().get(0).getId());

        assertEquals("tool", messages.get(3).getRole());
        assertEquals("call_weather_1", messages.get(3).getToolCallId());
        assertEquals("Sunny", messages.get(3).getContentAsString());

        assertEquals("system", messages.get(4).getRole());
        assertNull(messages.get(4).getName());
        assertEquals("", messages.get(4).getContentAsString());
    }

    @Test
    @DisplayName("Appends empty user message only when enabled and ending with assistant")
    void appendsEmptyUserMessageOnlyWhenEnabledAndEndingWithAssistant() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Hello").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(List.of(TextBlock.builder().text("Hi").build()))
                                .build());

        assertEquals(2, new DeepSeekFormatter(false).format(messages).size());

        List<OpenAIMessage> withAppend = new DeepSeekFormatter(true).format(messages);

        assertEquals(3, withAppend.size());
        assertEquals("user", withAppend.get(2).getRole());
        assertEquals("", withAppend.get(2).getContentAsString());
        assertEquals(List.of(), DeepSeekFormatter.appendEmptyUserIfNeeded(List.of()));
    }

    @Test
    @DisplayName("Leaves messages unchanged when no DeepSeek fixes are needed")
    void leavesMessagesUnchangedWhenNoFixesAreNeeded() {
        OpenAIMessage message =
                OpenAIMessage.builder().role("assistant").content("Already valid").build();

        List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(List.of(message));

        assertSame(message, result.get(0));
    }

    @Test
    @DisplayName("Removes stale reasoning content")
    void removesStaleReasoningContent() {
        List<OpenAIMessage> messages =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("First").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("First answer")
                                        .reasoningContent("hidden reasoning")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("user")
                                        .content("Next question")
                                        .build()));

        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("First answer", messages.get(1).getContentAsString());
        assertNull(messages.get(1).getReasoningContent());
        assertEquals("user", messages.get(2).getRole());
    }

    @Test
    @DisplayName("Preserves reasoning content for current turn")
    void preservesReasoningContentForCurrentTurn() {
        List<OpenAIMessage> messages =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("Question").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("Answer")
                                        .reasoningContent("current reasoning")
                                        .build()));

        assertEquals("current reasoning", messages.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Preserves reasoning content for historical tool-call segments")
    void preservesReasoningContentForHistoricalToolCallSegments() {
        OpenAIToolCall toolCall =
                OpenAIToolCall.builder()
                        .id("call_1")
                        .type("function")
                        .function(OpenAIFunction.of("lookup", "{}"))
                        .build();

        List<OpenAIMessage> messages =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("First").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("Calling tool")
                                        .toolCalls(List.of(toolCall))
                                        .reasoningContent("tool reasoning")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("tool")
                                        .toolCallId("call_1")
                                        .content("Tool result")
                                        .build(),
                                OpenAIMessage.builder().role("user").content("Second").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("Second answer")
                                        .reasoningContent("current reasoning")
                                        .build()));

        assertEquals("tool reasoning", messages.get(1).getReasoningContent());
        assertEquals("current reasoning", messages.get(4).getReasoningContent());
    }
}
