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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Verifies that the per-call {@link RuntimeContext#getRunId()} is readable from middleware
 * callbacks on every invocation path (plain {@code call()} and {@code prepareCall()} handles),
 * stays stable across the callbacks of one call, and differs between concurrent calls.
 */
@DisplayName("ReActAgent runId visibility in middleware callbacks")
class ReActAgentRunIdVisibilityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /**
     * Records every runId observed per session across all middleware callbacks. One call must
     * contribute exactly one distinct runId; distinct concurrent calls must contribute distinct
     * values.
     */
    private static final class RunIdRecorder implements MiddlewareBase {
        private final ConcurrentHashMap<String, Set<String>> observedBySession =
                new ConcurrentHashMap<>();

        void record(RuntimeContext ctx) {
            String runId = ctx.getRunId();
            observedBySession
                    .computeIfAbsent(ctx.getSessionId(), k -> ConcurrentHashMap.newKeySet())
                    .add(runId);
        }

        Set<String> observed(String sessionId) {
            return observedBySession.getOrDefault(sessionId, Set.of());
        }

        @Override
        public Flux<AgentEvent> onAgent(
                Agent agent,
                RuntimeContext ctx,
                AgentInput input,
                Function<AgentInput, Flux<AgentEvent>> next) {
            record(ctx);
            return next.apply(input);
        }

        @Override
        public Flux<AgentEvent> onReasoning(
                Agent agent,
                RuntimeContext ctx,
                ReasoningInput input,
                Function<ReasoningInput, Flux<AgentEvent>> next) {
            record(ctx);
            return next.apply(input);
        }

        @Override
        public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
            record(ctx);
            return Mono.just(prompt);
        }
    }

    @Test
    @DisplayName("plain call(): middleware reads a stable per-call runId; concurrent calls differ")
    void plainCallMiddlewareSeesStableDistinctRunIds() {
        RunIdRecorder recorder = new RunIdRecorder();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("runid-visibility")
                        .model(new MockModel("done"))
                        .middlewares(List.of(recorder))
                        .build()) {
            Msg input = new UserMessage("hello");
            Mono.zip(
                            agent.call(
                                    List.of(input),
                                    RuntimeContext.builder()
                                            .userId("u")
                                            .sessionId("sess-a")
                                            .build()),
                            agent.call(
                                    List.of(input),
                                    RuntimeContext.builder()
                                            .userId("u")
                                            .sessionId("sess-b")
                                            .build()))
                    .block(TIMEOUT);

            Set<String> seenByA = recorder.observed("sess-a");
            Set<String> seenByB = recorder.observed("sess-b");
            assertEquals(1, seenByA.size(), "one call must observe exactly one runId");
            assertEquals(1, seenByB.size(), "one call must observe exactly one runId");
            String runA = seenByA.iterator().next();
            String runB = seenByB.iterator().next();
            assertNotNull(runA);
            assertNotNull(runB);
            assertNotEquals(runA, runB, "concurrent calls must not share a runId");
        }
    }

    @Test
    @DisplayName("prepareCall(): middleware runId equals the handle's runId")
    void preparedCallMiddlewareRunIdEqualsHandleRunId() {
        RunIdRecorder recorder = new RunIdRecorder();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("runid-handle")
                        .model(new MockModel("done"))
                        .middlewares(List.of(recorder))
                        .build()) {
            RuntimeContext ctx =
                    RuntimeContext.builder().userId("u").sessionId("sess-handle").build();
            AgentRun<Msg> run = agent.prepareCall(List.of(new UserMessage("hello")), ctx);
            assertNotNull(run.stream().single().block(TIMEOUT));
            assertEquals(1, recorder.observed("sess-handle").size());
            assertEquals(run.runId(), recorder.observed("sess-handle").iterator().next());
        }
    }
}
