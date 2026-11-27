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

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.ToolOutputDelta;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.ExecuteBatch;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.LiveEvents;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Caller-side Executor with a persistent transport inbox/outbox, hosted by a Brain or backend service.
 * Delivers execution events to SessionBackend; does not execute tools or start a Hands worker.
 * Broker ACK follows inbox admission and contiguous delivery.
 */
public final class QueuedExecutor implements Executor {
    private final String name;
    private final String prefix;
    private final RecordStore records;
    private final SessionBackend backend;
    private final Consumer<ExecuteBatch> dispatch;
    private final Consumer<Command> control;
    private final long retryMillis;
    private final long responseMillis;
    private final LiveEvents liveEvents;

    public QueuedExecutor(
            String name,
            RecordStore records,
            SessionBackend backend,
            Consumer<ExecuteBatch> dispatch,
            Consumer<Command> control,
            Duration retry,
            Duration responseTimeout,
            LiveEvents liveEvents) {
        this.name = DurableJson.text(name, "executor name");
        prefix = "queued:v4:" + DurableJson.hash(name) + ":";
        this.records = Objects.requireNonNull(records);
        this.backend = Objects.requireNonNull(backend);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.control = Objects.requireNonNull(control);
        this.liveEvents = Objects.requireNonNull(liveEvents);
        retryMillis = retry.toMillis();
        responseMillis = responseTimeout.toMillis();
        if (retryMillis < 1 || responseMillis < 1)
            throw new IllegalArgumentException(
                    "Retry interval and response timeout must be positive");
    }

    public static final class State {
        public int format = 4;
        public ExecutionRequest request;
        public String digest;
        public String batchId;
        public boolean cancelled;
        public long nextSendAt;
        public long receivedSequence;
        public long deliveredSequence;
        public ExecutionResult result;
        public long outputOffset;
        public boolean outputSealed;
    }

    private String key(ExecutionRequest request) {
        if (!name.equals(request.call().executor()))
            throw failure(DurableException.Code.INCOMPATIBLE, "Executor route differs");
        // Keep execution identity in its original namespace and stage sibling parts atomically.
        return prefix + DurableJson.hash(request.session().namespace());
    }

    private static String stateKey(String id) {
        return "state:" + id;
    }

    private static String batchKey(String id) {
        return "batch:" + id;
    }

    private static State read(RecordStore.Transaction tx, String id) {
        var value = tx.get(stateKey(id));
        if (value == null || value.isNull()) return null;
        var state = DurableJson.convert(value, State.class);
        if (state.format != 4)
            throw failure(DurableException.Code.INCOMPATIBLE, "Unknown queue format");
        return state;
    }

    private static void same(State state, ExecutionRequest request) {
        if (!state.digest.equals(DurableJson.hash(request)))
            throw failure(DurableException.Code.CONFLICT, "Execution identity content changed");
    }

    private void save(RecordStore.Transaction tx, State state) {
        String id = state.request.executionId();
        tx.put(stateKey(id), DurableJson.value(state));
        boolean pending = tx.get(eventKey(id, state.deliveredSequence + 1)) != null;
        tx.schedule(
                prefix,
                id,
                state.result.state().terminal() ? -1 : pending ? 0 : state.nextSendAt,
                DurableJson.value(null));
    }

    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    public ExecutionResult startExecution(ExecutionRequest request) {
        return stageBatch(List.of(request)).get(0);
    }

