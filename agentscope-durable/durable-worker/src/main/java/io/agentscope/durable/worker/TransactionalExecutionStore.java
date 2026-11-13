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

package io.agentscope.durable.worker;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableProtocol;
import io.agentscope.durable.DurableTypes.ExecutionReconciliation;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/** Transactional Hands ledger and immutable, replayable source event outbox. */
public final class TransactionalExecutionStore implements ExecutionStore {
    private final RecordStore records;
    private final String prefix;

    public TransactionalExecutionStore(RecordStore records) {
        this.records = records;
        prefix = "execution:";
    }

    public TransactionalExecutionStore(RecordStore records, String pool) {
        this.records = records;
        prefix = "execution-pool:" + DurableJson.hash(DurableJson.text(pool, "pool")) + ":";
    }

    public static final class State {
        public int format = 3;
        public ExecutionRequest request;
        public String digest;
        public String owner;
        public long epoch;
        public long expiresAt;
        public long nextAttemptAt;
        public long createdAt;
        public long publicationPendingAt;
        public int consecutiveFailures;
        public boolean outputEnabled;
        public boolean attempted;
        public boolean cancelled;
        public long sequence;
        public long publishedSequence;
        public long outputOffset;
        public boolean outputSealed = true;
        public boolean outputConfigured;
        public ExecutionResult result =
                new ExecutionResult(ExecutionState.PENDING, null, null, null);
        @JsonIgnore public RecordStore.Transaction tx;
    }

    private String key(ExecutionRequest request) {
        return prefix + ExecutionMessages.identity(request);
    }

    private static State read(RecordStore.Transaction tx) {
        var value = tx.get("");
        if (value == null || value.isNull()) return null;
        var state = DurableJson.convert(value, State.class);
        if (state.format != 3)
            throw failure(DurableException.Code.INCOMPATIBLE, "Unknown execution format");
        state.tx = tx;
        return state;
    }

    private static ExecutionResult publicResult(State state) {
        return new ExecutionResult(
                state.result.state().terminal() && !state.outputSealed
                        ? ExecutionState.RUNNING
                        : state.result.state(),
                state.request.executionId(),
                state.outputSealed ? state.result.result() : null,
                state.result.error());
    }

    private void save(State state) {
        state.tx.put("", DurableJson.value(state));
        state.tx.schedule(
                "hands-events:" + prefix,
                state.request.executionId(),
                state.publishedSequence < state.sequence ? 0 : -1,
                DurableJson.value(null));
        boolean settled = state.result.state().terminal() && state.outputSealed;
        boolean recovery = state.attempted || state.cancelled;
        long readyAt = Math.max(state.expiresAt, state.nextAttemptAt);
        state.tx.schedule(
                "hands-start:" + prefix,
                state.request.executionId(),
                settled || recovery ? -1 : readyAt,
                DurableJson.value(null));
        state.tx.schedule(
                "hands-recovery:" + prefix,
                state.request.executionId(),
                settled || !recovery ? -1 : readyAt,
                DurableJson.value(null));
        state.tx.schedule(
                "hands:" + prefix,
                state.request.executionId(),
                state.result.state().terminal() && state.outputSealed
                        ? -1
                        : Math.max(state.expiresAt, state.nextAttemptAt),
                DurableJson.value(state.request.call().executor()));
    }

    private <T> T transact(String key, BiFunction<State, Long, T> change) {
        return records.transaction(
                key,
                tx -> {
                    var state = read(tx);
                    if (state == null)
                        throw failure(DurableException.Code.NOT_FOUND, "Execution not found");
                    var value = change.apply(state, tx.now());
                    save(state);
                    return value;
                });
    }

    private static void same(State state, ExecutionRequest request) {
        if (!state.digest.equals(DurableJson.hash(request)))
            throw failure(DurableException.Code.CONFLICT, "Execution identity content changed");
    }

    private static State create(RecordStore.Transaction tx, ExecutionRequest request) {
        var state = new State();
        state.tx = tx;
        state.createdAt = tx.now();
        state.request = request;
        state.digest = DurableJson.hash(request);
        emit(state, publicResult(state), null);
        return state;
    }

