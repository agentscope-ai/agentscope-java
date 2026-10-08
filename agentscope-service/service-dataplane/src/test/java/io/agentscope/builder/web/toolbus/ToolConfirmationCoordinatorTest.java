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
package io.agentscope.builder.web.toolbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.ControlPlaneClient.ManagedExecutionScope;
import io.agentscope.builder.web.coord.CoordinationStore;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.ManagedTurnContext;
import io.agentscope.builder.web.managed.SessionEventDto;
import io.agentscope.builder.web.managed.SessionEventTypes;
import io.agentscope.builder.web.managed.service.SessionEventLog;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ToolConfirmationCoordinatorTest {

    private final SessionEventLog eventLog = mock(SessionEventLog.class);
    private final DataSessionService sessionService = mock(DataSessionService.class);
    private final CoordinationStore store = mock(CoordinationStore.class);
    private final ControlPlaneClient controlPlaneClient = mock(ControlPlaneClient.class);
    private final AtomicReference<CoordinationStore.HitlTicket> ticket = new AtomicReference<>();
    private final AtomicReference<CoordinationStore.LeaseHandle> turnLease =
            new AtomicReference<>();
    private ToolConfirmationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator =
                new ToolConfirmationCoordinator(
                        eventLog, sessionService, store, controlPlaneClient, 60_000L);
        when(sessionService.requireById("session-a"))
                .thenReturn(
                        new ManagedSessionDto(
                                "session-a",
                                "owner-a",
                                "agent-a",
                                "owner-a",
                                1,
                                "managed",
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of(),
                                "running",
                                null,
                                1,
                                1,
                                null));
        doAnswer(
                        invocation -> {
                            CoordinationStore.HitlTicket created = invocation.getArgument(0);
                            ticket.set(created);
                            return created;
                        })
                .when(store)
                .putHitlTicket(any());
        doAnswer(
                        invocation -> {
                            ticket.set(null);
                            return null;
                        })
                .when(store)
                .deleteHitlTicketsBySession(anyString());
        when(store.getHitlTicket(anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            CoordinationStore.HitlTicket current = ticket.get();
                            return current != null
                                            && current.sessionId().equals(invocation.getArgument(0))
                                            && current.toolUseId().equals(invocation.getArgument(1))
                                    ? Optional.of(current)
                                    : Optional.empty();
                        });
        when(store.getHitlTicket(any(CoordinationStore.HitlDecisionFence.class)))
                .thenAnswer(
                        invocation -> {
                            CoordinationStore.HitlTicket current = ticket.get();
                            return matches(current, invocation.getArgument(0))
                                    ? Optional.of(current)
                                    : Optional.empty();
                        });
        turnLease.set(
                new CoordinationStore.LeaseHandle(
                        "session-a",
                        "owner-a",
                        "instance-a",
                        System.currentTimeMillis(),
                        System.currentTimeMillis() + 60_000L));
        when(store.getTurnLease("session-a"))
                .thenAnswer(invocation -> Optional.ofNullable(turnLease.get()));
        when(store.resolveHitlTicket(any(), anyString(), anyLong(), anyBoolean(), any(), anyLong()))
                .thenAnswer(
                        invocation -> {
                            CoordinationStore.HitlDecisionFence fence = invocation.getArgument(0);
                            String status = invocation.getArgument(1);
                            long decisionVersion = invocation.getArgument(2);
                            boolean allow = invocation.getArgument(3);
                            String reason = invocation.getArgument(4);
                            long at = invocation.getArgument(5);
                            CoordinationStore.HitlTicket current = ticket.get();
                            if (!matches(current, fence)
                                    || (!current.managedTask() && current.expiresAt() <= at)) {
                                return Optional.empty();
                            }
                            if (current.resolvedAllow() != null) {
                                return current.resolvedAllow() == allow
                                                && status.equals(current.resolutionStatus())
                                                && decisionVersion == current.decisionVersion()
                                        ? Optional.of(
                                                new CoordinationStore.HitlResolution(
                                                        current, false))
                                        : Optional.empty();
                            }
                            CoordinationStore.HitlTicket resolved =
                                    copy(
                                            current,
                                            status,
                                            decisionVersion,
                                            allow,
                                            reason,
                                            at,
                                            false);
                            ticket.set(resolved);
                            return Optional.of(
                                    new CoordinationStore.HitlResolution(resolved, true));
                        });
        when(store.markHitlContinuationReady(any()))
                .thenAnswer(
                        invocation -> {
                            CoordinationStore.HitlTicket current = ticket.get();
                            if (!matches(current, invocation.getArgument(0))) {
                                return false;
                            }
                            ticket.set(
                                    copy(
                                            current,
                                            current.resolutionStatus(),
                                            current.decisionVersion(),
                                            current.resolvedAllow(),
                                            current.denyMessage(),
                                            current.resolvedAt(),
                                            true));
                            return true;
                        });
        SessionEventDto event =
                new SessionEventDto(
                        "event-a", "session-a", 1, "session.requires_action", Map.of(), 1L, 1);
        when(eventLog.append(eq("session-a"), eq(SessionEventTypes.SESSION_REQUIRES_ACTION), any()))
                .thenReturn(event);
        when(eventLog.appendLocal(
                        eq("session-a"),
                        eq(SessionEventTypes.SESSION_REQUIRES_ACTION),
                        any(),
                        eq(null)))
                .thenReturn(event);
        when(eventLog.appendIdempotent(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new SessionEventDto(
                                "event-decision",
                                "session-a",
                                2,
                                SessionEventTypes.USER_TOOL_CONFIRMATION,
                                Map.of(),
                                2L,
                                2));
        when(eventLog.appendIdempotentLocal(anyString(), anyString(), any(), anyString()))
                .thenReturn(
                        new SessionEventDto(
                                "event-decision",
                                "session-a",
                                2,
                                SessionEventTypes.USER_TOOL_CONFIRMATION,
                                Map.of(),
                                2L,
                                2));
    }

    @Test
    void managedTicketUsesDeterministicIdFullFenceAndSafePreview() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", deepInput(), "instance-a");

        CoordinationStore.HitlTicket created = ticket.get();
        assertThat(created.approvalId()).isEqualTo("3bd34c03-5a5a-5dbf-a015-e66363eaa457");
        assertThat(created.agentTaskId()).isEqualTo("task-a");
        assertThat(created.attemptId()).isEqualTo("attempt-a");
        assertThat(created.dispatchGeneration()).isEqualTo(3);
        assertThat(created.turnId()).isEqualTo("turn-a");
        assertThat(waiting).isNotDone();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(eventLog)
                .appendLocal(
                        eq("session-a"),
                        eq(SessionEventTypes.SESSION_REQUIRES_ACTION),
                        payload.capture(),
                        eq(null));
        assertThat(payload.getValue())
                .containsEntry("kind", "tool_confirmation")
                .containsEntry("schemaVersion", 1)
                .containsEntry("approvalId", created.approvalId());
        Map<?, ?> inputPreview = (Map<?, ?>) payload.getValue().get("inputPreview");
        assertThat(inputPreview.get("apiToken")).isEqualTo("[REDACTED]");
        assertThat(inputPreview.toString()).doesNotContain("deep-secret");
        assertThat(payload.getValue().get("inputSha256")).isInstanceOf(String.class);

        ToolConfirmationCoordinator.ManagedDecision stale =
                new ToolConfirmationCoordinator.ManagedDecision(
                        "session-a",
                        "tool-a",
                        created.approvalId(),
                        "task-a",
                        "attempt-old",
                        3,
                        "turn-a",
                        "approved",
                        2,
                        true,
                        null);
        assertThat(coordinator.resolveManaged(stale))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.FENCE_MISMATCH);
        assertThat(waiting).isNotDone();

        ToolConfirmationCoordinator.ManagedDecision approved =
                new ToolConfirmationCoordinator.ManagedDecision(
                        "session-a",
                        "tool-a",
                        created.approvalId(),
                        "task-a",
                        "attempt-a",
                        3,
                        "turn-a",
                        "approved",
                        2,
                        true,
                        null);
        assertThat(coordinator.resolveManaged(approved))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.RESOLVED);
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(ticket.get().continuationReady()).isTrue();
        assertThat(coordinator.resolveManaged(approved))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.IDEMPOTENT);
        assertThat(
                        coordinator.resolveManaged(
                                new ToolConfirmationCoordinator.ManagedDecision(
                                        "session-a",
                                        "tool-a",
                                        created.approvalId(),
                                        "task-a",
                                        "attempt-a",
                                        3,
                                        "turn-a",
                                        "rejected",
                                        3,
                                        false,
                                        "no")))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.CONFLICT);
    }

    @Test
    void personalDecisionCannotResolveAnotherSessionsTicket() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a")).thenReturn(null);
        CompletableFuture<Boolean> waiting =
                coordinator.requestConfirmation(
                        "session-a", "shared-tool-id", "read_file", Map.of());
        ToolConfirmationCoordinator otherReplica =
                new ToolConfirmationCoordinator(
                        eventLog, sessionService, store, controlPlaneClient, 60_000L);

        assertThat(coordinator.resolvePersonal("session-b", "shared-tool-id", true, null))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.NOT_FOUND);
        assertThat(waiting).isNotDone();
        assertThat(
                        otherReplica.resolvePersonal(
                                "session-a", "shared-tool-id", false, "user rejected"))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.RESOLVED);
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void managedTimeoutWaitsForControlPlaneCancelledDecision() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", Map.of("query", "weather"), "instance-a");
        CoordinationStore.HitlTicket created = ticket.get();

        assertThat(coordinator.expire("session-a", "tool-a", created.expiresAt() + 1))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.CONTROL_PLANE_REQUIRED);
        assertThat(waiting).isNotDone();
        ticket.set(withExpires(created, System.currentTimeMillis() - 1));

        ToolConfirmationCoordinator.ManagedDecision cancelled =
                new ToolConfirmationCoordinator.ManagedDecision(
                        "session-a",
                        "tool-a",
                        created.approvalId(),
                        "task-a",
                        "attempt-a",
                        3,
                        "turn-a",
                        "cancelled",
                        2,
                        false,
                        "timed_out");
        assertThat(coordinator.resolveManaged(cancelled))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.RESOLVED);
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
        assertThat(ticket.get().resolutionStatus()).isEqualTo("cancelled");

        ArgumentCaptor<Map<String, Object>> resolvedPayload = ArgumentCaptor.forClass(Map.class);
        verify(eventLog)
                .appendIdempotentLocal(
                        eq("session-a"),
                        eq(SessionEventTypes.USER_TOOL_CONFIRMATION),
                        resolvedPayload.capture(),
                        eq("evt_hitl_decision_" + created.approvalId()));
        assertThat(resolvedPayload.getValue()).containsEntry("status", "cancelled");
    }

    @Test
    void delayedApprovedOutboxDecisionRemainsValidAfterLocalDeadline() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", Map.of("query", "weather"), "instance-a");
        CoordinationStore.HitlTicket created = ticket.get();
        ticket.set(withExpires(created, System.currentTimeMillis() - 1));

        assertThat(
                        coordinator.resolveManaged(
                                new ToolConfirmationCoordinator.ManagedDecision(
                                        "session-a",
                                        "tool-a",
                                        created.approvalId(),
                                        "task-a",
                                        "attempt-a",
                                        3,
                                        "turn-a",
                                        "approved",
                                        2,
                                        true,
                                        null)))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.RESOLVED);
        assertThat(waiting.get(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void managedDecisionIsRejectedWhenTurnContinuationLeaseWasLost() {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", Map.of("query", "weather"), "instance-a");
        CoordinationStore.HitlTicket created = ticket.get();
        turnLease.set(null);

        assertThat(
                        coordinator.resolveManaged(
                                new ToolConfirmationCoordinator.ManagedDecision(
                                        "session-a",
                                        "tool-a",
                                        created.approvalId(),
                                        "task-a",
                                        "attempt-a",
                                        3,
                                        "turn-a",
                                        "approved",
                                        2,
                                        true,
                                        null)))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.CONTINUATION_LOST);
        assertThat(ticket.get().resolvedAllow()).isNull();
        assertThat(waiting).isNotDone();
    }

    @Test
    void replacementTurnLeaseCannotReleaseOldTicketAndMayReuseToolUseId() {
        ManagedExecutionScope oldScope =
                new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a");
        when(controlPlaneClient.managedExecutionScope("session-a")).thenReturn(oldScope);
        CompletableFuture<Boolean> oldWaiting =
                requestManaged("shared-tool", "web_search", Map.of("query", "old"), "instance-a");
        CoordinationStore.HitlTicket oldTicket = ticket.get();

        turnLease.set(
                new CoordinationStore.LeaseHandle(
                        "session-a", "owner-a", "instance-b", 2L, Long.MAX_VALUE));
        assertThat(
                        coordinator.resolveManaged(
                                new ToolConfirmationCoordinator.ManagedDecision(
                                        "session-a",
                                        "shared-tool",
                                        oldTicket.approvalId(),
                                        "task-a",
                                        "attempt-a",
                                        3,
                                        "turn-a",
                                        "approved",
                                        2,
                                        true,
                                        null)))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.CONTINUATION_LOST);

        coordinator.cancelSession("session-a", "new_turn_admitted");
        assertThat(oldWaiting.join()).isFalse();
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-b", 4, "turn-b"));
        CompletableFuture<Boolean> newWaiting =
                requestManaged("shared-tool", "web_search", Map.of("query", "new"), "instance-b");

        assertThat(ticket.get().attemptId()).isEqualTo("attempt-b");
        assertThat(ticket.get().toolUseId()).isEqualTo("shared-tool");
        assertThat(newWaiting).isNotDone();
        assertThat(
                        coordinator.resolveManaged(
                                new ToolConfirmationCoordinator.ManagedDecision(
                                        "session-a",
                                        "shared-tool",
                                        oldTicket.approvalId(),
                                        "task-a",
                                        "attempt-a",
                                        3,
                                        "turn-a",
                                        "approved",
                                        2,
                                        true,
                                        null)))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.FENCE_MISMATCH);
    }

    @Test
    void incompleteManagedScopeFailsClosedAndSessionCancellationReleasesWaiter() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(new ManagedExecutionScope(null, "task-a", "attempt-a", 3, "turn-a"));

        assertThatThrownBy(
                        () ->
                                coordinator.requestConfirmation(
                                        "session-a",
                                        "tool-a",
                                        "web_search",
                                        Map.of(),
                                        new ManagedTurnContext(
                                                new ManagedExecutionScope(
                                                        null, "task-a", "attempt-a", 3, "turn-a"),
                                                "instance-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("complete immutable execution scope");

        when(controlPlaneClient.managedExecutionScope("session-a")).thenReturn(null);
        CompletableFuture<Boolean> waiting =
                coordinator.requestConfirmation(
                        "session-a", "tool-b", "web_search", Map.of("query", "weather"));
        coordinator.cancelSession("session-a", "remote_interrupt");

        assertThat(waiting.get(1, TimeUnit.SECONDS)).isFalse();
        verify(store).deleteHitlTicketsBySession("session-a");
    }

    @Test
    void staleControlPlaneMirrorNeverReleasesToolContinuation() {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        doAnswer(
                        invocation -> {
                            SessionEventDto event = invocation.getArgument(0);
                            if (SessionEventTypes.USER_TOOL_CONFIRMATION.equals(event.type())) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "attempt fence is stale");
                            }
                            return null;
                        })
                .when(controlPlaneClient)
                .appendSessionEvent(any(SessionEventDto.class), any(ManagedExecutionScope.class));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", Map.of("query", "weather"), "instance-a");
        CoordinationStore.HitlTicket created = ticket.get();

        assertThatThrownBy(
                        () ->
                                coordinator.resolveManaged(
                                        new ToolConfirmationCoordinator.ManagedDecision(
                                                "session-a",
                                                "tool-a",
                                                created.approvalId(),
                                                "task-a",
                                                "attempt-a",
                                                3,
                                                "turn-a",
                                                "approved",
                                                2,
                                                true,
                                                null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(waiting).isNotDone();
        assertThat(ticket.get().continuationReady()).isFalse();

        coordinator.cancelSession("session-a", "managed_attempt_stale");
        assertThat(waiting.join()).isFalse();
    }

    @Test
    void oldTurnCannotAdoptReplacementScopeOrLeaseWhenEnteringApproval() {
        ManagedExecutionScope oldScope =
                new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a");
        ManagedExecutionScope replacementScope =
                new ManagedExecutionScope("tenant-a", "task-a", "attempt-b", 4, "turn-b");
        when(controlPlaneClient.managedExecutionScope("session-a")).thenReturn(replacementScope);
        turnLease.set(
                new CoordinationStore.LeaseHandle(
                        "session-a", "owner-a", "instance-b", 2L, Long.MAX_VALUE));

        assertThatThrownBy(
                        () ->
                                coordinator.requestConfirmation(
                                        "session-a",
                                        "shared-tool",
                                        "web_search",
                                        Map.of("query", "old"),
                                        new ManagedTurnContext(oldScope, "instance-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
        verify(store, never()).putHitlTicket(any());

        CompletableFuture<Boolean> replacementWaiting =
                coordinator.requestConfirmation(
                        "session-a",
                        "shared-tool",
                        "web_search",
                        Map.of("query", "new"),
                        new ManagedTurnContext(replacementScope, "instance-b"));
        assertThat(ticket.get().attemptId()).isEqualTo("attempt-b");
        assertThat(replacementWaiting).isNotDone();
    }

    @Test
    void nonOwnerPersistsDecisionButOwnerPollerAloneReleasesContinuation() throws Exception {
        when(controlPlaneClient.managedExecutionScope("session-a"))
                .thenReturn(
                        new ManagedExecutionScope("tenant-a", "task-a", "attempt-a", 3, "turn-a"));
        CompletableFuture<Boolean> waiting =
                requestManaged("tool-a", "web_search", Map.of("query", "q"), "instance-a");
        CoordinationStore.HitlTicket created = ticket.get();
        ToolConfirmationCoordinator nonOwner =
                new ToolConfirmationCoordinator(
                        eventLog, sessionService, store, controlPlaneClient, 60_000L);
        ToolConfirmationCoordinator.ManagedDecision approved =
                new ToolConfirmationCoordinator.ManagedDecision(
                        "session-a",
                        "tool-a",
                        created.approvalId(),
                        "task-a",
                        "attempt-a",
                        3,
                        "turn-a",
                        "approved",
                        2,
                        true,
                        null);

        assertThat(nonOwner.resolveManaged(approved))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.OWNER_UNAVAILABLE);
        assertThat(ticket.get().resolvedAllow()).isTrue();
        assertThat(ticket.get().continuationReady()).isFalse();

        assertThat(waiting.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(ticket.get().continuationReady()).isTrue();
        assertThat(nonOwner.resolveManaged(approved))
                .isEqualTo(ToolConfirmationCoordinator.DecisionResult.IDEMPOTENT);
    }

    private static boolean matches(
            CoordinationStore.HitlTicket ticket, CoordinationStore.HitlDecisionFence fence) {
        return ticket != null
                && ticket.sessionId().equals(fence.sessionId())
                && ticket.toolUseId().equals(fence.toolUseId())
                && same(ticket.approvalId(), fence.approvalId())
                && same(ticket.agentTaskId(), fence.agentTaskId())
                && same(ticket.attemptId(), fence.attemptId())
                && ticket.dispatchGeneration() == fence.dispatchGeneration()
                && same(ticket.turnId(), fence.turnId());
    }

    private CompletableFuture<Boolean> requestManaged(
            String toolUseId,
            String toolName,
            Map<String, Object> input,
            String continuationLeaseId) {
        return coordinator.requestConfirmation(
                "session-a",
                toolUseId,
                toolName,
                input,
                new ManagedTurnContext(
                        controlPlaneClient.managedExecutionScope("session-a"),
                        continuationLeaseId));
    }

    private static CoordinationStore.HitlTicket copy(
            CoordinationStore.HitlTicket source,
            String status,
            long decisionVersion,
            Boolean allow,
            String reason,
            Long resolvedAt,
            boolean ready) {
        return new CoordinationStore.HitlTicket(
                source.toolUseId(),
                source.sessionId(),
                source.ownerId(),
                source.approvalId(),
                source.agentTaskId(),
                source.attemptId(),
                source.dispatchGeneration(),
                source.turnId(),
                source.continuationLeaseId(),
                source.toolName(),
                source.inputJson(),
                status,
                decisionVersion,
                allow,
                reason,
                source.createdAt(),
                source.expiresAt(),
                resolvedAt,
                ready);
    }

    private static CoordinationStore.HitlTicket withExpires(
            CoordinationStore.HitlTicket source, long expiresAt) {
        return new CoordinationStore.HitlTicket(
                source.toolUseId(),
                source.sessionId(),
                source.ownerId(),
                source.approvalId(),
                source.agentTaskId(),
                source.attemptId(),
                source.dispatchGeneration(),
                source.turnId(),
                source.continuationLeaseId(),
                source.toolName(),
                source.inputJson(),
                source.resolutionStatus(),
                source.decisionVersion(),
                source.resolvedAllow(),
                source.denyMessage(),
                source.createdAt(),
                expiresAt,
                source.resolvedAt(),
                source.continuationReady());
    }

    private static Map<String, Object> deepInput() {
        Map<String, Object> nested = Map.of("secret", "deep-secret");
        for (String key : List.of("d", "c", "b", "a")) {
            nested = Map.of(key, nested);
        }
        return Map.of("query", "weather", "apiToken", "must-not-leak", "nested", nested);
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
