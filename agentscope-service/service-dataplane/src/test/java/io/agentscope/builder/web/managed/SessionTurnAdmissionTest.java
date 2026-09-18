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
package io.agentscope.builder.web.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.web.catalog.HarnessAgentBuildService;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.coord.TurnLeaseService;
import io.agentscope.builder.web.managed.service.DeletedSessionRegistry;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import io.agentscope.builder.web.toolbus.ToolConfirmationCoordinator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A wake that cannot start a turn must leave no trace. The control plane retries a rejected wake
 * every couple of seconds for as long as the member stays busy, so anything recorded before the turn
 * lease is held would be written once per retry.
 */
class SessionTurnAdmissionTest {

    @Test
    void aRejectedWakeDoesNotRecordTheMessageItCouldNotDeliver() {
        AtomicInteger recorded = new AtomicInteger();
        SessionTurnRunner runner = runnerWithLease(busyLease());

        assertThatThrownBy(
                        () ->
                                runner.runTurnAsync(
                                        session(), "task-1 completed", recorded::incrementAndGet))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(recorded.get()).isZero();
    }

    @Test
    void localLeaseLossBeforeAdmissionIsNeverRequeuedAsAnUnfencedInterrupt() {
        CoordinationStore coordinationStore = mock(CoordinationStore.class);
        TurnLeaseService leases = mock(TurnLeaseService.class);
        when(leases.acquireOrConflictFenced(anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            Consumer<CoordinationStore.TurnInterruptRequest> callback =
                                    invocation.getArgument(2);
                            callback.accept(
                                    new CoordinationStore.TurnInterruptRequest(
                                            "turn_lease_lost", null));
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "lease lost before admission");
                        });
        SessionTurnRunner runner =
                runnerWithLease(
                        leases,
                        mock(ControlPlaneClient.class),
                        mock(ToolConfirmationCoordinator.class),
                        coordinationStore);

