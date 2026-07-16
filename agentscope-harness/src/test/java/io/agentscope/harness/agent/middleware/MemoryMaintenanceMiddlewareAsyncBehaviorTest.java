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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/**
 * Regression tests for the issue where {@code onAgent} used {@code concatWith} to append memory
 * maintenance onto the returned {@link reactor.core.publisher.Flux}, forcing callers that consume
 * the response to completion (e.g. {@code blockLast()}, {@code takeLast(1)}) to wait for the full
 * maintenance duration — including the {@link MemoryConsolidator#consolidate} LLM call.
 *
 * <p>{@code onAgent} must now detach maintenance via {@code doOnComplete(...).subscribe()} so the
 * returned Flux completes as soon as the underlying agent call completes, independent of how long
 * maintenance takes.
 */
class MemoryMaintenanceMiddlewareAsyncBehaviorTest {

    @BeforeEach
    void resetSharedMap() {
        MemoryMaintenanceMiddleware.SHARED_LAST_RUN_AT.clear();
    }

    @Test
    void onAgent_completesBeforeSlowConsolidationFinishes(@TempDir Path tmp) throws Exception {
        // No filesystem configured -> expireDailyFiles/pruneOldSessions are no-ops, isolating
        // this test to the consolidation step's timing.
        WorkspaceManager wsm = new WorkspaceManager(tmp);

        CountDownLatch consolidationStarted = new CountDownLatch(1);
        CountDownLatch releaseConsolidation = new CountDownLatch(1);

        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(any()))
                .thenAnswer(
                        invocation ->
                                Mono.<Void>fromRunnable(
                                        () -> {
                                            consolidationStarted.countDown();
                                            await(releaseConsolidation);
                                        }));

        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, consolidator);
        AgentInput input = new AgentInput(List.of(userMsg("hi")));

        long start = System.nanoTime();
        List<AgentEvent> events =
                mw.onAgent(
                                (Agent) null,
                                RuntimeContext.empty(),
                                input,
                                in -> Flux.<AgentEvent>empty())
                        .collectList()
                        .block(Duration.ofSeconds(5));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(events != null && events.isEmpty());
        assertTrue(
                elapsedMs < 1000,
                () ->
                        "onAgent should complete before consolidation finishes, but took "
                                + elapsedMs
                                + "ms");

        assertTrue(
                consolidationStarted.await(2, TimeUnit.SECONDS),
                "consolidation should have started on a detached background thread");

        releaseConsolidation.countDown();
    }

    @Test
    void onAgent_onErrorResume_handlesExceptionFromDetachedMaintenance(@TempDir Path tmp)
            throws Exception {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        MemoryMaintenanceMiddleware mw = new MemoryMaintenanceMiddleware(wsm, null);

        // getUserId() is called (via timerKeyFor) before maybeRunMaintenance's own try/catch,
        // so throwing here reaches onAgent's onErrorResume rather than being swallowed inside
        // maybeRunMaintenance itself.
        RuntimeContext brokenCtx = mock(RuntimeContext.class);
        when(brokenCtx.getUserId()).thenThrow(new RuntimeException("boom"));

        AtomicBoolean errorDropped = new AtomicBoolean(false);
        Hooks.onErrorDropped(t -> errorDropped.set(true));
        try {
            AgentInput input = new AgentInput(List.of(userMsg("hi")));

            List<AgentEvent> events =
                    mw.onAgent((Agent) null, brokenCtx, input, in -> Flux.<AgentEvent>empty())
                            .collectList()
                            .block(Duration.ofSeconds(5));

            assertTrue(events != null && events.isEmpty());

            // Give the detached boundedElastic subscription time to run and (not) drop the error.
            // Without onErrorResume, subscribing with no error consumer routes the error to
            // Hooks.onErrorDropped instead of handling it.
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!errorDropped.get() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertFalse(
                    errorDropped.get(),
                    "onErrorResume should have handled the maintenance error, not dropped it");
        } finally {
            Hooks.resetOnErrorDropped();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }
}
