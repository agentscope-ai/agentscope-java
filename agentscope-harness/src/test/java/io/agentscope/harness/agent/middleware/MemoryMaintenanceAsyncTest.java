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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.memory.MemoryOperationScheduler;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class MemoryMaintenanceAsyncTest {

    @AfterEach
    void clearThrottleState() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.clear();
    }

    @Test
    void asyncMaintenanceDoesNotDelayAgentCompletion() throws Exception {
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        Agent agent = mock(Agent.class);
        CountDownLatch maintenanceStarted = new CountDownLatch(1);
        CountDownLatch releaseMaintenance = new CountDownLatch(1);
        when(consolidator.consolidate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        Mono.fromRunnable(
                                        () -> {
                                            maintenanceStarted.countDown();
                                            await(releaseMaintenance);
                                        })
                                .then());

        MemoryOperationScheduler scheduler = new MemoryOperationScheduler();
        MemoryMaintenanceMiddleware middleware =
                new MemoryMaintenanceMiddleware(
                        workspaceManager,
                        consolidator,
                        90,
                        180,
                        Duration.ZERO,
                        IsolationScope.SESSION,
                        MemoryConfig.ExecutionMode.ASYNC,
                        scheduler);

        middleware
                .onAgent(
                        agent,
                        RuntimeContext.builder().sessionId("session-1").build(),
                        new AgentInput(List.of()),
                        ignored -> Flux.empty())
                .blockLast(Duration.ofSeconds(1));

        assertTrue(
                maintenanceStarted.await(1, TimeUnit.SECONDS),
                "maintenance should continue after the agent stream completes");
        releaseMaintenance.countDown();
        scheduler.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
