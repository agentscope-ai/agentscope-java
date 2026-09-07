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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.coordination.LocalPeriodicGate;
import io.agentscope.harness.agent.coordination.PeriodicGate;
import io.agentscope.harness.agent.coordination.StoreBackedPeriodicGate;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryConsolidator;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Flush and maintenance must retain independent windows when sharing a periodic gate. */
class MemoryMiddlewareThrottleIsolationTest {

    private static final RuntimeContext RC =
            RuntimeContext.builder().userId("alice").sessionId("session-1").build();

    @BeforeEach
    void resetLocalGate() {
        LocalPeriodicGate.clearForTests();
    }

    @AfterEach
    void awaitBackgroundTasks() {
        assertTrue(MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS));
        LocalPeriodicGate.clearForTests();
    }

    static Stream<Arguments> gateConfigurations() {
        return Stream.of(false, true)
                .flatMap(
                        storeBacked ->
                                Arrays.stream(IsolationScope.values())
                                        .flatMap(
                                                scope ->
                                                        Stream.of(false, true)
                                                                .map(
                                                                        maintenanceFirst ->
                                                                                Arguments.of(
                                                                                        storeBacked,
                                                                                        scope,
                                                                                        maintenanceFirst))));
    }

    @ParameterizedTest
    @MethodSource("gateConfigurations")
    void flushAndMaintenanceHaveIndependentWindows(
            boolean storeBacked, IsolationScope scope, boolean maintenanceFirst) {
        PeriodicGate gate =
                storeBacked
                        ? new StoreBackedPeriodicGate(new InMemoryStore())
                        : new LocalPeriodicGate();
        WorkspaceManager workspace = mock(WorkspaceManager.class);
        MemoryConsolidator consolidator = mock(MemoryConsolidator.class);
        when(consolidator.consolidate(RC)).thenReturn(Mono.empty());
        MemoryFlushMiddleware flush = flush(scope, gate);
        MemoryMaintenanceMiddleware maintenance = maintenance(workspace, consolidator, scope, gate);

        if (maintenanceFirst) {
            runMaintenance(maintenance);
            verify(consolidator).consolidate(RC);
            assertTrue(
                    flush.shouldFlushNow(RC), "maintenance must not consume the first flush slot");
        } else {
            assertTrue(flush.shouldFlushNow(RC));
            runMaintenance(maintenance);
            verify(consolidator).consolidate(RC);
        }

        assertFalse(flush.shouldFlushNow(RC), "repeated flush must respect its own gap");
        runMaintenance(maintenance);
        verify(consolidator, times(1)).consolidate(RC);

        // Rebuilding middleware must preserve both windows for the same isolation key.
        assertFalse(flush(scope, gate).shouldFlushNow(RC));
        runMaintenance(maintenance(workspace, consolidator, scope, gate));
        verify(consolidator, times(1)).consolidate(RC);
    }

    private static MemoryFlushMiddleware flush(IsolationScope scope, PeriodicGate gate) {
        return new MemoryFlushMiddleware(
                null,
                null,
                null,
                MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)),
                scope,
                gate);
    }

    private static MemoryMaintenanceMiddleware maintenance(
            WorkspaceManager workspace,
            MemoryConsolidator consolidator,
            IsolationScope scope,
            PeriodicGate gate) {
        return new MemoryMaintenanceMiddleware(
                workspace, consolidator, 90, 180, Duration.ofHours(24), scope, gate);
    }

    private static void runMaintenance(MemoryMaintenanceMiddleware middleware) {
        middleware
                .onAgent(
                        null,
                        RC,
                        new AgentInput(List.of()),
                        input -> Flux.just(new AgentEndEvent("reply-1")))
                .collectList()
                .block(Duration.ofSeconds(5));
        assertTrue(
                MemoryBackgroundTasks.awaitQuiescence(5, TimeUnit.SECONDS),
                "maintenance must finish before verifying consolidation");
    }
}
