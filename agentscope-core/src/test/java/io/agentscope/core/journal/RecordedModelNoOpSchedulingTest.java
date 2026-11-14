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
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class RecordedModelNoOpSchedulingTest {
    @Test
    void directRunWithoutInboxDoesNotQueueEmptySessionStepOrCloseHooks() {
        var queued = new AtomicInteger();
        Schedulers.onScheduleHook(
                "no-op-react-session-test",
                work ->
                        () -> {
                            if (Thread.currentThread()
                                    .getName()
                                    .startsWith("agentscope-model-request"))
                                queued.incrementAndGet();
                            work.run();
                        });
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("test");
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(
                        Flux.just(
                                ChatResponse.builder()
                                        .content(List.of(TextBlock.builder().text("done").build()))
                                        .build()));
        var store = new InMemoryThreadJournalStore();
        try (var agent =
                ReActAgent.builder().name("test").model(model).threadJournalStore(store).build()) {
            agent.call(
                            List.of(new UserMessage("hello")),
                            RuntimeContext.builder().userId("u").sessionId("s").build())
                    .block(Duration.ofSeconds(5));
            assertEquals(0, queued.get());
        } finally {
            Schedulers.resetOnScheduleHook("no-op-react-session-test");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrDeclaredEmptyPolicyNeverUsesRequestWorkers(boolean installPolicy) {
        var tasks = new AtomicInteger();
        Schedulers.onScheduleHook(
                "no-op-model-policy-test",
                work ->
                        () -> {
                            if (Thread.currentThread()
                                    .getName()
                                    .startsWith("agentscope-model-request"))
                                tasks.incrementAndGet();
                            work.run();
                        });
        var recorder =
                new InvocationRecorder(
                        new InMemoryThreadJournalStore()
                                .open(new ThreadKey("u", "a", "s", "main"), null),
                        "turn",
                        "run",
                        null);
        try {
            var context =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(InvocationRecorder.CONTEXT_KEY, recorder)
                            .build();
            context.setAgentState(AgentState.builder().build());
            if (installPolicy)
                context.put(
                        ModelCallPolicy.CONTEXT_KEY,
                        new ModelCallPolicy() {
                            public boolean hasBeforeCall() {
                                return false;
                            }

                            public boolean hasAfterCall() {
                                return false;
                            }

                            public void beforeCall(String id, String model, RuntimeContext rc) {
                                fail("empty admission invoked");
                            }

                            public void afterCall(
                                    String id,
                                    String model,
                                    String status,
                                    ChatUsage usage,
                                    RuntimeContext rc) {
                                fail("empty completion invoked");
                            }
                        });
            var model = mock(Model.class);
            when(model.getModelName()).thenReturn("test");
            when(model.stream(anyList(), anyList(), any()))
                    .thenReturn(
                            Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("done")
                                                                    .build()))
                                            .build()));
            RecordedModels.wrap(model, context, "answer").stream(
                            List.of(new UserMessage("hello")),
                            List.of(),
                            GenerateOptions.builder().build())
                    .blockLast(Duration.ofSeconds(5));
            assertEquals(0, tasks.get());
        } finally {
            recorder.close();
            Schedulers.resetOnScheduleHook("no-op-model-policy-test");
        }
    }
}
