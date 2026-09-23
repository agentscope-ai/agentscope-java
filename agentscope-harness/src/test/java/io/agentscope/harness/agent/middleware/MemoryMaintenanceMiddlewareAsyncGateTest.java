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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Verifies that {@link MemoryMaintenanceMiddleware} runs its periodic-gate claim off the
 * completing thread: with a store-backed gate in a cluster deployment the claim is remote I/O,
 * so it must not execute synchronously in the {@code doOnComplete} callback (which may be a
 * Netty event loop).
 */
class MemoryMaintenanceMiddlewareAsyncGateTest {

    @Test
    void gateClaimRunsOffTheCompletingThread() throws Exception {
        AtomicReference<String> claimThread = new AtomicReference<>();
        CountDownLatch claimed = new CountDownLatch(1);
        PeriodicGate recordingGate =
                (name, minGap) -> {
                    claimThread.set(Thread.currentThread().getName());
                    claimed.countDown();
                    return false; // throttled out — enough to observe the claim's thread
                };
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(
                        null,
                        null,
                        90,
                        180,
                        Duration.ofMinutes(30),
                        IsolationScope.USER,
                        recordingGate);

        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        RuntimeContext rc = RuntimeContext.builder().userId("alice").build();
        AgentEndEvent event = new AgentEndEvent("reply-1");
        middleware
                .onAgent(null, rc, new AgentInput(List.of(userMsg)), in -> Flux.just(event))
                .collectList()
                .block(Duration.ofSeconds(2));

        assertTrue(claimed.await(5, TimeUnit.SECONDS), "gate claim must fire");
        assertTrue(
                claimThread.get().contains("boundedElastic"),
                "gate claim must run on boundedElastic, ran on: " + claimThread.get());
        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "maintenance task must quiesce");
    }

    @Test
    void completingThreadIsNotBoundedElastic() throws Exception {
        // Sanity guard for the assertion above: the thread that completes the agent stream in
        // these tests (the test thread driving block()) must never be a boundedElastic thread,
        // otherwise the thread-name check cannot distinguish eager from deferred claims.
        AtomicReference<String> completingThread = new AtomicReference<>();
        Msg userMsg = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        RuntimeContext rc = RuntimeContext.builder().userId("alice").build();
        AgentEndEvent event = new AgentEndEvent("reply-1");
        Flux<AgentEvent> stream =
                Flux.<AgentEvent>just(event)
                        .doOnComplete(() -> completingThread.set(Thread.currentThread().getName()));
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(
                        null,
                        null,
                        90,
                        180,
                        Duration.ofMinutes(30),
                        IsolationScope.USER,
                        (name, minGap) -> false);

        middleware
                .onAgent(null, rc, new AgentInput(List.of(userMsg)), in -> stream)
                .collectList()
                .block(Duration.ofSeconds(2));

        assertTrue(
                completingThread.get() != null
                        && !completingThread.get().contains("boundedElastic"),
                "test setup invalid: completing thread is boundedElastic ("
                        + completingThread.get()
                        + ")");
    }
}
