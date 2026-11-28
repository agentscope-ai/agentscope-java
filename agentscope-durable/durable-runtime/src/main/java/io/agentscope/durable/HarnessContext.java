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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableTypes.Commit;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.Frame;
import io.agentscope.durable.DurableTypes.Lease;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.LiveEventInput;
import io.agentscope.durable.DurableTypes.RunView;
import io.agentscope.durable.DurableTypes.Signal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Invocation-scoped fenced access, never stored in a reusable Agent field. */
public final class HarnessContext {
    private final SessionBackend backend;
    private final Lease lease;
    private final AtomicBoolean stopped;
    private final LiveEvents liveEvents;
    private final BooleanSupplier draining;
    private final String invocationRequest = UUID.randomUUID().toString();
    private RunView invocation;

    public HarnessContext(SessionBackend backend, Lease lease, AtomicBoolean stopped) {
        this(backend, lease, stopped, LiveEvents.none());
    }

    HarnessContext(
            SessionBackend backend, Lease lease, AtomicBoolean stopped, LiveEvents liveEvents) {
        this(backend, lease, stopped, liveEvents, () -> false);
    }

    HarnessContext(
            SessionBackend backend,
            Lease lease,
            AtomicBoolean stopped,
            LiveEvents liveEvents,
            BooleanSupplier draining) {
        this.backend = Objects.requireNonNull(backend);
        this.lease = Objects.requireNonNull(lease);
        this.stopped = Objects.requireNonNull(stopped);
        this.liveEvents = Objects.requireNonNull(liveEvents);
        this.draining = Objects.requireNonNull(draining);
    }

    public Lease lease() {
        return lease;
    }

    /** The host is yielding ownership. Finish a durable phase and return without pausing the Turn. */
    public boolean draining() {
        return draining.getAsBoolean();
    }

    /** Prepare immediately before one native call; retries keep the invocation's start identity. */
    public synchronized RunView prepareRun() {
        check();
        if (invocation != null) return invocation;
        try {
            invocation = backend.prepareRun(lease, invocationRequest);
        } catch (DurableException uncertain) {
            if (uncertain.code() != DurableException.Code.UNAVAILABLE) throw uncertain;
            try {
                invocation = backend.prepareRun(lease, invocationRequest);
            } catch (RuntimeException unresolved) {
                stopped.set(true);
                throw unresolved;
            }
        }
        return invocation;
    }

    /** Atomically seal a model generation before allowing its result to execute tools. */
    public void sealModelReply(DurableTypes.Signal signal) {
        check();
        backend.signal(lease.thread(), lease.turnId(), signal);
    }

    /** Discard stale host evidence without marking it as applied. */
    public void rejectNativeToolReconciliation(Signal signal) {
        check();
        backend.rejectNativeToolReconciliation(lease, signal.id());
    }

    /** Optional change hints; callers must still repair lost hints by reading durable state. */
    public Wakeups wakeups() {
        check();
        return backend.wakeups();
    }

    public Frame frame() {
        check();
        var frame = backend.frame(lease);
        if (!frame.checkpoint().isNull()) {
            if (frame.checkpointMetadata() == null)
                throw new DurableException(
                        DurableException.Code.INCOMPATIBLE,
                        "Native checkpoint metadata is missing");
            frame.checkpointMetadata().verify(frame.definition(), frame.checkpoint());
        } else if (frame.checkpointMetadata() != null) {
            throw new DurableException(
                    DurableException.Code.INVALID, "Native checkpoint is missing");
        }
        return frame;
    }

    public List<JsonNode> journal(long after, int limit) {
        check();
        return backend.journal(lease, after, limit);
    }

    public CommitReceipt commit(Commit command) {
        check();
        try {
            return backend.commit(lease, command);
        } catch (DurableException uncertain) {
            if (uncertain.code() != DurableException.Code.UNAVAILABLE) throw uncertain;
            try {
                var receipt = backend.receipt(lease.thread(), lease.turnId(), command.commitId());
                if (receipt.isPresent()) {
                    if (!receipt.get().digest().equals(DurableJson.hash(command)))
                        throw new DurableException(
                                DurableException.Code.CONFLICT, "Commit identity content changed");
                    return receipt.get();
                }
                return backend.commit(lease, command);
            } catch (RuntimeException unresolved) {
                // No further native writes or effects until a new owner reads authoritative state.
                stopped.set(true);
                throw unresolved;
            }
        }
    }

    /**
     * Publish presentation signals only after the native commit that produced them succeeded.
     * Delivery is best effort and can never change an acknowledged durable outcome.
     */
    public void publish(List<LiveEventInput> events) {
        for (var input : List.copyOf(events)) {
            try {
                liveEvents.publish(
                        new LiveEvent(
                                "1",
                                input.eventId(),
                                lease.session(),
                                lease.thread().threadId(),
                                lease.turnId(),
                                input.runId(),
                                input.stepId(),
                                input.occurredAt(),
                                input.type(),
                                input.data()));
            } catch (RuntimeException error) {
                System.getLogger(HarnessContext.class.getName())
                        .log(
                                System.Logger.Level.WARNING,
                                "Live event delivery failed; durable execution continues",
                                error);
            }
        }
    }

    public JsonNode resource(SessionResources.Request request) {
        check();
        try {
            return backend.resource(lease, request);
        } catch (DurableException uncertain) {
            if (uncertain.code() != DurableException.Code.UNAVAILABLE) throw uncertain;
            try {
                return backend.resource(lease, request);
            } catch (RuntimeException unresolved) {
                stopped.set(true);
                throw unresolved;
            }
        }
    }

    /** Stop this native attempt; the runtime releases ownership and recovery reads committed state. */
    public void stop() {
        stopped.set(true);
    }

    public void check() {
        if (stopped.get() || Thread.currentThread().isInterrupted())
            throw new DurableException(DurableException.Code.FENCED, "Invocation stopped");
    }
}
