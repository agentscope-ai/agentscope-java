/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.SessionProjection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class ManagedCancelBeforePreparationTest {
    private final ManagedScope scope = new ManagedScope("tenant", "namespace", "session");
    private final ManagedTurnExecution execution = mock(ManagedTurnExecution.class);
    private final ManagedJournalStore journal = mock(ManagedJournalStore.class);
    private final ManagedLocalJournal local = mock(ManagedLocalJournal.class);
    private final ManagedMqSessionLog log = mock(ManagedMqSessionLog.class);
    private final ManagedInputDelivery inputs = mock(ManagedInputDelivery.class);
    private final ManagedHarnessTurns turns =
            new ManagedHarnessTurns(
                    new ObjectMapper(), inputs, mock(ScheduledExecutorService.class));

    private void cancelledAndExternallySettled() {
        when(execution.scope()).thenReturn(scope);
        when(execution.turnId()).thenReturn("turn");
        when(execution.journal()).thenReturn(journal);
        when(execution.local()).thenReturn(local);
        when(execution.log()).thenReturn(log);
        when(journal.cancelRequested(scope, "turn")).thenReturn(true);
        when(local.pendingTools()).thenReturn(List.of());
    }

    @Test
    void externallySettledToolsStillRequireNativeReconciliationBeforeCancellation() {
        cancelledAndExternallySettled();
        try (var projections = mockStatic(SessionProjection.class)) {
            projections
                    .when(() -> SessionProjection.read(log))
                    .thenReturn(new SessionProjection(42, null, Set.of("call"), Set.of()));
            assertFalse(turns.cancelBeforePreparation(execution));
            verify(execution, never()).closeInputs();
            verify(execution, never()).finish("cancelled");
            verifyNoInteractions(inputs);
        }
    }

    @Test
    void fullySettledCancellationStillAvoidsAgentAndMcpPreparation() {
        cancelledAndExternallySettled();
        when(inputs.pending(scope, "turn", 0, 256)).thenReturn(List.of());
        try (var projections = mockStatic(SessionProjection.class);
                var cancellation = mockStatic(ManagedNativeCancellation.class)) {
            projections
                    .when(() -> SessionProjection.read(log))
                    .thenReturn(new SessionProjection(42, null, Set.of(), Set.of()));
            assertTrue(turns.cancelBeforePreparation(execution));
            cancellation.verify(() -> ManagedNativeCancellation.settle(execution));
            verify(execution).finish("cancelled");
        }
    }
}
