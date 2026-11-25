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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException.Code;
import io.agentscope.durable.store.ModelOutputStore;
import io.agentscope.durable.store.RecordStore;
import io.agentscope.durable.store.RecordStore.Transaction;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Model metadata and wakeup readiness share the Session's RecordStore transaction. */
public final class TransactionalModelExecutionStore implements ModelExecutionStore {
    private final RecordStore records;
    private final ModelOutputStore outputs;
    private final Wakeups wakeups;

    public TransactionalModelExecutionStore(
            RecordStore records, ModelOutputStore outputs, Wakeups wakeups) {
        this.records = Objects.requireNonNull(records, "records");
        this.outputs = Objects.requireNonNull(outputs, "outputs");
        this.wakeups = Objects.requireNonNull(wakeups, "wakeups");
    }

    static final class State {
        public String route;
        public String requestDigest;
        public String worker;
        public long epoch;
        public long expiresAt;
        public boolean started;
        public long nextSequence;
        public Outcome outcome;
    }

    private record Index(JsonNode locator, String digest) {}

    private record Claim(Lease lease, boolean notifyReady) {}

    @Override
    public Wakeups wakeups() {
        return wakeups;
    }

    @Override
    public void submit(Request request) {
        Key key = request.key();
        String digest = DurableJson.hash(request);
        boolean admitted =
                transaction(
                        key,
                        tx -> {
                            var state = load(tx, key);
                            if (state != null) {
                                if (!digest.equals(state.requestDigest))
                                    throw new DurableException(
                                            Code.CONFLICT, "Model request changed");
                                return false;
                            }
                            requireActive(tx, key);
                            state = new State();
                            state.route = request.route();
                            state.requestDigest = digest;
                            tx.put(part(key) + "request", DurableJson.value(request));
                            save(tx, key, state);
                            return true;
                        });
        if (admitted) notifyChange(key);
    }

    @Override
    public View query(Key key) {
        return transaction(
                key,
                tx -> {
                    var state = load(tx, key);
                    if (state == null) return null;
                    Status status =
                            state.outcome != null
                                    ? state.outcome.status()
                                    : state.expiresAt > tx.now() ? Status.RUNNING : Status.PENDING;
                    return new View(status, state.epoch, state.nextSequence, state.outcome);
                });
    }

    @Override
    public List<Key> ready(String route, int limit) {
        DurableJson.text(route, "model route");
        return records.ready(queue(route), limit).stream()
                .map(work -> DurableJson.convert(work.value(), Key.class))
                .toList();
    }

    @Override
    public Optional<Lease> claim(Key key, String workerId, Duration duration) {
        DurableJson.text(workerId, "worker ID");
        long millis = leaseMillis(duration);
        var claimed =
                transaction(
                        key,
                        tx -> {
                            var state = load(tx, key);
                            if (state == null
                                    || state.outcome != null
                                    || state.expiresAt > tx.now()) return new Claim(null, false);
                            if (!TransactionalSessionBackend.modelTurnActive(tx, key)) {
                                state.outcome =
                                        new Outcome(Status.CANCELLED, "Turn cancelled", false);
                            } else if (state.started) {
                                state.outcome =
                                        new Outcome(
                                                Status.UNKNOWN,
                                                "Worker lost after provider start",
                                                false);
                                TransactionalSessionBackend.modelOutputReady(tx, key);
                            } else {
                                state.worker = workerId;
                                state.epoch = Math.addExact(state.epoch, 1);
                                state.expiresAt = Math.addExact(tx.now(), millis);
                                save(tx, key, state);
                                var request =
                                        DurableJson.convert(
                                                tx.get(part(key) + "request"), Request.class);
                                return new Claim(new Lease(request, workerId, state.epoch), false);
                            }
                            save(tx, key, state);
                            return new Claim(null, state.outcome.status() == Status.UNKNOWN);
                        });
        if (claimed.notifyReady()) notifyChange(key);
        return Optional.ofNullable(claimed.lease());
    }

    @Override
    public void renew(Lease lease, Duration duration) {
        long millis = leaseMillis(duration);
        update(lease, (tx, state, key) -> state.expiresAt = Math.addExact(tx.now(), millis));
    }

    @Override
    public void begin(Lease lease) {
        update(
                lease,
                (tx, state, key) -> {
                    if (state.started)
                        throw new DurableException(Code.CONFLICT, "Model provider already started");
                    state.started = true;
                });
    }

