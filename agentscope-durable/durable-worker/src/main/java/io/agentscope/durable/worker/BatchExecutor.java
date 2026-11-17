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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.DurableTypes.ToolCall;
import io.agentscope.durable.ExecutionMessages;
import io.agentscope.durable.Executor;
import io.agentscope.durable.store.RecordStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable ordered or bounded-parallel children. Output is attributed, replayable NDJSON. */
public final class BatchExecutor implements Executor {
    public enum Mode {
        ORDERED,
        PARALLEL
    }

    private final RecordStore records;
    private final Executor children;
    private final Mode mode;
    private final int parallelism;

    public BatchExecutor(RecordStore records, Executor children) {
        this(records, children, Mode.ORDERED, 1);
    }

    public BatchExecutor(RecordStore records, Executor children, Mode mode, int parallelism) {
        this.records = Objects.requireNonNull(records);
        this.children = Objects.requireNonNull(children);
        this.mode = Objects.requireNonNull(mode);
        if (parallelism < 1 || parallelism > 128)
            throw new IllegalArgumentException("Batch parallelism must be 1..128");
        this.parallelism = mode == Mode.ORDERED ? 1 : parallelism;
        if (!children.capabilities().idempotentStart()
                || !children.capabilities().lookupByExecutionId())
            throw new IllegalArgumentException(
                    "Batch children require durable admission and lookup");
    }

    public static final class State {
        public int format = 1;
        public ExecutionRequest request;
        public String digest;
        public Mode mode;
        public int parallelism;
        public boolean cancelled;
        public List<ExecutionRequest> children;
        public List<ExecutionResult> results;
        public List<Long> cursors;
        public List<Boolean> sealed;
        public int admitted;
        public long outputOffset;
        public ExecutionResult terminal;
    }

    public Capabilities capabilities() {
        return new Capabilities(true, true, true, true);
    }

    private static String key(ExecutionRequest request) {
        return "batch:" + ExecutionMessages.identity(request);
    }

    private static State read(RecordStore.Transaction tx, ExecutionRequest request) {
        var value = tx.get("");
        if (value == null) return null;
        var state = DurableJson.convert(value, State.class);
        if (state.format != 1)
            throw new DurableException(DurableException.Code.INCOMPATIBLE, "Unknown batch format");
        if (!state.digest.equals(DurableJson.hash(request)))
            throw new DurableException(DurableException.Code.CONFLICT, "Batch descriptor changed");
        return state;
    }

    private State create(ExecutionRequest request) {
        var calls = request.call().arguments().path("calls");
        if (!calls.isArray() || calls.isEmpty() || calls.size() > 128)
            throw new IllegalArgumentException("Batch requires 1..128 calls");
        var state = new State();
        state.request = request;
        state.digest = DurableJson.hash(request);
        state.mode = mode;
        state.parallelism = parallelism;
        state.children = new ArrayList<>();
        state.results = new ArrayList<>();
        state.cursors = new ArrayList<>();
        state.sealed = new ArrayList<>();
        var ids = new HashSet<String>();
        int slot = 0;
        for (JsonNode entry : calls) {
            var call = DurableJson.convert(entry, ToolCall.class);
            if (call.origin() != null && !call.origin().equals(request.call().origin()))
                throw new IllegalArgumentException("Batch child origin differs from its parent");
            call = call.withOrigin(request.call().origin());
            if (call.executor().equals(request.call().executor()) || !ids.add(call.callId()))
                throw new IllegalArgumentException("Recursive route or duplicate batch call ID");
            state.children.add(
                    new ExecutionRequest(
                            "child-"
                                    + DurableJson.hash(
                                            List.of(request.executionId(), slot++, call)),
                            request.thread(),
                            request.turnId(),
                            call));
            state.results.add(null);
            state.cursors.add(0L);
            state.sealed.add(!children.capabilities().output());
        }
        return state;
    }