    public List<ExecutionResult> stageBatch(List<ExecutionRequest> requests) {
        var batch = ExecutionMessages.batch(requests);
        ExecutionMessages.validate(batch);
        return records.transaction(
                key(batch.requests().get(0)),
                tx -> {
                    var states = new ArrayList<State>();
                    var additions = new ArrayList<State>();
                    for (var request : batch.requests()) {
                        var state = read(tx, request.executionId());
                        if (state == null) {
                            state = create(request);
                            additions.add(state);
                        } else same(state, request);
                        states.add(state);
                    }
                    if (!additions.isEmpty()) {
                        var newBatch =
                                ExecutionMessages.batch(
                                        additions.stream().map(state -> state.request).toList());
                        tx.put(batchKey(newBatch.batchId()), DurableJson.value(newBatch));
                        for (var state : additions) {
                            state.batchId = newBatch.batchId();
                            save(tx, state);
                        }
                    }
                    return states.stream().map(state -> state.result).toList();
                });
    }

    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        ExecutionMessages.validate(ExecutionMessages.command(Kind.CANCEL, request));
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request.executionId());
                    if (state == null) state = create(request);
                    else same(state, request);
                    if (!state.cancelled) {
                        state.cancelled = true;
                        state.nextSendAt = 0;
                    }
                    save(tx, state);
                    return state.result;
                });
    }

    private static State create(ExecutionRequest request) {
        var state = new State();
        state.request = request;
        state.digest = DurableJson.hash(request);
        state.result =
                new ExecutionResult(ExecutionState.PENDING, request.executionId(), null, null);
        return state;
    }

    public ExecutionResult query(ExecutionRequest request, String handle) {
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request.executionId());
                    if (state == null) return ExecutionResult.missing();
                    same(state, request);
                    return state.result;
                });
    }

    private static String eventKey(String id, long sequence) {
        return "event:" + id + ":" + sequence;
    }

    private static String outputKey(String id, long offset) {
        return "output:" + id + ":" + offset;
    }

    public void receive(Event event) {
        ExecutionMessages.validate(event);
        String key = key(event.request());
        String id = event.request().executionId();
        records.transaction(
                key,
                tx -> {
                    var state = read(tx, id);
                    if (state == null)
                        throw failure(
                                DurableException.Code.NOT_FOUND, "Unsolicited execution event");
                    same(state, event.request());
                    var old = tx.get(eventKey(id, event.sequence()));
                    if (old != null
                            && !old.isNull()
                            && !DurableJson.hash(old).equals(DurableJson.hash(event)))
                        throw failure(
                                DurableException.Code.CONFLICT, "Event sequence content changed");
                    if (state.result.state().terminal()
                            && event.sequence() > state.deliveredSequence)
                        throw failure(
                                DurableException.Code.CONFLICT, "Event after terminal outcome");
                    tx.put(eventKey(id, event.sequence()), DurableJson.value(event));
                    state.receivedSequence = Math.max(state.receivedSequence, event.sequence());
                    if (old == null || old.isNull()) {
                        if (event.sequence() > state.deliveredSequence + 1) state.nextSendAt = 0;
                        else if (!state.cancelled)
                            state.nextSendAt = Math.addExact(tx.now(), responseMillis);
                    }
                    save(tx, state);
                    return null;
                });
        deliver(key, id);
    }

    private void deliver(String key, String id) {
        for (int i = 0; i < 100; i++) {
            var event =
                    records.transaction(
                            key,
                            tx -> {
                                var state = read(tx, id);
                                if (state == null) return null;
                                var value = tx.get(eventKey(id, state.deliveredSequence + 1));
                                return value == null || value.isNull()
                                        ? null
                                        : DurableJson.convert(value, Event.class);
                            });
            if (event == null) return;
            // Session ledger deduplicates this exact source sequence. A crash here is repaired.
            backend.acceptExecutionEvent(event);
            boolean delivered =
                    records.transaction(
                            key,
                            tx -> {
                                var state = read(tx, id);
                                if (event.sequence() == state.deliveredSequence + 1) {
                                    if (event.output() != null) {
                                        var page = event.output();
                                        if (state.outputSealed
                                                || page.offset() != state.outputOffset)
                                            throw failure(
                                                    DurableException.Code.CONFLICT,
                                                    "Discontinuous execution output");
                                        tx.put(
                                                outputKey(id, page.offset()),
                                                DurableJson.value(page));
                                        state.outputOffset = page.nextOffset();
                                        state.outputSealed = page.terminal();
                                    } else state.result = event.result();
                                    state.deliveredSequence = event.sequence();
                                    if (state.receivedSequence > state.deliveredSequence
                                            && tx.get(eventKey(id, state.deliveredSequence + 1))
                                                    == null) state.nextSendAt = 0;
                                    save(tx, state);
                                    return true;
                                }
                                return false;
                            });
            if (delivered && event.output() != null && !event.output().data().isEmpty())
                preview(event);
        }
    }

    private void preview(Event event) {
        var request = event.request();
        var origin = request.call().origin();
        var page = event.output();
        try {
            liveEvents.publish(
                    new LiveEvent(
                            "1",
                            event.eventId(),
                            request.session(),
                            request.thread().threadId(),
                            request.turnId(),
                            origin == null ? null : origin.runId(),
                            origin == null ? null : origin.stepId(),
                            System.currentTimeMillis(),
                            ToolOutputDelta.TYPE,
                            DurableJson.value(
                                    new ToolOutputDelta(
                                            request.call().callId(),
                                            request.executionId(),
                                            1,
                                            0,
                                            page.offset(),
                                            page.nextOffset(),
                                            page.data()))));
        } catch (RuntimeException error) {
            System.getLogger(QueuedExecutor.class.getName())
                    .log(System.Logger.Level.WARNING, "Tool output preview unavailable", error);
        }
    }

    public OutputPage output(ExecutionRequest request, long offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative output offset");
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request.executionId());
                    if (state == null)
                        throw failure(DurableException.Code.NOT_FOUND, "Execution not admitted");
                    same(state, request);
                    var value = tx.get(outputKey(request.executionId(), offset));
                    if (value != null && !value.isNull())
                        return DurableJson.convert(value, OutputPage.class);
                    if (offset > state.outputOffset)
                        throw failure(
                                DurableException.Code.CONFLICT, "Output offset ahead of stream");
                    if (offset < state.outputOffset)
                        throw failure(
                                DurableException.Code.INVALID, "Use a returned output cursor");
                    return new OutputPage(
                            offset,
                            offset,
                            "",
                            offset,
                            state.outputSealed || state.result.state().terminal(),
                            null);
                });
    }

    public synchronized void tick(Consumer<Throwable> errors) {
        for (var work : records.ready(prefix, 100)) advance(work.scope(), work.workId(), errors);
    }

    /** Publishes any newly staged or due work for this execution after the caller's commit. */
    public synchronized void tick(ExecutionRequest request, Consumer<Throwable> errors) {
        advance(key(request), request.executionId(), errors);
    }

    private void advance(String key, String id, Consumer<Throwable> errors) {
        try {
            try {
                deliver(key, id);
            } catch (RuntimeException failure) {
                errors.accept(failure);
            }
            var send = records.transaction(key, tx -> preparePublication(tx, id));
            if (send != null) send.run();
        } catch (RuntimeException failure) {
            errors.accept(failure);
        }
    }

    private Runnable preparePublication(RecordStore.Transaction tx, String id) {
        var state = read(tx, id);
        if (state == null || state.result.state().terminal() || state.nextSendAt > tx.now())
            return null;
        if (!state.cancelled && state.receivedSequence == 0) {
            var batch = reserveBatch(tx, state.batchId);
            return () -> dispatch.accept(batch);
        }
        var kind = state.cancelled ? Kind.CANCEL : Kind.QUERY;
        boolean gap =
                state.receivedSequence > state.deliveredSequence
                        && tx.get(eventKey(id, state.deliveredSequence + 1)) == null;
        state.nextSendAt =
                Math.addExact(tx.now(), kind == Kind.QUERY && !gap ? responseMillis : retryMillis);
        save(tx, state);
        var command = ExecutionMessages.command(kind, state.request, state.deliveredSequence);
        return () -> control.accept(command);
    }

    private ExecuteBatch reserveBatch(RecordStore.Transaction tx, String batchId) {
        var batch = DurableJson.convert(tx.get(batchKey(batchId)), ExecuteBatch.class);
        var eligible = new ArrayList<ExecutionRequest>();
        long nextSend = Math.addExact(tx.now(), retryMillis);
        for (var request : batch.requests()) {
            var state = read(tx, request.executionId());
            if (state.cancelled) continue;
            eligible.add(request);
            if (state.receivedSequence == 0 && !state.result.state().terminal()) {
                state.nextSendAt = nextSend;
                save(tx, state);
            }
        }
        return eligible.size() == batch.requests().size()
                ? batch
                : ExecutionMessages.batch(eligible);
    }

    private static DurableException failure(DurableException.Code code, String message) {
        return new DurableException(code, message);
    }
}
