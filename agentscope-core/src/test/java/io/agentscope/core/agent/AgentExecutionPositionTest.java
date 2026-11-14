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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Flux;

class AgentExecutionPositionTest {
    @ParameterizedTest
    @CsvSource({"false,2", "true,2", "false,3", "true,3"})
    void restoredPositionPreservesIterationAndLimitForBothCallPaths(
            boolean streaming, int iteration) {
        var calls = new AtomicInteger();
        var logs = new InMemoryThreadJournalStore();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "iteration-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("reply").build()))
                                        .build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("agent")
                        .model(model)
                        .maxIters(3)
                        .threadJournalStore(logs)
                        .build();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentExecutionPosition.class,
                                new AgentExecutionPosition(iteration, iteration - 1))
                        .build();
        Msg result =
                streaming
                        ? agent.streamEvents(List.of(new UserMessage("continue")), context)
                                .ofType(AgentResultEvent.class)
                                .map(AgentResultEvent::getResult)
                                .single()
                                .block(Duration.ofSeconds(5))
                        : agent.call(List.of(new UserMessage("continue")), context)
                                .block(Duration.ofSeconds(5));
        assertEquals(1, calls.get());
        assertEquals(
                iteration == 3 ? GenerateReason.MAX_ITERATIONS : GenerateReason.MODEL_STOP,
                result.getGenerateReason());
        var log = logs.open(logs.list(context).get(0), context);
        assertEquals(
                List.of(iteration),
                StreamSupport.stream(log.scan(0, log.head().seq()).spliterator(), false)
                        .filter(event -> "step/start".equals(event.type()))
                        .map(event -> ((Number) event.data().get("iteration")).intValue())
                        .toList());
    }

    @Test
    void negativePositionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AgentExecutionPosition(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AgentExecutionPosition(0, -1));
    }
}
