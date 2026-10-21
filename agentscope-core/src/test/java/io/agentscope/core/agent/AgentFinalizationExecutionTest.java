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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionLogException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class AgentFinalizationExecutionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resumedFinalizationPreservesReplyAndFinishHooksWithoutPrimaryInference(boolean streaming) {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var agent = agent(calls, finishes);
        Msg original = Msg.builder().name("agent").textContent("already produced").build();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentFinalization.class,
                                new AgentFinalization() {
                                    public Msg resumeResult() {
                                        return original;
                                    }

                                    public Mono<Void> finish(
                                            RuntimeContext runtime,
                                            Msg result,
                                            Supplier<Mono<Void>> next) {
                                        assertNotNull(runtime.getAgentState());
                                        assertEquals(original, result);
                                        return next.get();
                                    }
                                })
                        .build();
        Msg actual =
                streaming
                        ? agent.streamEvents(List.of(new UserMessage("resume")), context)
                                .ofType(AgentResultEvent.class)
                                .map(AgentResultEvent::getResult)
                                .single()
                                .block(Duration.ofSeconds(5))
                        : agent.call(List.of(new UserMessage("resume")), context)
                                .block(Duration.ofSeconds(5));
        assertEquals(original, actual);
        assertEquals(0, calls.get());
        assertEquals(1, finishes.get());
    }

    @Test
    void observerKeepsOrdinaryModelAndFinishBehavior() {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var observed = new AtomicInteger();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentFinalization.class,
                                (rc, result, next) -> {
                                    observed.incrementAndGet();
                                    return next.get();
                                })
                        .build();
        assertEquals(
                "new reply",
                agent(calls, finishes)
                        .call(List.of(new UserMessage("hello")), context)
                        .block(Duration.ofSeconds(5))
                        .getTextContent());
        assertEquals(1, calls.get());
        assertEquals(1, finishes.get());
        assertEquals(1, observed.get());
    }

    @Test
    void persistenceFailureInFinalizationIsNotConvertedToAReply() {
        var calls = new AtomicInteger();
        var finishes = new AtomicInteger();
        var context =
                RuntimeContext.builder()
                        .sessionId("session")
                        .put(
                                AgentFinalization.class,
                                (rc, result, next) ->
                                        Mono.error(
                                                new SessionLogException(
                                                        "unconfirmed finalization")))
                        .build();
        StepVerifier.create(agent(calls, finishes).call(List.of(new UserMessage("hello")), context))
                .expectError(SessionLogException.class)
                .verify(Duration.ofSeconds(5));
        assertEquals(1, calls.get());
        assertEquals(0, finishes.get());
    }

    private ReActAgent agent(AtomicInteger calls, AtomicInteger finishes) {
        Model model =
                new Model() {
                    public String getModelName() {
                        return "finalization-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("new reply")
                                                                .build()))
                                        .build());
                    }
                };
        return ReActAgent.builder()
                .name("agent")
                .model(model)
                .sessionLogStore(new InMemorySessionLogStore())
                .middleware(
                        new MiddlewareBase() {
                            public Set<ExtensionPoint> activePoints() {
                                return EnumSet.of(ExtensionPoint.ON_AGENT_FINISH);
                            }

                            public Mono<Void> onAgentFinish(
                                    Agent agent, RuntimeContext rc, Msg result) {
                                finishes.incrementAndGet();
                                return Mono.empty();
                            }
                        })
                .build();
    }
}
