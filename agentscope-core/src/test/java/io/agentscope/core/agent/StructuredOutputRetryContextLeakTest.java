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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Regression test for the maintainer-reported context-leak issue: a failed
 * retry attempt must not leak its thinking into the final result, and its
 * token usage must still be accounted for in the aggregate.
 */
class StructuredOutputRetryContextLeakTest {

    static final class Answer {
        public int answer;
    }

    static final class RetryModel implements Model {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int call = calls.getAndIncrement();
            List<io.agentscope.core.message.ContentBlock> content =
                    call == 0
                            ? List.of(
                                    ThinkingBlock.builder().thinking("failed-thinking").build(),
                                    TextBlock.builder().text("{\"answer\":\"wrong\"}").build())
                            : List.of(
                                    ThinkingBlock.builder().thinking("valid-thinking").build(),
                                    TextBlock.builder().text("{\"answer\":7}").build());
            ChatUsage usage = call == 0 ? new ChatUsage(10, 20, 0) : new ChatUsage(30, 40, 0);
            return Flux.just(
                    ChatResponse.builder().id("m" + call).content(content).usage(usage).build());
        }

        @Override
        public String getModelName() {
            return "retry-model";
        }

        @Override
        public boolean supportsNativeStructuredOutput() {
            return true;
        }
    }

    @Test
    @DisplayName("failed attempt thinking does not leak; usage is aggregated across attempts")
    void failedAttemptThinkingDoesNotLeakAndUsageIsAggregated() {
        RetryModel model = new RetryModel();
        ReActAgent agent =
                ReActAgent.builder().name("agent").sysPrompt("test").model(model).build();
        Msg user =
                Msg.builderForRole(MsgRole.USER)
                        .content(TextBlock.builder().text("answer").build())
                        .build();

        Msg result = agent.call(user, Answer.class).block(Duration.ofSeconds(10));

        assertEquals(2, model.calls.get());
        assertEquals("{\"answer\":7}", result.getTextContent());

        // Only the conforming attempt's thinking reaches the final message.
        String thinking =
                result.getContent().stream()
                        .filter(b -> b instanceof ThinkingBlock)
                        .map(b -> ((ThinkingBlock) b).getThinking())
                        .reduce("", (a, b) -> a + b);
        assertTrue(
                thinking != null && thinking.contains("valid-thinking"),
                "final thinking should contain conforming attempt's thinking");
        assertTrue(
                !thinking.contains("failed-thinking"),
                "failed-turn thinking leaked into final message: " + thinking);

        // Usage aggregates across attempts: input 10+30, output 20+40.
        assertEquals(40, result.getChatUsage().getInputTokens());
        assertEquals(60, result.getChatUsage().getOutputTokens());
    }
}