        assertThatThrownBy(() -> runner.runTurnAsync(session(), "message", () -> {}))
                .isInstanceOf(ResponseStatusException.class);
        verify(coordinationStore, never()).requestTurnInterrupt(anyString(), anyString());
    }

    @Test
    void anAdmittedTurnRecordsTheMessageThatStartedIt() {
        AtomicInteger recorded = new AtomicInteger();
        SessionTurnRunner runner = runnerWithLease(freeLease());

        runner.runTurnAsync(session(), "task-1 completed", recorded::incrementAndGet);

        assertThat(recorded.get()).isEqualTo(1);
    }

    @Test
    void delayedOldHeartbeatFailureDoesNotCancelFreshTurn() {
        ControlPlaneClient controlPlaneClient = mock(ControlPlaneClient.class);
        ToolConfirmationCoordinator confirmationCoordinator =
                mock(ToolConfirmationCoordinator.class);
        ControlPlaneClient.ManagedExecutionScope oldScope =
                new ControlPlaneClient.ManagedExecutionScope(
                        "tenant-a", "task-a", "attempt-a", 3, "turn-a");
        when(controlPlaneClient.managedExecutionScope("sess_lead"))
                .thenReturn(
                        new ControlPlaneClient.ManagedExecutionScope(
                                "tenant-a", "task-a", "attempt-b", 4, "turn-b"));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "attempt fenced"))
                .when(controlPlaneClient)
                .heartbeatManagedExecution("sess_lead", oldScope);
        SessionTurnRunner runner =
                runnerWithLease(freeLease(), controlPlaneClient, confirmationCoordinator);

        runner.heartbeatManagedExecution(
                "sess_lead", oldScope, mock(TurnLeaseService.TurnLease.class));

        verify(confirmationCoordinator, never()).cancelSession(anyString(), anyString());
    }

    @Test
    void transientManagedHeartbeatFailureKeepsTurnAlive() {
        ControlPlaneClient controlPlaneClient = mock(ControlPlaneClient.class);
        ToolConfirmationCoordinator confirmationCoordinator =
                mock(ToolConfirmationCoordinator.class);
        ControlPlaneClient.ManagedExecutionScope scope =
                new ControlPlaneClient.ManagedExecutionScope(
                        "tenant-a", "task-a", "attempt-a", 3, "turn-a");
        doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "temporary outage"))
                .when(controlPlaneClient)
                .heartbeatManagedExecution("sess_lead", scope);
        SessionTurnRunner runner =
                runnerWithLease(freeLease(), controlPlaneClient, confirmationCoordinator);

        runner.heartbeatManagedExecution(
                "sess_lead", scope, mock(TurnLeaseService.TurnLease.class));

        verify(confirmationCoordinator, never()).cancelSession(anyString(), anyString());
    }

    @Test
    void staleTurnFinishingBuildCannotOverwriteReplacementCancellationHandles() throws Exception {
        TurnLeaseService leases = mock(TurnLeaseService.class);
        TurnLeaseService.TurnLease leaseA = mock(TurnLeaseService.TurnLease.class);
        TurnLeaseService.TurnLease leaseB = mock(TurnLeaseService.TurnLease.class);
        when(leaseA.coordinationId()).thenReturn("lease-a");
        when(leaseB.coordinationId()).thenReturn("lease-b");
        java.util.ArrayList<Consumer<CoordinationStore.TurnInterruptRequest>> callbacks =
                new java.util.ArrayList<>();
        AtomicInteger acquisitions = new AtomicInteger();
        when(leases.acquireOrConflictFenced(anyString(), anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            callbacks.add(invocation.getArgument(2));
                            return acquisitions.getAndIncrement() == 0 ? leaseA : leaseB;
                        });
        ControlPlaneClient controlPlane = mock(ControlPlaneClient.class);
        ControlPlaneClient.ManagedExecutionScope scopeA =
                new ControlPlaneClient.ManagedExecutionScope(
                        "tenant-a", "task-a", "attempt-a", 3, "turn-a");
        ControlPlaneClient.ManagedExecutionScope scopeB =
                new ControlPlaneClient.ManagedExecutionScope(
                        "tenant-a", "task-a", "attempt-b", 4, "turn-b");
        when(controlPlane.beginManagedExecution("sess_lead")).thenReturn(scopeA, scopeB);
        HarnessAgentBuildService builds = mock(HarnessAgentBuildService.class);
        HarnessAgent agentA = mock(HarnessAgent.class);
        HarnessAgent agentB = mock(HarnessAgent.class);
        CountDownLatch buildAEntered = new CountDownLatch(1);
        CountDownLatch releaseBuildA = new CountDownLatch(1);
        CountDownLatch replacementSubscribed = new CountDownLatch(1);
        AtomicInteger buildCalls = new AtomicInteger();
        when(builds.getOrBuildAgent(any(), any()))
                .thenAnswer(
                        invocation -> {
                            if (buildCalls.incrementAndGet() == 1) {
                                buildAEntered.countDown();
                                releaseBuildA.await(2, TimeUnit.SECONDS);
                                return agentA;
                            }
                            return agentB;
                        });
        when(agentB.streamEvents(
                        org.mockito.ArgumentMatchers.<List<Msg>>any(), any(RuntimeContext.class)))
                .thenAnswer(
                        invocation -> {
                            replacementSubscribed.countDown();
                            return reactor.core.publisher.Flux.never();
                        });
        HandsLeaseService hands = mock(HandsLeaseService.class);
        when(hands.acquire(any(), any())).thenReturn(Optional.empty());
        CoordinationStore coordinationStore = mock(CoordinationStore.class);
        SessionTurnRunner runner =
                new SessionTurnRunner(
                        builds,
                        mock(DataSessionService.class),
                        mock(SessionEventLog.class),
                        mock(SessionEventMapper.class),
                        mock(SessionEventPreviewBus.class),
                        mock(DataEnvironmentService.class),
                        hands,
                        leases,
                        coordinationStore,
                        new DeletedSessionRegistry(),
                        controlPlane,
                        mock(ToolConfirmationCoordinator.class));

        runner.runTurnAsync(session(), "old", () -> {});
        assertThat(buildAEntered.await(2, TimeUnit.SECONDS)).isTrue();
        runner.runTurnAsync(session(), "replacement", () -> {});
        assertThat(replacementSubscribed.await(2, TimeUnit.SECONDS)).isTrue();
        releaseBuildA.countDown();
        Thread.sleep(100L);
        callbacks
                .get(0)
                .accept(new CoordinationStore.TurnInterruptRequest("turn_lease_lost", null));

        verify(agentB, never()).interrupt();
        verify(coordinationStore, never()).requestTurnInterrupt(anyString(), anyString());
        runner.interrupt("sess_lead");
    }

    private static TurnLeaseService busyLease() {
        TurnLeaseService leases = mock(TurnLeaseService.class);
        when(leases.acquireOrConflictFenced(anyString(), anyString(), any()))
                .thenThrow(
                        new ResponseStatusException(
                                HttpStatus.CONFLICT, "Session turn already in progress"));
        return leases;
    }

    private static TurnLeaseService freeLease() {
        TurnLeaseService leases = mock(TurnLeaseService.class);
        when(leases.acquireOrConflictFenced(anyString(), anyString(), any()))
                .thenReturn(mock(TurnLeaseService.TurnLease.class));
        return leases;
    }

    private static SessionTurnRunner runnerWithLease(TurnLeaseService leases) {
        return runnerWithLease(
                leases, mock(ControlPlaneClient.class), mock(ToolConfirmationCoordinator.class));
    }

    private static SessionTurnRunner runnerWithLease(
            TurnLeaseService leases,
            ControlPlaneClient controlPlaneClient,
            ToolConfirmationCoordinator confirmationCoordinator) {
        return runnerWithLease(
                leases, controlPlaneClient, confirmationCoordinator, mock(CoordinationStore.class));
    }

    private static SessionTurnRunner runnerWithLease(
            TurnLeaseService leases,
            ControlPlaneClient controlPlaneClient,
            ToolConfirmationCoordinator confirmationCoordinator,
            CoordinationStore coordinationStore) {
        return new SessionTurnRunner(
                mock(HarnessAgentBuildService.class),
                mock(DataSessionService.class),
                mock(SessionEventLog.class),
                mock(SessionEventMapper.class),
                mock(SessionEventPreviewBus.class),
                mock(DataEnvironmentService.class),
                mock(HandsLeaseService.class),
                leases,
                coordinationStore,
                new DeletedSessionRegistry(),
                controlPlaneClient,
                confirmationCoordinator);
    }

    private static ManagedSessionDto session() {
        return new ManagedSessionDto(
                "sess_lead",
                "user_1",
                "agt_lead",
                "user_1",
                1,
                null,
                null,
                null,
                "agent-task|f92cf745-82f5-45f3-a273-8ad96a87aa5a",
                List.of(),
                List.of(),
                List.of(),
                "idle",
                Map.of(),
                0L,
                0L,
                null);
    }
}
