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
package io.agentscope.harness.agent.memory.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.harness.agent.context.ContextTokenEstimator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenCounterUtilTest {

    @Test
    void calculateToken_countsThinkingContent() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("x".repeat(1_000)).build())
                        .build();

        assertEquals(412, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_countsThinkingContentInsideToolResults() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .output(
                                                ThinkingBlock.builder()
                                                        .thinking("x".repeat(1_000))
                                                        .build())
                                        .build())
                        .build();

        assertEquals(430, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_countsMediaInsideToolResults() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("read")
                                        .output(
                                                ImageBlock.builder()
                                                        .source(
                                                                new URLSource(
                                                                        "https://example.com/image.png"))
                                                        .build())
                                        .build())
                        .build();

        assertEquals(2077, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_usesFinalInputRulesForChineseAndNestedArguments() {
        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("请查询昨天的订单").build(),
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .input(
                                                        Map.of(
                                                                "filter",
                                                                Map.of(
                                                                        "status",
                                                                        List.of("已完成", "退款"))))
                                                .build())
                                .build());

        long finalEstimate =
                ContextTokenEstimator.approximate()
                        .estimate(new ModelCallInput(messages, List.of(), null, null))
                        .tokens();

        assertEquals(finalEstimate, TokenCounterUtil.calculateToken(messages));
    }
}
