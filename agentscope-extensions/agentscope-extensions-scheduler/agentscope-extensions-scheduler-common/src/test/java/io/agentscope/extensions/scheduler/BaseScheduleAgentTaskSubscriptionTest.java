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
package io.agentscope.extensions.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Timeout(10)
class BaseScheduleAgentTaskSubscriptionTest {

    private final List<Agent> executions = new CopyOnWriteArrayList<>();
    private final List<List<Msg>> modelInputs = new CopyOnWriteArrayList<>();
    private Model model;
    private ChatResponse response;
    private BaseScheduleAgentTask task;
    private Msg input;

    @BeforeEach
    void setUp() {
        model = mock(Model.class);
        when(model.getModelName()).thenReturn("scheduler-test");
        response =
                ChatResponse.builder()
                        .id("response")
                        .content(List.of(TextBlock.builder().text("ok").build()))
                        .build();
        when(model.stream(anyList(), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            modelInputs.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(response);
                        });
        ModelConfig modelConfig = mock(ModelConfig.class);
        when(modelConfig.createModel()).thenReturn(model);
        Hook recorder =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PreCallEvent) {
                            executions.add(event.getAgent());
                        }
                        return Mono.just(event);
                    }
                };
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder()
                        .name("subscription-test")
                        .modelConfig(modelConfig)
                        .sysPrompt("test prompt")
                        .hooks(List.of(recorder))
                        .build();
        task =
                new BaseScheduleAgentTask(
                        config, ScheduleConfig.builder().build(), mock(AgentScheduler.class));
        input =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("hello").build())
                        .build();
    }

    @Test
    void assemblingRunDoesNotCountAnExecution() {
        Mono<Msg> pending = task.run(input);

        assertNotNull(pending);
        assertEquals(0, task.getExecutionCount());
        assertTrue(executions.isEmpty());
        assertTrue(modelInputs.isEmpty());
    }

    @Test
    void eachSubscriptionUsesAFreshAgentAndHistory() {
        Mono<Msg> run = task.run(input);
        assertNotNull(run.block(Duration.ofSeconds(5)));
        assertNotNull(run.block(Duration.ofSeconds(5)));

        assertEquals(2, executions.size());
        assertNotSame(executions.get(0), executions.get(1));
        assertEquals(2, modelInputs.size());
        assertEquals(modelInputs.get(0).size(), modelInputs.get(1).size());
        assertEquals(2, task.getExecutionCount());
    }

    @Test
    void retryStartsANewAgentAndCountsEachAttempt() {
        when(model.stream(anyList(), anyList(), any()))
                .thenReturn(Flux.error(new IllegalStateException("first attempt failed")))
                .thenReturn(Flux.just(response));

        assertNotNull(task.run(input).retry(1).block(Duration.ofSeconds(5)));

        assertEquals(2, executions.size());
        assertNotSame(executions.get(0), executions.get(1));
        assertEquals(2, task.getExecutionCount());
    }

    @Test
    void cancellationAfterAssemblyPreventsExecution() {
        Mono<Msg> pending = task.run(input);
        task.cancel();

        StepVerifier.create(pending)
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
        assertTrue(executions.isEmpty());
        assertTrue(modelInputs.isEmpty());
        assertEquals(0, task.getExecutionCount());
    }

    @Test
    void cancellationBetweenSubscriptionsPreventsAnotherExecution() {
        Mono<Msg> run = task.run(input);
        assertNotNull(run.block(Duration.ofSeconds(5)));
        task.cancel();

        StepVerifier.create(run)
                .expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
        assertEquals(1, executions.size());
        assertEquals(1, modelInputs.size());
        assertEquals(1, task.getExecutionCount());
    }
}
