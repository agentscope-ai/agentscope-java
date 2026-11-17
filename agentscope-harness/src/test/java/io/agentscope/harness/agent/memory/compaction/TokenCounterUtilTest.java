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

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenCounterUtilTest {

    @Test
    void calculateToken_chargesMediaBlocksTheSameFixedWeightAsMain() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                ImageBlock.builder()
                                        .source(new Base64Source("image/png", "AA=="))
                                        .build())
                        .build();

        // 5 message overhead + 2 for the USER role + 2048 for the image.
        assertEquals(2055, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_chargesMediaBlocksInsideToolResults() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call-1")
                                        .name("search")
                                        .output(
                                                ImageBlock.builder()
                                                        .source(
                                                                new Base64Source(
                                                                        "image/png", "AA=="))
                                                        .build())
                                        .build())
                        .build();

        // 5 message overhead + 2 for the TOOL role + 8 tool-result overhead
        // + 3 for the id + 3 for the name + 2048 for the image.
        assertEquals(2069, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_countsHintBlocksAsTextNotMedia() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(new HintBlock("hint-1", "x".repeat(1_000)))
                        .build();

        // 5 message overhead + 4 for the ASSISTANT role + 400 for the hint text.
        assertEquals(409, TokenCounterUtil.calculateToken(List.of(message)));
    }

    @Test
    void calculateToken_countsThinkingContent() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(ThinkingBlock.builder().thinking("x".repeat(1_000)).build())
                        .build();

        assertEquals(409, TokenCounterUtil.calculateToken(List.of(message)));
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

        assertEquals(421, TokenCounterUtil.calculateToken(List.of(message)));
    }
}
