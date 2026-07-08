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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

/** Verifies that cancelling {@code agent_spawn} stops the in-flight local subagent. */
class AgentSpawnToolCancellationTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void cancelingAgentSpawnCancelsAndInterruptsLocalSubagent() throws Exception {
        ReActAgent realAgent =
                ReActAgent.builder()
                        .name("slow_subagent")
                        .sysPrompt("You are a slow test subagent.")
                        .maxIters(1)
                        .build();
        ReActAgent slowAgent = Mockito.spy(realAgent);

        CountDownLatch enteredCall = new CountDownLatch(1);
        AtomicBoolean childSubscribed = new AtomicBoolean(false);
        AtomicBoolean childCancelled = new AtomicBoolean(false);

        Mockito.doAnswer(
                        invocation -> {
                            enteredCall.countDown();
                            return Mono.<Msg>never()
                                    .doOnSubscribe(subscription -> childSubscribed.set(true))
                                    .doOnCancel(() -> childCancelled.set(true));
                        })
                .when(slowAgent)
                .call(anyList(), any(RuntimeContext.class));

        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("slow", "Slow subagent", rc -> slowAgent)), null);
        AgentSpawnTool tool = new AgentSpawnTool(manager, Mockito.mock(TaskRepository.class), 0);
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("parent-session").userId("user-1").build();

        var subscription =
                tool.agentSpawn(ctx, null, "slow", "run slowly", null, 30, null).subscribe();

        assertTrue(enteredCall.await(5, TimeUnit.SECONDS), "subagent call should start");
        assertTrue(childSubscribed.get(), "subagent Mono should be subscribed");

        subscription.dispose();

        assertEventuallyTrue(childCancelled, "subagent Mono should be cancelled");
        assertEventuallyInterrupted(slowAgent);
    }

    private static void assertEventuallyTrue(AtomicBoolean condition, String message)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        assertTrue(condition.get(), message);
    }

    private static void assertEventuallyInterrupted(ReActAgent agent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            try {
                Mockito.verify(agent, atLeastOnce()).interrupt(any(RuntimeContext.class));
                return;
            } catch (org.mockito.exceptions.base.MockitoAssertionError ignored) {
                Thread.sleep(50);
            }
        }
        Mockito.verify(agent, atLeastOnce()).interrupt(any(RuntimeContext.class));
    }
}