    private static String eventKey(long sequence) {
        return "event:" + String.format("%020d", sequence);
    }

    private static void emit(State state, ExecutionResult result, OutputPage output) {
        if (state.publishedSequence == state.sequence) state.publicationPendingAt = state.tx.now();
        if (output != null) state.tx.put("output:" + output.offset(), DurableJson.value(output));
        var event = ExecutionMessages.event(state.request, ++state.sequence, result, output);
        state.tx.put(eventKey(event.sequence()), DurableJson.value(event));
    }

    public ExecutionResult admit(ExecutionRequest request) {
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null) state = create(tx, request);
                    else same(state, request);
                    save(state);
                    return publicResult(state);
                });
    }

    public ExecutionResult query(ExecutionRequest request) {
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null) return ExecutionResult.missing();
                    same(state, request);
                    return publicResult(state);
                });
    }

    public ExecutionResult cancel(ExecutionRequest request) {
        receive(ExecutionMessages.command(Kind.CANCEL, request));
        return query(request);
    }

    private static void cancel(State state) {
        state.cancelled = true;
        state.nextAttemptAt = 0;
        if (!state.attempted && !state.result.state().terminal()) {
            state.result = new ExecutionResult(ExecutionState.CANCELLED, null, null, null);
            state.outputSealed = true;
            emit(state, publicResult(state), null);
        }
    }

    /** QUERY repairs missing source events; it never creates execution work. */
    public void receive(Command command) {
        ExecutionMessages.validate(command);
        records.transaction(
                key(command.request()),
                tx -> {
                    var state = read(tx);
                    if (state == null) {
                        if (command.kind() == Kind.QUERY)
                            throw failure(
                                    DurableException.Code.NOT_FOUND, "Execution not admitted");
                        state = create(tx, command.request());
                    } else same(state, command.request());
                    if (command.afterSequence() > state.sequence)
                        throw failure(
                                DurableException.Code.CONFLICT, "Replay cursor ahead of source");
                    if (command.kind() == Kind.CANCEL) cancel(state);
                    state.publishedSequence =
                            Math.min(state.publishedSequence, command.afterSequence());
                    save(state);
                    return null;
                });
    }

    public Optional<Event> pendingEvent(String key) {
        return records.transaction(
                key,
                tx -> {
                    var state = read(tx);
                    if (state == null || state.publishedSequence >= state.sequence)
                        return Optional.empty();
                    return Optional.of(
                            DurableJson.convert(
                                    tx.get(eventKey(state.publishedSequence + 1)), Event.class));
                });
    }

    public void published(Event event) {
        ExecutionMessages.validate(event);
        transact(
                key(event.request()),
                (state, now) -> {
                    same(state, event.request());
                    var original = state.tx.get(eventKey(event.sequence()));
                    if (!DurableJson.hash(event).equals(DurableJson.hash(original)))
                        throw failure(DurableException.Code.CONFLICT, "Published event changed");
                    if (event.sequence() == state.publishedSequence + 1) state.publishedSequence++;
                    return null;
                });
    }

    public List<String> keys(String after, int limit) {
        return records.keys(prefix, after, limit);
    }

    @Override
    public List<String> ready(int limit) {
        return records.ready("hands:" + prefix, limit).stream()
                .map(RecordStore.Work::scope)
                .toList();
    }

    @Override
    public List<String> ready(boolean recovery, int limit) {
        return records
                .ready((recovery ? "hands-recovery:" : "hands-start:") + prefix, limit)
                .stream()
                .map(RecordStore.Work::scope)
                .toList();
    }

    @Override
    public OutputPage output(ExecutionRequest request, long offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative output cursor");
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null)
                        throw failure(DurableException.Code.NOT_FOUND, "Execution not admitted");
                    same(state, request);
                    var page = tx.get("output:" + offset);
                    if (page != null) return DurableJson.convert(page, OutputPage.class);
                    if (offset != state.outputOffset)
                        throw failure(
                                DurableException.Code.INVALID, "Use a returned output cursor");
                    return new OutputPage(
                            offset,
                            offset,
                            "",
                            offset,
                            state.outputSealed && state.result.state().terminal(),
                            null);
                });
    }

    @Override
    public Statistics statistics(int limit) {
        if (limit < 1 || limit > 10000)
            throw new IllegalArgumentException("Inspection limit must be 1..10000");
        var keys = keys(null, limit + 1);
        int pending = 0, unknown = 0, publications = 0;
        long pendingAge = 0, publicationAge = 0;
        for (var key : keys.stream().limit(limit).toList()) {
            long[] counts =
                    records.transaction(
                            key,
                            tx -> {
                                var state = read(tx);
                                boolean unfinished =
                                        !state.result.state().terminal() || !state.outputSealed;
                                boolean unpublished = state.sequence > state.publishedSequence;
                                return new long[] {
                                    unfinished ? 1 : 0,
                                    state.result.state() == ExecutionState.UNKNOWN ? 1 : 0,
                                    unpublished ? 1 : 0,
                                    unfinished ? Math.max(0, tx.now() - state.createdAt) : 0,
                                    unpublished
                                            ? Math.max(0, tx.now() - state.publicationPendingAt)
                                            : 0
                                };
                            });
            pending += (int) counts[0];
            unknown += (int) counts[1];
            publications += (int) counts[2];
            pendingAge = Math.max(pendingAge, counts[3]);
            publicationAge = Math.max(publicationAge, counts[4]);
        }
        return new Statistics(
                Math.min(limit, keys.size()),
                keys.size() > limit,
                pending,
                unknown,
                publications,
                pendingAge,
                publicationAge);
    }

    public List<String> pendingPublications(int limit) {
        return records.ready("hands-events:" + prefix, limit).stream()
                .map(RecordStore.Work::scope)
                .toList();
    }

    private static Work work(String key, State state) {
        return new Work(
                key,
                state.request,
                state.owner,
                state.epoch,
                state.attempted,
                state.cancelled,
                state.result,
                state.outputOffset,
                state.outputSealed);
    }

    public Optional<Work> claim(String key, String owner, Duration lease) {
        return claim(key, null, owner, lease);
    }

    @Override
    public Optional<Work> claim(ExecutionRequest request, String owner, Duration lease) {
        return claim(key(request), request, owner, lease);
    }

    private Optional<Work> claim(
            String key, ExecutionRequest request, String owner, Duration lease) {
        DurableJson.text(owner, "owner");
        if (lease.toMillis() < 100) throw new IllegalArgumentException("Lease must be >= 100ms");
        return transact(
                key,
                (state, now) -> {
                    if (request != null) same(state, request);
                    if (state.result.state().terminal() && state.outputSealed
                            || state.expiresAt > now
                            || state.nextAttemptAt > now) return Optional.empty();
                    state.owner = owner;
                    state.epoch++;
                    state.expiresAt = now + lease.toMillis();
                    return Optional.of(work(key, state));
                });
    }

    private static void fence(State state, Work work, long now) {
        if (!work.owner().equals(state.owner)
                || state.epoch != work.epoch()
                || state.expiresAt <= now)
            throw failure(DurableException.Code.FENCED, "Worker lease lost");
    }

    public void renew(Work work, Duration lease) {
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    state.expiresAt = now + lease.toMillis();
                    return null;
                });
    }

    public Work begin(Work work) {
        return transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    var before = work(work.key(), state);
                    if (!state.cancelled && !state.result.state().terminal())
                        state.attempted = true;
                    return before;
                });
    }

    public void configureOutput(Work work, boolean enabled) {
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    if (!state.outputConfigured) {
                        state.outputConfigured = true;
                        state.outputEnabled = enabled;
                        state.outputSealed = !enabled;
                    } else if (state.outputEnabled != enabled) {
                        throw failure(
                                DurableException.Code.INCOMPATIBLE,
                                "Provider output capability changed");
                    }
                    return null;
                });
    }

    public void appendOutput(Work work, OutputPage page) {
        ExecutionMessages.validate(page);
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    if (state.outputSealed || page.offset() != state.outputOffset)
                        throw failure(
                                DurableException.Code.CONFLICT, "Output offset or seal differs");
                    if (page.offset() == page.nextOffset() && !page.terminal()) return null;
                    state.outputOffset = page.nextOffset();
                    state.outputSealed = page.terminal();
                    emit(state, null, page);
                    if (state.outputSealed && state.result.state().terminal())
                        emit(state, publicResult(state), null);
                    return null;
                });
    }

    public void update(Work work, ExecutionResult result) {
        if (result.state() == ExecutionState.NOT_FOUND)
            throw new IllegalArgumentException("Persist UNKNOWN, not NOT_FOUND");
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    if (state.result.state().terminal()) {
                        if (!state.result.equals(result))
                            throw failure(
                                    DurableException.Code.CONFLICT, "Terminal result changed");
                    } else if (!state.result.equals(result)) {
                        state.result = result;
                        if (!result.state().terminal() || state.outputSealed)
                            emit(state, publicResult(state), null);
                    }
                    return null;
                });
    }

    public void release(Work work, Duration delay) {
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    state.owner = null;
                    state.expiresAt = 0;
                    state.consecutiveFailures = 0;
                    state.nextAttemptAt = now + delay.toMillis();
                    return null;
                });
    }

    @Override
    public void defer(Work work, Duration minimum, Duration maximum) {
        if (minimum.toMillis() < 1 || maximum.compareTo(minimum) < 0)
            throw new IllegalArgumentException("Invalid retry bounds");
        transact(
                work.key(),
                (state, now) -> {
                    fence(state, work, now);
                    state.consecutiveFailures = Math.min(31, state.consecutiveFailures + 1);
                    long multiplier = 1L << (state.consecutiveFailures - 1);
                    long delay =
                            minimum.toMillis() > maximum.toMillis() / multiplier
                                    ? maximum.toMillis()
                                    : Math.min(maximum.toMillis(), minimum.toMillis() * multiplier);
                    state.owner = null;
                    state.expiresAt = 0;
                    state.nextAttemptAt = now + delay;
                    return null;
                });
    }

    @Override
    public ExecutionResult reconcile(Work work, ExecutionReconciliation reconciliation) {
        DurableProtocol.validate("executionReconciliation", reconciliation);
        return transact(
                work.key(),
                (state, now) -> {
                    same(state, work.request());
                    String receiptKey =
                            "reconciliation:" + DurableJson.hash(reconciliation.requestId());
                    var receipt = state.tx.get(receiptKey);
                    if (receipt != null) {
                        if (!DurableJson.hash(receipt.required("command"))
                                .equals(DurableJson.hash(reconciliation)))
                            throw failure(
                                    DurableException.Code.CONFLICT,
                                    "Reconciliation identity content changed");
                        return publicResult(state);
                    }
                    fence(state, work, now);
                    if (!state.attempted || state.result.state() != ExecutionState.UNKNOWN)
                        throw failure(
                                DurableException.Code.CONFLICT,
                                "Only an UNKNOWN effect can be reconciled");
                    state.result = reconciliation.result();
                    if (!state.outputSealed) {
                        // The external result is verified; absent output bytes must remain an
                        // explicit gap.
                        state.outputSealed = true;
                        emit(
                                state,
                                null,
                                new OutputPage(
                                        state.outputOffset,
                                        state.outputOffset,
                                        "",
                                        state.outputOffset,
                                        true,
                                        "Output unavailable after external reconciliation"));
                    }
                    state.tx.put(
                            receiptKey,
                            DurableJson.value(
                                    Map.of(
                                            "command",
                                            reconciliation,
                                            "owner",
                                            work.owner(),
                                            "epoch",
                                            work.epoch(),
                                            "at",
                                            now)));
                    emit(state, publicResult(state), null);
                    return publicResult(state);
                });
    }

    @Override
    public Optional<ExecutionReconciliation> reconciliation(
            ExecutionRequest request, String requestId) {
        DurableJson.text(requestId, "reconciliation requestId");
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null) return Optional.empty();
                    same(state, request);
                    var receipt = tx.get("reconciliation:" + DurableJson.hash(requestId));
                    return receipt == null
                            ? Optional.empty()
                            : Optional.of(
                                    DurableJson.convert(
                                            receipt.required("command"),
                                            ExecutionReconciliation.class));
                });
    }

    private static DurableException failure(DurableException.Code code, String message) {
        return new DurableException(code, message);
    }
}
