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
package io.agentscope.core.tracing.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamChatResponseAggregator Tests")
class StreamChatResponseAggregatorTest {

    @Test
    @DisplayName("Cumulative usage should take max, not sum")
    void testCumulativeUsageTakesMax() {
        StreamChatResponseAggregator agg = StreamChatResponseAggregator.create();

        for (int i = 1; i <= 5; i++) {
            agg.append(
                    ChatResponse.builder()
                            .id("test-id")
                            .content(List.of(TextBlock.builder().text("chunk" + i).build()))
                            .usage(
                                    ChatUsage.builder()
                                            .inputTokens(100)
                                            .outputTokens(i * 20)
                                            .time(i * 0.5)
                                            .build())
                            .finishReason(i == 5 ? "stop" : null)
                            .build());
        }

        ChatResponse response = agg.getResponse();
        assertEquals("test-id", response.getId());
        assertEquals(100, response.getUsage().getInputTokens());
        assertEquals(100, response.getUsage().getOutputTokens());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    @DisplayName("Only last chunk carries usage (OpenAI style)")
    void testOnlyLastChunkHasUsage() {
        StreamChatResponseAggregator agg = StreamChatResponseAggregator.create();

        for (int i = 0; i < 3; i++) {
            agg.append(
                    ChatResponse.builder()
                            .id("openai-id")
                            .content(List.of(TextBlock.builder().text("part" + i).build()))
                            .build());
        }

        agg.append(
                ChatResponse.builder()
                        .id("openai-id")
                        .usage(ChatUsage.builder().inputTokens(200).outputTokens(150).build())
                        .finishReason("stop")
                        .build());

        ChatResponse response = agg.getResponse();
        assertEquals(200, response.getUsage().getInputTokens());
        assertEquals(150, response.getUsage().getOutputTokens());
    }
}