    public ExecutionResult startExecution(ExecutionRequest request) {
        records.transaction(
                key(request),
                tx -> {
                    if (read(tx, request) == null) tx.put("", DurableJson.value(create(request)));
                    return null;
                });
        return query(request, request.executionId());
    }

    /** The frozen manifest is useful to hosts authorizing child work without trusting caller input. */
    public List<ExecutionRequest> manifest(ExecutionRequest request) {
        var state = records.transaction(key(request), tx -> read(tx, request));
        if (state == null)
            throw new DurableException(DurableException.Code.NOT_FOUND, "Batch not admitted");
        return List.copyOf(state.children);
    }

    public ExecutionResult query(ExecutionRequest request, String handle) {
        var initial = records.transaction(key(request), tx -> read(tx, request));
        if (initial == null) return ExecutionResult.missing();
        if (initial.terminal != null) return initial.terminal;
        // A synchronous provider can complete all children in one query; an asynchronous child
        // consumes a slot until its durable result (and output seal) is confirmed.
        for (int round = 0; round <= initial.children.size(); round++) {
            var state = records.transaction(key(request), tx -> read(tx, request));
            for (int slot = 0; slot < state.admitted; slot++) observe(request, slot);
            boolean admitted =
                    records.transaction(
                            key(request),
                            tx -> {
                                var current = read(tx, request);
                                int active = 0;
                                for (int slot = 0; slot < current.admitted; slot++)
                                    if ((current.results.get(slot) == null
                                                    || !current.results
                                                            .get(slot)
                                                            .state()
                                                            .terminal())
                                            || !current.sealed.get(slot)) active++;
                                int previous = current.admitted;
                                while (!current.cancelled
                                        && current.admitted < current.children.size()
                                        && active < current.parallelism) {
                                    current.admitted++;
                                    active++;
                                }
                                tx.put("", DurableJson.value(current));
                                return previous != current.admitted;
                            });
            if (!admitted) break;
        }
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request);
                    if (state.terminal != null) return state.terminal;
                    if (state.cancelled) {
                        for (int slot = state.admitted; slot < state.children.size(); slot++) {
                            state.results.set(
                                    slot,
                                    new ExecutionResult(
                                            ExecutionState.CANCELLED,
                                            state.children.get(slot).executionId(),
                                            null,
                                            null));
                            state.sealed.set(slot, true);
                        }
                    }
                    if (state.results.stream()
                                    .anyMatch(
                                            result -> result == null || !result.state().terminal())
                            || state.sealed.contains(false)) {
                        tx.put("", DurableJson.value(state));
                        boolean unknown =
                                state.results.stream()
                                        .anyMatch(
                                                result ->
                                                        result != null
                                                                && result.state()
                                                                        == ExecutionState.UNKNOWN);
                        return new ExecutionResult(
                                unknown ? ExecutionState.UNKNOWN : ExecutionState.RUNNING,
                                request.executionId(),
                                null,
                                unknown ? "A child outcome remains unconfirmed" : null);
                    }
                    var results = new ArrayList<Object>();
                    for (int slot = 0; slot < state.children.size(); slot++) {
                        var child = state.children.get(slot);
                        results.add(
                                Map.of(
                                        "callId",
                                        child.call().callId(),
                                        "executionId",
                                        child.executionId(),
                                        "name",
                                        child.call().name(),
                                        "result",
                                        state.results.get(slot)));
                    }
                    var outcome =
                            state.cancelled
                                    ? ExecutionState.CANCELLED
                                    : state.results.stream()
                                                    .anyMatch(
                                                            result ->
                                                                    result.state()
                                                                            != ExecutionState
                                                                                    .SUCCEEDED)
                                            ? ExecutionState.FAILED
                                            : ExecutionState.SUCCEEDED;
                    state.terminal =
                            new ExecutionResult(
                                    outcome,
                                    request.executionId(),
                                    DurableJson.value(
                                            Map.of(
                                                    "format",
                                                    "durable.batch.v1",
                                                    "results",
                                                    results)),
                                    null);
                    tx.put(
                            "output:" + state.outputOffset,
                            DurableJson.value(
                                    new OutputPage(
                                            state.outputOffset,
                                            state.outputOffset,
                                            "",
                                            state.outputOffset,
                                            true,
                                            null)));
                    tx.put("", DurableJson.value(state));
                    return state.terminal;
                });
    }

    private void observe(ExecutionRequest request, int slot) {
        var state = records.transaction(key(request), tx -> read(tx, request));
        var child = state.children.get(slot);
        if (state.results.get(slot) == null || !state.results.get(slot).state().terminal()) {
            var result = children.query(child, child.executionId());
            if (state.cancelled && !result.state().terminal())
                result = children.cancel(child, result.handle());
            else if (result.state() == ExecutionState.NOT_FOUND)
                result = children.startExecution(child);
            var observed = result;
            records.transaction(
                    key(request),
                    tx -> {
                        var current = read(tx, request);
                        if (current.results.get(slot) == null
                                || !current.results.get(slot).state().terminal()) {
                            current.results.set(slot, observed);
                            tx.put("", DurableJson.value(current));
                        }
                        return null;
                    });
        }
        if (!state.sealed.get(slot)) {
            for (int pageNumber = 0; pageNumber < 16; pageNumber++) {
                var current = records.transaction(key(request), tx -> read(tx, request));
                if (current.sealed.get(slot)) break;
                long offset = current.cursors.get(slot);
                var page = children.output(child, offset);
                ExecutionMessages.validate(page);
                if (page.offset() != offset)
                    throw new DurableException(
                            DurableException.Code.CONFLICT, "Child output cursor differs");
                if (page.nextOffset() == offset && !page.terminal()) break;
                byte[] bytes =
                        (DurableJson.value(
                                                        Map.of(
                                                                "format",
                                                                "durable.batch.output.v1",
                                                                "callId",
                                                                child.call().callId(),
                                                                "executionId",
                                                                child.executionId(),
                                                                "output",
                                                                page))
                                                .toString()
                                        + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                records.transaction(
                        key(request),
                        tx -> {
                            var latest = read(tx, request);
                            if (latest.sealed.get(slot) || latest.cursors.get(slot) != offset)
                                return null;
                            long next = Math.addExact(latest.outputOffset, bytes.length);
                            tx.put(
                                    "output:" + latest.outputOffset,
                                    DurableJson.value(
                                            new OutputPage(
                                                    latest.outputOffset,
                                                    next,
                                                    Base64.getEncoder().encodeToString(bytes),
                                                    next,
                                                    false,
                                                    null)));
                            latest.outputOffset = next;
                            latest.cursors.set(slot, page.nextOffset());
                            latest.sealed.set(slot, page.terminal());
                            tx.put("", DurableJson.value(latest));
                            return null;
                        });
                if (page.terminal()) break;
            }
        }
    }

    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request);
                    if (state == null) state = create(request);
                    if (state.terminal == null) state.cancelled = true;
                    tx.put("", DurableJson.value(state));
                    return null;
                });
        return query(request, handle);
    }

    public OutputPage output(ExecutionRequest request, long offset) {
        if (offset < 0) throw new IllegalArgumentException("Negative output cursor");
        return records.transaction(
                key(request),
                tx -> {
                    var state = read(tx, request);
                    if (state == null)
                        throw new DurableException(
                                DurableException.Code.NOT_FOUND, "Batch not admitted");
                    var page = tx.get("output:" + offset);
                    if (page != null) return DurableJson.convert(page, OutputPage.class);
                    if (offset != state.outputOffset)
                        throw new DurableException(
                                DurableException.Code.INVALID, "Use a returned output cursor");
                    return new OutputPage(offset, offset, "", offset, state.terminal != null, null);
                });
    }
}
