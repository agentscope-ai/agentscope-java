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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for GitHub issue #3246: DeepSeek thinking mode returned HTTP 400 "The
 * {@code reasoning_content} in the thinking mode must be passed back to the API."
 *
 * <p>Per the DeepSeek docs (Thinking Mode / Tool Calls): for requests carrying the {@code tools}
 * parameter, {@code reasoning_content} must be fully passed back for ALL assistant turns — even
 * turns without tool calls.
 */
@Tag("unit")
@DisplayName("Issue #3246 reasoning_content pass-back regression tests")
class DeepSeekReasoningContentPassbackTest {

    private static final String NOTIFICATION =
            "<system-notification>Background subagent task 'task_c495' (agent=general-purpose)"
                    + " has completed.\n\nResult:\n\nError: java.lang.InterruptedException"
                    + "</system-notification>";

    @Test
    @DisplayName(
            "Plain OpenAI formatter emits hint-only assistant msg without"
                    + " reasoning_content (documented trigger condition)")
    void plainFormatterHintAssistantHasNoReasoning() {
        // Documents the issue trigger: the generic formatter serializes a synthesized
        // assistant message (HintBlock only, e.g. a subagent completion notification) without
        // reasoning_content. DeepSeek users must use DeepSeekFormatter to be protected.
        Msg hintMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name("a6779a6083fd4858b68ce1dfc772c441")
                        .content(List.of(new HintBlock("hint1", NOTIFICATION, "subagent_task")))
                        .build();
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("继续").build()))
                                .build(),
                        hintMsg);

        OpenAIChatFormatter plainFormatter = new OpenAIChatFormatter();
        List<OpenAIMessage> result = plainFormatter.format(msgs);

        OpenAIMessage assistant = result.get(1);
        assertEquals("assistant", assistant.getRole());
        assertNotNull(assistant.getName());
        assertNull(assistant.getReasoningContent());
    }

    @Test
    @DisplayName("DeepSeekFormatter preserves reasoning_content of previous text-only turns")
    void deepSeekFormatterPreservesReasoningOfTextOnlyHistoryTurn() {
        // Turn 1: user asks, model answers with thinking + text (NO tool call).
        // Turn 2: user continues. The request carries tools (agent loop) — DeepSeek
        // requires ALL assistant reasoning_content to be passed back.
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Q1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("thinking about Q1")
                                                        .build(),
                                                TextBlock.builder().text("A1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Q2").build()))
                                .build());

        io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter formatter =
                new io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter();
        List<OpenAIMessage> result = formatter.format(msgs);

        OpenAIMessage historicalAssistant = result.get(1);
        assertEquals("assistant", historicalAssistant.getRole());
        assertEquals(
                "thinking about Q1",
                historicalAssistant.getReasoningContent(),
                "Historical text-only assistant must keep reasoning_content (issue #3246)");
    }

    @Test
    @DisplayName(
            "DeepSeekFormatter backfills empty reasoning_content for hint-only assistant"
                    + " messages in thinking mode")
    void deepSeekFormatterBackfillsReasoningForHintAssistant() {
        // Real issue scenario: after prior thinking turns (reasoning trace present), a
        // framework-synthesized assistant message (subagent completion notification, no
        // ThinkingBlock) enters the history. It must be backfilled with an empty
        // reasoning_content so the tools-carrying request is not rejected.
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Q1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("thinking about Q1")
                                                        .build(),
                                                TextBlock.builder().text("A1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("继续").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("agent-1")
                                .content(
                                        List.of(
                                                new HintBlock(
                                                        "hint1", NOTIFICATION, "subagent_task")))
                                .build());

        io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter formatter =
                new io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter();
        List<OpenAIMessage> result = formatter.format(msgs);

        OpenAIMessage historicalAssistant = result.get(1);
        assertEquals("thinking about Q1", historicalAssistant.getReasoningContent());

        OpenAIMessage hintAssistant = result.get(3);
        assertEquals("assistant", hintAssistant.getRole());
        assertEquals(
                "",
                hintAssistant.getReasoningContent(),
                "Hint-only assistant must be backfilled with empty reasoning_content"
                        + " (issue #3246)");
        // compat formatter preserves the name field (DeepSeek v4 allows it)
        assertEquals("agent-1", hintAssistant.getName());
    }

    @Test
    @DisplayName(
            "Compat DeepSeekFormatter backfills on the production two-arg format path"
                    + " (format with options)")
    void compatFormatterBackfillsOnTwoArgProductionPath() {
        // Production requests flow through format(msgs, options) → doFormat(msgs, options).
        // The two-arg override previously bypassed applyDeepSeekFixes entirely, so the fix
        // never fired on real requests — pin both paths (code review finding).
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Q1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("thinking about Q1")
                                                        .build(),
                                                TextBlock.builder().text("A1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("继续").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("agent-1")
                                .content(
                                        List.of(
                                                new HintBlock(
                                                        "hint1", NOTIFICATION, "subagent_task")))
                                .build());

        io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter formatter =
                new io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter();
        List<OpenAIMessage> result = formatter.format(msgs, null);

        assertEquals("thinking about Q1", result.get(1).getReasoningContent());
        assertEquals(
                "",
                result.get(3).getReasoningContent(),
                "Hint-only assistant must be backfilled on the two-arg production path"
                        + " (issue #3246)");
        assertEquals("agent-1", result.get(3).getName());
    }

    @Test
    @DisplayName(
            "Deprecated DeepSeekFormatter strips name and backfills on the two-arg"
                    + " production path")
    void deprecatedFormatterBackfillsOnTwoArgProductionPath() {
        // Same scenario through the deprecated formatter and the two-arg production path:
        // name removed (legacy rule) and reasoning_content backfilled (issue #3246).
        List<Msg> msgs =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("Q1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        List.of(
                                                ThinkingBlock.builder()
                                                        .thinking("thinking about Q1")
                                                        .build(),
                                                TextBlock.builder().text("A1").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text("继续").build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .name("agent-1")
                                .content(
                                        List.of(
                                                new HintBlock(
                                                        "hint1", NOTIFICATION, "subagent_task")))
                                .build());

        DeepSeekFormatter formatter = new DeepSeekFormatter();
        List<OpenAIMessage> result = formatter.format(msgs, null);

        assertEquals("thinking about Q1", result.get(1).getReasoningContent());
        assertEquals("", result.get(3).getReasoningContent());
        assertNull(
                result.get(3).getName(),
                "Deprecated formatter must strip the name field (DeepSeek returns HTTP 400)");
    }
}
