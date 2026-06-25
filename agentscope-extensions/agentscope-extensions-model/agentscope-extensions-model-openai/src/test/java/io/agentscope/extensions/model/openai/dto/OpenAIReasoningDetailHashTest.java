/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.model.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.state.ListHashUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for ListHashUtil with OpenAIReasoningDetail in metadata.
 *
 * <p>This test was moved from agentscope-core because OpenAIReasoningDetail was relocated to the
 * extensions module.
 */
class OpenAIReasoningDetailHashTest {

    @Test
    void testComputeHashEquivalentThinkingBlocksWithReasoningDetails() {
        List<Msg> first =
                List.of(
                        Msg.builder()
                                .id("m-assistant-thinking")
                                .timestamp("2026-05-08 14:00:01.000")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ThinkingBlock.builder()
                                                .thinking("thinking")
                                                .metadata(
                                                        Map.of(
                                                                ThinkingBlock
                                                                        .METADATA_REASONING_DETAILS,
                                                                List.of(
                                                                        createReasoningDetail())))
                                                .build())
                                .build());

        List<Msg> second =
                List.of(
                        Msg.builder()
                                .id("m-assistant-thinking")
                                .timestamp("2026-05-08 14:00:01.000")
                                .role(MsgRole.ASSISTANT)
                                .content(
                                        ThinkingBlock.builder()
                                                .thinking("thinking")
                                                .metadata(
                                                        Map.of(
                                                                ThinkingBlock
                                                                        .METADATA_REASONING_DETAILS,
                                                                List.of(
                                                                        createReasoningDetail())))
                                                .build())
                                .build());

        assertEquals(ListHashUtil.computeHash(first), ListHashUtil.computeHash(second));
    }

    private OpenAIReasoningDetail createReasoningDetail() {
        OpenAIReasoningDetail detail = new OpenAIReasoningDetail();
        detail.setId("reasoning-1");
        detail.setType("reasoning.text");
        detail.setData("encrypted-data");
        detail.setText("visible reasoning");
        detail.setFormat("openai-responses-v1");
        detail.setIndex(0);
        return detail;
    }
}
