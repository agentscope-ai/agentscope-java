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
package io.agentscope.core.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ModelCallPolicyExecutionTest {
    @Test
    void synchronousAdmissionRunsOutsideTheEventLoopBeforeModelDispatch() {
        var admitted = new AtomicBoolean();
        var dispatched = new AtomicInteger();
        var policy =
                new ModelCallPolicy() {
                    public void beforeCall(String id, String model, RuntimeContext context) {
                        assertFalse(Schedulers.isInNonBlockingThread());
                        assertEquals("user", context.getUserId());
                        admitted.set(true);
                    }

                    public void afterCall(
                            String id,
                            String model,
                            String status,
                            ChatUsage usage,
                            RuntimeContext context) {}
                };
        var agent =
                ReActAgent.builder()
                        .name("fixture")
                        .model(model(admitted, dispatched))
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .build();
        var context =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ModelCallPolicy.CONTEXT_KEY, policy)
                        .build();
        StepVerifier.create(
                        agent.call(List.of(new UserMessage("hello")), context)
                                .subscribeOn(Schedulers.parallel()))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));
        assertEquals(1, dispatched.get());
    }

    @Test
    void rejectedAdmissionNeverDispatchesTheModel() {
        var dispatched = new AtomicInteger();
        var policy =
                new ModelCallPolicy() {
                    public void beforeCall(String id, String model, RuntimeContext context) {
                        throw new IllegalStateException("permission revoked");
                    }

                    public void afterCall(
                            String id,
                            String model,
                            String status,
                            ChatUsage usage,
                            RuntimeContext context) {}
                };
        var agent =
                ReActAgent.builder()
                        .name("fixture")
                        .model(model(new AtomicBoolean(), dispatched))
                        .threadJournalStore(new InMemoryThreadJournalStore())
                        .build();
        var context =
                RuntimeContext.builder()
                        .userId("user")
                        .sessionId("session")
                        .put(ModelCallPolicy.CONTEXT_KEY, policy)
                        .build();
        StepVerifier.create(
                        agent.call(List.of(new UserMessage("hello")), context)
                                .subscribeOn(Schedulers.parallel()))
                .expectErrorMatches(error -> error.getMessage().contains("permission revoked"))
                .verify(Duration.ofSeconds(10));
        assertEquals(0, dispatched.get());
    }

    private Model model(AtomicBoolean admitted, AtomicInteger dispatched) {
        return new Model() {
            public String getModelName() {
                return "fixture";
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                assertTrue(admitted.get());
                dispatched.incrementAndGet();
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("answer").build()))
                                .build());
            }
        };
    }
}
