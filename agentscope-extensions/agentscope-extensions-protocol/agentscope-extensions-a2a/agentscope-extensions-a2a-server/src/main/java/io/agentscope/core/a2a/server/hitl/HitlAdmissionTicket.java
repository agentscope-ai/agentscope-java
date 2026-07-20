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

package io.agentscope.core.a2a.server.hitl;

import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.spec.MessageSendParams;

/** Single-owner handoff between synchronous request admission and asynchronous execution. */
public final class HitlAdmissionTicket {

    private static final String CONTEXT_KEY = HitlAdmissionTicket.class.getName() + ".ticket";

    public enum Operation {
        NORMAL,
        RESUME,
        CANCEL
    }

    enum State {
        PREPARED,
        EXECUTION_OWNED,
        CLOSED,
        ABORTED
    }

    private final ResolvedAgentRequestMetadata request;
    private final Operation operation;
    private final List<Msg> preparedMessages;
    private final String claimedHandoffId;
    private final HitlClaimRequest deferredClaim;
    private final HitlEncodingContext encodingContext;
    private final HitlLeaseHandle lease;
    private final HitlResumeCoordinator coordinator;
    private final AtomicReference<State> state = new AtomicReference<>(State.PREPARED);
    private final AtomicBoolean claimCommitted;
    private final AtomicBoolean claimAttempted = new AtomicBoolean();
    private final AtomicBoolean recoveryTransitioned = new AtomicBoolean();

    HitlAdmissionTicket(
            ResolvedAgentRequestMetadata request,
            Operation operation,
            List<Msg> preparedMessages,
            String claimedHandoffId,
            HitlEncodingContext encodingContext,
            HitlLeaseHandle lease,
            HitlResumeCoordinator coordinator) {
        this(
                request,
                operation,
                preparedMessages,
                claimedHandoffId,
                null,
                encodingContext,
                lease,
                coordinator);
    }

    private HitlAdmissionTicket(
            ResolvedAgentRequestMetadata request,
            Operation operation,
            List<Msg> preparedMessages,
            String claimedHandoffId,
            HitlClaimRequest deferredClaim,
            HitlEncodingContext encodingContext,
            HitlLeaseHandle lease,
            HitlResumeCoordinator coordinator) {
        this.request = request;
        this.operation = operation;
        this.preparedMessages = preparedMessages == null ? null : List.copyOf(preparedMessages);
        this.claimedHandoffId = claimedHandoffId;
        this.deferredClaim = deferredClaim;
        this.encodingContext = encodingContext;
        this.lease = lease;
        this.coordinator = coordinator;
        this.claimCommitted = new AtomicBoolean(claimedHandoffId != null && deferredClaim == null);
    }

    public static HitlAdmissionTicket normal(
            ResolvedAgentRequestMetadata request,
            HitlEncodingContext encodingContext,
            HitlLeaseHandle lease,
            HitlResumeCoordinator coordinator) {
        return new HitlAdmissionTicket(
                request, Operation.NORMAL, null, null, encodingContext, lease, coordinator);
    }

    static HitlAdmissionTicket cancel(
            ResolvedAgentRequestMetadata request,
            HitlClaimRequest deferredClaim,
            HitlLeaseHandle lease,
            HitlResumeCoordinator coordinator) {
        return new HitlAdmissionTicket(
                request,
                Operation.CANCEL,
                List.of(),
                deferredClaim.handoffId(),
                deferredClaim,
                null,
                lease,
                coordinator);
    }

    public void attach(ServerCallContext callContext) {
        if (callContext == null || callContext.getState() == null) {
            throw new IllegalStateException(
                    "ServerCallContext state is required for HITL admission");
        }
        Object existing = callContext.getState().putIfAbsent(CONTEXT_KEY, this);
        if (existing != null && existing != this) {
            throw new IllegalStateException("HITL admission ticket is already attached");
        }
    }

    public static Optional<HitlAdmissionTicket> find(RequestContext context) {
        ServerCallContext callContext = context == null ? null : context.getCallContext();
        if (callContext == null || callContext.getState() == null) {
            return Optional.empty();
        }
        Object candidate = callContext.getState().get(CONTEXT_KEY);
        return candidate instanceof HitlAdmissionTicket ticket
                ? Optional.of(ticket)
                : Optional.empty();
    }

    public HitlAdmissionTicket take(RequestContext context) {
        if (!request.matches(context)) {
            throw new IllegalStateException("HITL admission ticket coordinates changed");
        }
        if (!state.compareAndSet(State.PREPARED, State.EXECUTION_OWNED)) {
            throw new IllegalStateException("HITL admission ticket is no longer available");
        }
        request.bind(context);
        return this;
    }

    public boolean abort() {
        if (!state.compareAndSet(State.PREPARED, State.ABORTED)) {
            return false;
        }
        try {
            markRecoveryRequired();
        } finally {
            closeLease();
        }
        return true;
    }

    /** Atomically consume a deferred cancel credential after executor ownership is established. */
    public void claimForExecution() {
        if (deferredClaim == null) {
            return;
        }
        if (state.get() != State.EXECUTION_OWNED || !claimAttempted.compareAndSet(false, true)) {
            throw new IllegalStateException("HITL cancel claim is not available");
        }
        coordinator.claim(deferredClaim);
        claimCommitted.set(true);
    }

    /** Commit a claimed cancel only after the SDK terminal event has been emitted. */
    public void completeCancel() {
        if (operation != Operation.CANCEL || !claimCommitted.get()) {
            throw new IllegalStateException("HITL cancel has not been claimed");
        }
        coordinator.transition(
                claimedHandoffId, HitlHandoffStatus.CLAIMED, HitlHandoffStatus.CANCELED);
    }

    public void markRecoveryRequired() {
        if (claimedHandoffId == null
                || !claimCommitted.get()
                || coordinator == null
                || !recoveryTransitioned.compareAndSet(false, true)) {
            return;
        }
        try {
            coordinator.transition(
                    claimedHandoffId,
                    HitlHandoffStatus.CLAIMED,
                    HitlHandoffStatus.RECOVERY_REQUIRED);
        } catch (HitlResumeRejectedException ignored) {
            // The encoder may already have committed a more specific terminal state.
        }
    }

    public void closeExecution() {
        if (state.compareAndSet(State.EXECUTION_OWNED, State.CLOSED)) {
            closeLease();
        }
    }

    private void closeLease() {
        if (lease != null) {
            lease.close();
        }
    }

    public MessageSendParams requestParams() {
        return request.requestParams();
    }

    public ResolvedAgentRequestMetadata request() {
        return request;
    }

    public Operation operation() {
        return operation;
    }

    public List<Msg> preparedMessages() {
        return preparedMessages;
    }

    public String claimedHandoffId() {
        return claimedHandoffId;
    }

    public HitlEncodingContext encodingContext() {
        return encodingContext;
    }

    public HitlLeaseHandle lease() {
        return lease;
    }

    State state() {
        return state.get();
    }
}