    @Override
    public void append(Lease lease, Batch batch) {
        if (!lease.request().key().equals(batch.key()) || lease.epoch() != batch.generation())
            throw new DurableException(Code.INVALID, "Batch belongs to another model execution");
        var key = batch.key();
        String digest = DurableJson.hash(batch);
        // External I/O is outside the metadata transaction. Only the fenced index makes it visible.
        var locator = Objects.requireNonNull(outputs.write(batch), "output locator");
        update(
                lease,
                (tx, state, ignored) -> {
                    if (!state.started)
                        throw new DurableException(Code.CONFLICT, "Model provider has not started");
                    String part = batchPart(key, batch.generation(), batch.firstSequence());
                    var old = tx.get(part);
                    if (old != null) {
                        if (!digest.equals(DurableJson.convert(old, Index.class).digest()))
                            throw new DurableException(Code.CONFLICT, "Model batch changed");
                        return;
                    }
                    if (batch.firstSequence() != state.nextSequence)
                        throw new DurableException(Code.CONFLICT, "Model batch is out of sequence");
                    tx.put(part, DurableJson.value(new Index(locator, digest)));
                    if (state.nextSequence == 0)
                        TransactionalSessionBackend.modelOutputReady(tx, key);
                    state.nextSequence = batch.nextSequence();
                });
        notifyChange(key);
    }

    @Override
    public Batch readOutput(Key key, long generation, long firstSequence) {
        var index =
                transaction(
                        key,
                        tx -> {
                            var value = tx.get(batchPart(key, generation, firstSequence));
                            return value == null ? null : DurableJson.convert(value, Index.class);
                        });
        if (index == null) return null;
        var batch = outputs.read(index.locator());
        if (!key.equals(batch.key())
                || generation != batch.generation()
                || firstSequence != batch.firstSequence()
                || !index.digest().equals(DurableJson.hash(batch)))
            throw new DurableException(
                    Code.INVALID, "Model batch does not match the committed index");
        return batch;
    }

    @Override
    public void complete(Lease lease, Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        update(
                lease,
                (tx, state, key) -> {
                    if (outcome.status() == Status.SUCCEEDED && !state.started)
                        throw new DurableException(Code.CONFLICT, "Model provider has not started");
                    state.outcome = outcome;
                    if (state.nextSequence == 0)
                        TransactionalSessionBackend.modelOutputReady(tx, key);
                });
        notifyChange(lease.request().key());
    }

    private interface Change {
        void apply(Transaction tx, State state, Key key);
    }

    private void update(Lease lease, Change change) {
        var key = lease.request().key();
        transaction(
                key,
                tx -> {
                    var state = load(tx, key);
                    if (state == null
                            || state.outcome != null
                            || state.expiresAt <= tx.now()
                            || state.epoch != lease.epoch()
                            || !lease.workerId().equals(state.worker))
                        throw new DurableException(Code.FENCED, "Model Worker lease lost");
                    requireActive(tx, key);
                    change.apply(tx, state, key);
                    save(tx, key, state);
                    return null;
                });
    }

    private static void requireActive(Transaction tx, Key key) {
        if (!TransactionalSessionBackend.modelTurnActive(tx, key))
            throw new DurableException(Code.FENCED, "Model Turn is terminal or cancelling");
    }

    private <T> T transaction(Key key, Function<Transaction, T> action) {
        return records.transaction(key.thread().session().storageKey(), action);
    }

    private static State load(Transaction tx, Key key) {
        var value = tx.get(part(key) + "state");
        return value == null ? null : DurableJson.convert(value, State.class);
    }

    private static void save(Transaction tx, Key key, State state) {
        tx.put(part(key) + "state", DurableJson.value(state));
        tx.schedule(
                queue(state.route),
                DurableJson.hash(key),
                state.outcome != null ? -1 : state.worker == null ? tx.now() : state.expiresAt,
                DurableJson.value(key));
    }

    private static long leaseMillis(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 100) throw new IllegalArgumentException("Model Worker lease must be >= 100ms");
        return millis;
    }

    private static String queue(String route) {
        return "model-execution:" + DurableJson.hash(route);
    }

    private static String part(Key key) {
        return "model-execution:" + DurableJson.hash(key) + ":";
    }

    private static String batchPart(Key key, long generation, long seq) {
        return part(key) + "batch:" + generation + ":" + seq;
    }

    private void notifyChange(Key key) {
        try {
            wakeups.publish(key.thread().session());
        } catch (RuntimeException failure) {
            System.getLogger(TransactionalModelExecutionStore.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Model wakeup failed; polling remains active",
                            failure);
        }
    }
}
