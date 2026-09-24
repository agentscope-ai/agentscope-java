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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.dto.OpenAIFunction;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIReasoningDetail;
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

        List<OpenAIMessage> result = DeepSeekFormatter.applyDeepSeekFixes(List.of(message), null);

        assertSame(message, result.get(0));
    }

    @Test
    @DisplayName("Preserves reasoning content for historical text-only turns")
    void preservesReasoningContentForHistoricalTextOnlyTurns() {
        // DeepSeek requires reasoning_content to be fully passed back for ALL assistant turns
        // when the request carries tools — even turns without tool calls (issue #3246).
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
                                        .build()),
                        null);

        assertEquals("assistant", messages.get(1).getRole());
        assertEquals("First answer", messages.get(1).getContentAsString());
        assertEquals("hidden reasoning", messages.get(1).getReasoningContent());
        assertEquals("user", messages.get(2).getRole());
    }

    @Test
    @DisplayName(
            "Backfills empty reasoning content for assistant messages lacking it in"
                    + " thinking mode")
    void backfillsEmptyReasoningContentForAssistantLackingIt() {
        // Thinking mode detected from history; an assistant message without reasoning_content
        // (e.g. a framework-synthesized subagent notification) must be backfilled with an
        // empty value so tools-carrying requests are not rejected (issue #3246).
        List<OpenAIMessage> messages =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("First").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("First answer")
                                        .reasoningContent("history reasoning")
                                        .build(),
                                OpenAIMessage.builder().role("user").content("Next").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("notification")
                                        .build()),
                        null);

        assertEquals("history reasoning", messages.get(1).getReasoningContent());
        assertEquals("", messages.get(3).getReasoningContent());
        assertNull(messages.get(3).getName());
        assertEquals("notification", messages.get(3).getContentAsString());
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
                                        .build()),
                        null);

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
                                        .build()),
                        null);

        assertEquals("tool reasoning", messages.get(1).getReasoningContent());
        assertEquals("current reasoning", messages.get(4).getReasoningContent());
    }

    @Test
    @DisplayName("Two-arg format path backfills missing reasoning content (production path)")
    void twoArgFormatPathBackfillsMissingReasoningContent() {
        // Production requests flow through format(msgs, options) → doFormat(msgs, options).
        // The DeepSeek fixes must apply on this path too (code review finding).
        List<OpenAIMessage> messages =
                new DeepSeekFormatter()
                        .format(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Q1")
                                                                        .build()))
                                                .build(),
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking(
                                                                                "history thinking")
                                                                        .build(),
                                                                TextBlock.builder()
                                                                        .text("A1")
                                                                        .build()))
                                                .build(),
                                        Msg.builder()
                                                .role(MsgRole.USER)
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Q2")
                                                                        .build()))
                                                .build(),
                                        Msg.builder()
                                                .role(MsgRole.ASSISTANT)
                                                .name("agent-1")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("notify")
                                                                        .build()))
                                                .build()),
                                null);

        assertEquals("history thinking", messages.get(1).getReasoningContent());
        assertEquals(
                "",
                messages.get(3).getReasoningContent(),
                "Hint-only assistant must be backfilled on the two-arg production path");
    }

    @Test
    @DisplayName("Two-arg format path appends empty user message when enabled")
    void twoArgFormatPathAppendsEmptyUserMessageWhenEnabled() {
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

        List<OpenAIMessage> result = new DeepSeekFormatter(true).format(messages, null);

        assertEquals(3, result.size());
        assertEquals("user", result.get(2).getRole());
        assertEquals("", result.get(2).getContentAsString());
    }

    @Test
    @DisplayName("Backfill rebuild keeps refusal and reasoning details")
    void backfillRebuildKeepsRefusalAndReasoningDetails() {
        // The rebuild must be lossless: fields unrelated to reasoning must survive the
        // reasoning_content backfill (code review follow-up).
        OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
        detail.setId("detail-1");
        detail.setText("reasoning delta");
        List<OpenAIReasoningDetail> details = List.of(detail);

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("Hi").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("history")
                                        .reasoningContent("history reasoning")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notification")
                                        .refusal("none")
                                        .reasoningDetails(details)
                                        .build()),
                        null);

        OpenAIMessage hintAssistant = fixed.get(2);
        assertEquals("", hintAssistant.getReasoningContent());
        assertEquals("Agent", hintAssistant.getName());
        assertEquals("none", hintAssistant.getRefusal());
        assertEquals(details, hintAssistant.getReasoningDetails());
    }

    @Test
    @DisplayName("Supports strict is disabled for DeepSeek")
    void supportsStrictIsDisabledForDeepSeek() {
        assertFalse(new DeepSeekFormatter().supportsStrict());
    }

    @Test
    @DisplayName("Thinking option enabled backfills even when history has no reasoning content")
    void thinkingOptionEnabledBackfillsWithoutHistoryReasoning() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("thinking", Map.of("type", "enabled"))
                        .build();

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("Hi").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        options);

        assertEquals("", fixed.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Thinking option disabled skips backfill even when history has reasoning")
    void thinkingOptionDisabledSkipsBackfill() {
        // The request's thinking option decides the mode: with thinking explicitly disabled
        // there is no pass-back requirement, so nothing is backfilled.
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("thinking", Map.of("type", "disabled"))
                        .build();

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("history")
                                        .reasoningContent("r")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        options);

        assertNull(fixed.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Options without thinking param fall back to history scan")
    void optionsWithoutThinkingParamIsTreatedAsThinkingOn() {
        GenerateOptions options = GenerateOptions.builder().build();

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("history")
                                        .reasoningContent("r")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        options);

        assertEquals("", fixed.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Unknown thinking option shape is treated as thinking mode on")
    void unknownThinkingOptionShapeIsTreatedAsThinkingOn() {
        GenerateOptions options =
                GenerateOptions.builder().additionalBodyParam("thinking", "not-a-map").build();

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("history")
                                        .reasoningContent("r")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        options);

        assertEquals("", fixed.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Thinking option map with unknown type is treated as thinking mode on")
    void thinkingOptionMapWithUnknownTypeIsTreatedAsThinkingOn() {
        GenerateOptions options =
                GenerateOptions.builder()
                        .additionalBodyParam("thinking", Map.of("type", "auto"))
                        .build();

        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .content("history")
                                        .reasoningContent("r")
                                        .build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        options);

        assertEquals("", fixed.get(1).getReasoningContent());
    }

    @Test
    @DisplayName("Empty user message appended only when conversation ends with assistant")
    void appendEmptyUserOnlyWhenEndingWithAssistant() {
        List<OpenAIMessage> endingWithUser =
                List.of(OpenAIMessage.builder().role("user").content("Hi").build());
        assertSame(endingWithUser, DeepSeekFormatter.appendEmptyUserIfNeeded(endingWithUser));

        List<OpenAIMessage> endingWithAssistant =
                List.of(
                        OpenAIMessage.builder().role("user").content("Hi").build(),
                        OpenAIMessage.builder().role("assistant").content("hello").build());
        List<OpenAIMessage> result = DeepSeekFormatter.appendEmptyUserIfNeeded(endingWithAssistant);
        assertEquals(3, result.size());
        assertEquals("user", result.get(2).getRole());
        assertEquals("", result.get(2).getContent());
    }

    @Test
    @DisplayName("Unknown thinking state still backfills a synthesized assistant turn")
    void unknownThinkingStateStillBackfillsSynthesizedTurn() {
        // Regression for issue #3246: no thinking option is set and the history carries no
        // reasoning_content at all (framework-synthesized turn / lost traces), yet DeepSeek
        // enables thinking server-side by default. The synthesized assistant message must
        // still be backfilled so tool-carrying requests are not rejected with HTTP 400.
        List<OpenAIMessage> fixed =
                DeepSeekFormatter.applyDeepSeekFixes(
                        List.of(
                                OpenAIMessage.builder().role("user").content("继续").build(),
                                OpenAIMessage.builder()
                                        .role("assistant")
                                        .name("Agent")
                                        .content("notify")
                                        .build()),
                        null);

        assertEquals("", fixed.get(1).getReasoningContent());
    }
}
