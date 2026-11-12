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
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.ExecutionMessages.Command;
import io.agentscope.durable.ExecutionMessages.Event;
import io.agentscope.durable.ExecutionMessages.Kind;
import io.agentscope.durable.Executor;
import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.store.RecordStore;
import java.time.Duration;
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
    private final Consumer<Command> sender;
    private final long retryMillis;

    public QueuedExecutor(
            String name,
            RecordStore records,
            SessionBackend backend,
            Consumer<Command> sender,
            Duration retry) {
        this.name = DurableJson.text(name, "executor name");
        prefix = "queued:" + DurableJson.hash(name) + ":";
        this.records = Objects.requireNonNull(records);
        this.backend = Objects.requireNonNull(backend);
        this.sender = Objects.requireNonNull(sender);
        retryMillis = retry.toMillis();
        if (retryMillis < 1) throw new IllegalArgumentException("Retry interval must be positive");
    }

    public static final class State {
        public int format = 3;
        public ExecutionRequest request;
        public String digest;
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
        return prefix + ExecutionMessages.identity(request);
    }

    private static State read(RecordStore.Transaction tx) {
        var value = tx.get("");
        if (value == null || value.isNull()) return null;
        var state = DurableJson.convert(value, State.class);
        if (state.format != 3)
            throw failure(DurableException.Code.INCOMPATIBLE, "Unknown queue format");
        return state;
    }

    private static void same(State state, ExecutionRequest request) {
        if (!state.digest.equals(DurableJson.hash(request)))
            throw failure(DurableException.Code.CONFLICT, "Execution identity content changed");
    }

    private void save(RecordStore.Transaction tx, State state) {
        tx.put("", DurableJson.value(state));
        boolean pending = tx.get(eventKey(state.deliveredSequence + 1)) != null;
        tx.schedule(
                prefix,
                state.request.executionId(),
                state.result.state().terminal() ? -1 : pending ? 0 : state.nextSendAt,
                DurableJson.value(null));
    }

    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    public ExecutionResult startExecution(ExecutionRequest request) {
        return stage(request, false);
    }

    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        return stage(request, true);
    }

    private ExecutionResult stage(ExecutionRequest request, boolean cancel) {
        ExecutionMessages.validate(
                ExecutionMessages.command(cancel ? Kind.CANCEL : Kind.EXECUTE, request));
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null) {
                        state = new State();
                        state.request = request;
                        state.digest = DurableJson.hash(request);
                        state.result =
                                new ExecutionResult(
                                        ExecutionState.PENDING, request.executionId(), null, null);
                    } else same(state, request);
                    if (cancel && !state.cancelled) {
                        state.cancelled = true;
                        state.nextSendAt = 0;
                    }
                    save(tx, state);
                    return state.result;
                });
    }

    public ExecutionResult query(ExecutionRequest request, String handle) {
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null) return ExecutionResult.missing();
                    same(state, request);
                    return state.result;
                });
    }

    private static String eventKey(long sequence) {
        return "event:" + String.format("%020d", sequence);
    }

    private static String outputKey(long offset) {
        return "output:" + String.format("%020d", offset);
    }

    public void receive(Event event) {
        ExecutionMessages.validate(event);
        String key = key(event.request());
        records.transaction(
                key,
                tx -> {
                    var state = read(tx);
                    if (state == null)
                        throw failure(
                                DurableException.Code.NOT_FOUND, "Unsolicited execution event");
                    same(state, event.request());
                    var old = tx.get(eventKey(event.sequence()));
                    if (old != null
                            && !old.isNull()
                            && !DurableJson.hash(old).equals(DurableJson.hash(event)))
                        throw failure(
                                DurableException.Code.CONFLICT, "Event sequence content changed");
                    if (state.result.state().terminal()
                            && event.sequence() > state.deliveredSequence)
                        throw failure(
                                DurableException.Code.CONFLICT, "Event after terminal outcome");
                    tx.put(eventKey(event.sequence()), DurableJson.value(event));
                    state.receivedSequence = Math.max(state.receivedSequence, event.sequence());
                    if (event.sequence() > state.deliveredSequence + 1) state.nextSendAt = 0;
                    save(tx, state);
                    return null;
                });
        deliver(key);
    }

    private void deliver(String key) {
        for (int i = 0; i < 100; i++) {
            var event =
                    records.transaction(
                            key,
                            tx -> {
                                var state = read(tx);
                                var value = tx.get(eventKey(state.deliveredSequence + 1));
                                return value == null || value.isNull()
                                        ? null
                                        : DurableJson.convert(value, Event.class);
                            });
            if (event == null) return;
            // Session ledger deduplicates this exact source sequence. A crash here is repaired.
            backend.acceptExecutionEvent(event);
            records.transaction(
                    key,
                    tx -> {
                        var state = read(tx);
                        if (event.sequence() == state.deliveredSequence + 1) {
                            if (event.output() != null) {
                                var page = event.output();
                                if (state.outputSealed || page.offset() != state.outputOffset)
                                    throw failure(
                                            DurableException.Code.CONFLICT,
                                            "Discontinuous execution output");
                                tx.put(outputKey(page.offset()), DurableJson.value(page));
                                state.outputOffset = page.nextOffset();
                                state.outputSealed = page.terminal();
                            } else state.result = event.result();
                            state.deliveredSequence = event.sequence();
                            save(tx, state);
                        }
                        return null;
                    });
        }
    }

    public OutputPage output(ExecutionRequest request, long offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative output offset");
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx);
                    if (state == null)
                        throw failure(DurableException.Code.NOT_FOUND, "Execution not admitted");
                    same(state, request);
                    var value = tx.get(outputKey(offset));
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
        var keys = records.ready(prefix, 100).stream().map(RecordStore.Work::scope).toList();
        for (var key : keys) {
            try {
                try {
                    deliver(key);
                } catch (RuntimeException failure) {
                    errors.accept(failure);
                }
                var command =
                        records.transaction(
                                key,
                                tx -> {
                                    var state = read(tx);
                                    if (state.result.state().terminal()
                                            || state.nextSendAt > tx.now()) return null;
                                    state.nextSendAt = tx.now() + retryMillis;
                                    var kind =
                                            state.cancelled
                                                    ? Kind.CANCEL
                                                    : state.receivedSequence == 0
                                                            ? Kind.EXECUTE
                                                            : Kind.QUERY;
                                    save(tx, state);
                                    return ExecutionMessages.command(
                                            kind, state.request, state.deliveredSequence);
                                });
                if (command != null) sender.accept(command);
            } catch (RuntimeException failure) {
                errors.accept(failure);
            }
        }
    }

    private static DurableException failure(DurableException.Code code, String message) {
        return new DurableException(code, message);
    }
}
