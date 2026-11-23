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
import io.agentscope.durable.DurableMessages.Input;
import io.agentscope.durable.DurableTypes.CheckpointMetadata;
import io.agentscope.durable.DurableTypes.CommitReceipt;
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.HistoryPage;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadView;
import io.agentscope.durable.DurableTypes.TurnPage;
import io.agentscope.durable.DurableTypes.TurnReceipt;
import io.agentscope.durable.DurableTypes.TurnView;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Persistent Agent context and FIFO task scope; handles carry identity, never cached state. */
public final class DurableThread {
    private final DurableRuntime runtime;
    private final DurableSession session;
    private final ThreadKey key;

    DurableThread(DurableRuntime runtime, DurableSession session, String threadId) {
        this.runtime = runtime;
        this.session = session;
        this.key = new ThreadKey(session.key(), threadId);
    }

    public ThreadKey key() {
        return key;
    }

    public DurableSession session() {
        return session;
    }

    JsonNode call(String operation, Object... fields) {
        Object[] values = new Object[fields.length + 2];
        values[0] = "threadId";
        values[1] = key.threadId();
        System.arraycopy(fields, 0, values, 2, fields.length);
        return session.call(operation, values);
    }

    public ThreadView view() {
        return DurableJson.convert(call("getThread"), ThreadView.class);
    }

    public ThreadView close() {
        return DurableJson.convert(call("closeThread"), ThreadView.class);
    }

    public TurnPage listTurns(String cursor, int limit) {
        return DurableJson.convert(
                call("listTurns", "cursor", cursor, "limit", limit), TurnPage.class);
    }

    public HistoryPage history(String cursor, int limit) {
        return DurableJson.convert(
                call("history", "cursor", cursor, "limit", limit), HistoryPage.class);
    }

    public DurableTurn submit(String requestId, Input input) {
        var receipt =
                DurableJson.convert(
                        call("submit", "requestId", requestId, "input", input), TurnReceipt.class);
        if (!key.equals(receipt.thread()))
            throw new DurableException(
                    DurableException.Code.CONFLICT, "Admission belongs to another Thread");
        return new DurableTurn(this, receipt.turnId());
    }

    public DurableTurn submit(String requestId, String text) {
        return submit(requestId, Input.text(text));
    }

    public DurableTurn getTurn(String turnId) {
        call("getTurn", "turnId", turnId);
        return new DurableTurn(this, turnId);
    }

    private EventFilter filter() {
        return new EventFilter(key.threadId(), null, null);
    }

    public EventPage listEvents(String cursor, int limit) {
        return session.listEvents(filter(), cursor, limit);
    }

    /** @deprecated Use {@link #listEvents(String, int)}. */
    @Deprecated(forRemoval = true)
    public EventPage events(String cursor, int limit) {
        return listEvents(cursor, limit);
    }

    public SessionEventStream streamEvents(EventStreamOptions options) {
        return session.streamEvents(filter(), options);
    }

    /** @deprecated Use {@link #streamEvents(EventStreamOptions)}. */
    @Deprecated(forRemoval = true)
    public SessionEventStream openEventStream(String cursor) {
        return session.openEventStream(filter(), cursor);
    }

    public Snapshot snapshot() {
        return session.snapshot(filter());
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            String cursor, Consumer<Snapshot> snapshots, Consumer<PublicEvent> events) {
        return session.observe(filter(), cursor, snapshots, events);
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        return session.observe(filter(), cursor, snapshots, events, liveEvents);
    }

    public SessionEventSubscription subscribeEvents(
            EventStreamOptions options, Consumer<SessionStreamEvent> events) {
        return session.subscribeEvents(filter(), options, events);
    }

    public List<CheckpointMetadata> checkpoints(long after, int limit) {
        return List.of(
                DurableJson.convert(
                        call("checkpoints", "after", after, "limit", limit),
                        CheckpointMetadata[].class));
    }

    public CommitReceipt restore(long position, long expectedPosition, String requestId) {
        return DurableJson.convert(
                call(
                        "restore",
                        "position",
                        position,
                        "expectedPosition",
                        expectedPosition,
                        "requestId",
                        requestId),
                CommitReceipt.class);
    }

    public DurableThread fork(long position, ThreadKey target, String requestId) {
        call("fork", "position", position, "target", target, "requestId", requestId);
        return runtime.getSession(target.session()).getThread(target.threadId());
    }

    public CompletionStage<TurnView> call(String requestId, Input input, Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Positive timeout required");
        timeout.toNanos();
        return submit(requestId, input).await(timeout);
    }

    /**
     * @deprecated Subscribe with {@link #subscribeEvents(EventStreamOptions, Consumer)} before
     *     calling {@link #submit(String, Input)}.
     */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription stream(
            String requestId,
            Input input,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        return submit(requestId, input).observe(null, snapshots, events);
    }

    /**
     * @deprecated Subscribe with {@link #subscribeEvents(EventStreamOptions, Consumer)} before
     *     calling {@link #submit(String, Input)}.
     */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription stream(
            String requestId,
            Input input,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        return submit(requestId, input).observe(null, snapshots, events, liveEvents);
    }

    CompletionStage<TurnView> await(String turnId, Duration timeout) {
        return runtime.await(this, turnId, timeout);
    }
}
