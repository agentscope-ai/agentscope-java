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

import io.agentcore.managed.server.runtime.managed.ManagedRuntimeBatch.InputReceipt;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionLogException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Core's SessionLog SPI uses HarnessRuntime's epoch-fenced, durably committed native prefix. */
public final class ManagedMqSessionLog implements SessionLog {
    private static final Logger log = LoggerFactory.getLogger(ManagedMqSessionLog.class);
    private final ManagedScope scope;
    private final String turn;
    private final long epoch;
    private final ManagedExecutionGuard guard;
    private final ManagedJournalStore journal;
    private final ManagedRuntimeWriter commits;
    private final ManagedLocalJournal local;
    private Writer writer;

    public ManagedMqSessionLog(
            ManagedScope scope,
            String turn,
            long epoch,
            ManagedExecutionGuard guard,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits) {
        this(
                scope,
                turn,
                epoch,
                guard,
                journal,
                commits,
                guard == null ? null : new ManagedLocalJournal(scope, turn, journal));
    }

    ManagedMqSessionLog(
            ManagedScope scope,
            String turn,
            long epoch,
            ManagedExecutionGuard guard,
            ManagedJournalStore journal,
            ManagedRuntimeWriter commits,
            ManagedLocalJournal local) {
        this.scope = scope;
        this.turn = turn;
        this.epoch = epoch;
        this.guard = guard;
        this.journal = journal;
        this.commits = commits;
        this.local = local;
    }

    @Override
    public Head head() {
        var head = local == null ? journal.head(scope) : local.head();
        return new Head(
                head.sequence(),
                head.epoch(),
                writer == null ? head.token() : writer.owner(),
                guard == null ? 0 : System.currentTimeMillis() + guard.remaining().toMillis(),
                head.commitId());
    }

    @Override
    public synchronized Writer acquire(String owner, Duration lease) {
        ManagedScope.requireText(owner, "writer owner");
        ManagedMessageBus.positiveMillis(lease);
        if (guard == null) throw new SessionLogException("Reader has no active Managed execution");
        guard.check();
        if (writer != null && !writer.owner().equals(owner))
            throw new SessionLogException("Active execution already has a native writer");
        writer = new Writer(owner, epoch);
        return writer;
    }

    @Override
    public synchronized void renew(Writer candidate, Duration lease) {
        ManagedMessageBus.positiveMillis(lease);
        validate(candidate);
        // Core's flush validates the one existing guard. It never creates a second renewal loop.
        guard.check();
    }

    @Override
    public synchronized void release(Writer candidate) {
        if (Objects.equals(writer, candidate)) writer = null;
    }

    @Override
    public synchronized Head commit(
            Writer candidate, String batchId, long expectedSeq, List<SessionEvent> events) {
        validate(candidate);
        try {
            var required = new ArrayList<SessionEvent>();
            long sequence = expectedSeq;
            for (var event : events) {
                if (ManagedSessionLogArchive.diagnostic(event)) {
                    journal.archive(event, scope);
                    continue;
                }
                required.add(
                        new SessionEvent(
                                event.schemaVersion(),
                                event.eventId(),
                                ++sequence,
                                event.occurredAt(),
                                event.type(),
                                event.executionRunId(),
                                event.turnId() == null ? turn : event.turnId(),
                                event.required(),
                                event.payloadJson()));
            }
            // A diagnostic-only flush acknowledges no execution progress and performs no SQL.
            if (required.isEmpty())
                return new Head(
                        expectedSeq,
                        epoch,
                        candidate.owner(),
                        System.currentTimeMillis() + guard.remaining().toMillis(),
                        batchId);
            var batch =
                    new ManagedRuntimeBatch(
                            scope,
                            turn,
                            epoch,
                            guard.token(),
                            candidate.owner(),
                            batchId,
                            expectedSeq,
                            required.stream()
                                    .map(
                                            event ->
                                                    event.turnId() != null
                                                            ? event
                                                            : new SessionEvent(
                                                                    event.schemaVersion(),
                                                                    event.eventId(),
                                                                    event.seq(),
                                                                    event.occurredAt(),
                                                                    event.type(),
                                                                    event.executionRunId(),
                                                                    turn,
                                                                    event.required(),
                                                                    event.payloadJson()))
                                    .toList(),
                            List.of(),
                            inputReceipts(required),
                            null);
            var receipt = local.publish(batch, commits, guard);
            for (var event : events)
                if ("recovery/applied".equals(event.type()))
                    ManagedRuntimeLog.info(
                            log,
                            "harness_runtime.recovery.applied",
                            scope,
                            turn,
                            "run_id",
                            event.executionRunId(),
                            "epoch",
                            epoch,
                            "native_sequence",
                            event.seq(),
                            "batch_id",
                            batchId);
            return new Head(
                    receipt.sequence(),
                    epoch,
                    candidate.owner(),
                    System.currentTimeMillis() + guard.remaining().toMillis(),
                    batchId);
        } catch (RuntimeException error) {
            throw new SessionLogException("Managed native commit failed", error);
        }
    }

    @Override
    public List<SessionEvent> readAfter(long after, int limit) {
        return local == null
                ? journal.recoveryEvents(scope, after, limit)
                : local.readAfter(after, limit);
    }

    @Override
    public List<SessionEvent> readMetadataAfter(long after, int limit) {
        return local == null
                ? journal.recoveryManifests(scope, after, limit)
                : local.readMetadataAfter(after, limit);
    }

    @Override
    public Iterable<SessionEvent> scanMetadata(long after, long through) {
        return local == null
                ? SessionLog.super.scanMetadata(after, through)
                : local.scanRecovery(after, through);
    }

    @Override
    public Iterable<SessionEvent> scan(long after, long through) {
        if (local == null) return SessionLog.super.scan(after, through);
        return () ->
                new Iterator<SessionEvent>() {
                    final Iterator<SessionEvent> source =
                            local.scanRecovery(after, through).iterator();

                    @Override
                    public boolean hasNext() {
                        return source.hasNext();
                    }

                    @Override
                    public SessionEvent next() {
                        return materialize(source.next());
                    }
                };
    }

    @Override
    public SessionEvent materialize(SessionEvent event) {
        return local == null ? journal.objects().hydrate(scope, event) : local.materialize(event);
    }

    private List<InputReceipt> inputReceipts(List<SessionEvent> events) {
        var receipts = new ArrayList<InputReceipt>();
        for (var event : events) {
            if ("inbox/applied".equals(event.type())
                    && event.data().get("commandIds") instanceof List<?> ids) {
                for (var id : ids)
                    receipts.add(new InputReceipt(String.valueOf(id), "applied", null));
            } else if ("inbox/rejected".equals(event.type())) {
                receipts.add(
                        new InputReceipt(
                                String.valueOf(event.data().get("commandId")),
                                "rejected",
                                String.valueOf(event.data().get("reason"))));
            }
        }
        return List.copyOf(receipts);
    }

    private void validate(Writer candidate) {
        if (guard == null || !Objects.equals(candidate, writer) || candidate.epoch() != epoch)
            throw new SessionLogException("Writer does not belong to this execution");
    }
}
