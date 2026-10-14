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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariant that the compaction trigger and the final request budget derive their token
 * cost from the same accounting. If these diverge, a conversation that the trigger accepts can be
 * rejected by the final budget check (or vice versa).
 */
class ContextBudgetRegressionTest {

    private static final ContextTokenEstimator BUDGET = ContextTokenEstimator.approximate();

    @Test
    void triggerAndBudgetAgreeOnPlainConversation() {
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("hello world").build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .textContent("a".repeat(1000))
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
    }

    @Test
    void triggerAndBudgetAgreeWithToolCallAndResult() {
        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .input(Map.of("q", "x"))
                                                .build())
                                .build(),
                        Msg.builder()
                                .role(MsgRole.TOOL)
                                .content(
                                        ToolResultBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .output(TextBlock.builder().text("result").build())
                                                .build())
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long budget = BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();

        assertEquals(trigger, budget);
    }

    @Test
    void toolSchemasRaiseOnlyTheBudgetPath() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ToolSchema tool =
                ToolSchema.builder()
                        .name("search")
                        .description("search")
                        .parameters(Map.of("type", "object"))
                        .build();

        long trigger = TokenCounterUtil.calculateToken(messages);
        long withTools =
                BUDGET.estimate(new ModelCallInput(messages, List.of(tool), null, null)).tokens();

        assertTrue(withTools > trigger);
    }

    @Test
    void responseFormatRaisesOnlyTheBudgetPath() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        ResponseFormat format =
                ResponseFormat.jsonSchema(
                        JsonSchema.builder()
                                .name("Answer")
                                .schema(Map.of("type", "object", "description", "x".repeat(400)))
                                .build());

        long trigger = TokenCounterUtil.calculateToken(messages);
        long without =
                BUDGET.estimate(new ModelCallInput(messages, List.of(), null, null)).tokens();
        long with =
                BUDGET.estimate(
                                new ModelCallInput(
                                        messages,
                                        List.of(),
                                        GenerateOptions.builder().responseFormat(format).build(),
                                        null))
                        .tokens();

        assertEquals(trigger, without, "the trigger path never carries a response format");
        assertTrue(with > without, "the final budget path must price the schema");
    }
}
