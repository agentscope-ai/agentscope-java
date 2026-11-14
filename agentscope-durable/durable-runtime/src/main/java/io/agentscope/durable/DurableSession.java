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
import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.HistoryPage;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionView;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.DurableTypes.ThreadPage;
import io.agentscope.durable.DurableTypes.TurnPage;
import io.agentscope.durable.DurableTypes.TurnView;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** A business conversation. Context and execution belong to its explicit Threads. */
public final class DurableSession {
    private final DurableRuntime runtime;
    private final SessionKey key;

    DurableSession(DurableRuntime runtime, SessionKey key) {
        this.runtime = runtime;
        this.key = key;
    }

    public SessionKey key() {
        return key;
    }

    Wakeups wakeups() {
        return runtime.wakeups();
    }

    LiveEvents liveEvents() {
        return runtime.liveEvents();
    }

    JsonNode call(String operation, Object... fields) {
        Object[] values = new Object[fields.length + 2];
        values[0] = "session";
        values[1] = key;
        System.arraycopy(fields, 0, values, 2, fields.length);
        return runtime.call(operation, values);
    }

    public SessionView view() {
        return DurableJson.convert(call("getSession"), SessionView.class);
    }

    public SessionView close() {
        return DurableJson.convert(call("closeSession"), SessionView.class);
    }

    public DurableThread mainThread() {
        return getThread(view().primaryThreadId());
    }

    public DurableThread getThread(String threadId) {
        call("getThread", "threadId", threadId);
        return new DurableThread(runtime, this, threadId);
    }

    public DurableThread createThread(String threadId, String definition) {
        call("createThread", "threadId", threadId, "definition", definition);
        return new DurableThread(runtime, this, threadId);
    }

    public ThreadPage listThreads(String cursor, int limit) {
        return DurableJson.convert(
                call("listThreads", "cursor", cursor, "limit", limit), ThreadPage.class);
    }

    public Snapshot snapshot() {
        return snapshot(EventFilter.all());
    }

    public Snapshot snapshot(EventFilter filter) {
        return DurableJson.convert(call("snapshot", "filter", filter), Snapshot.class);
    }

    public EventPage listEvents(String cursor, int limit) {
        return listEvents(EventFilter.all(), cursor, limit);
    }

    /** Limit bounds scanned Session events; an empty filtered page can still advance its cursor. */
    public EventPage listEvents(EventFilter filter, String cursor, int limit) {
        return DurableJson.convert(
                call("events", "filter", filter, "cursor", cursor, "limit", limit),
                EventPage.class);
    }

    /** @deprecated Use {@link #listEvents(String, int)}. */
    @Deprecated(forRemoval = true)
    public EventPage events(String cursor, int limit) {
        return listEvents(cursor, limit);
    }

    /** @deprecated Use {@link #listEvents(EventFilter, String, int)}. */
    @Deprecated(forRemoval = true)
    public EventPage events(EventFilter filter, String cursor, int limit) {
        return listEvents(filter, cursor, limit);
    }

    /** Open a typed event stream. Delta previews are disabled unless enabled in {@code options}. */
    public SessionEventStream streamEvents(EventStreamOptions options) {
        return streamEvents(EventFilter.all(), options);
    }

    /** Open a filtered typed event stream with per-connection delta options. */
    public SessionEventStream streamEvents(EventFilter filter, EventStreamOptions options) {
        return new SessionEventStream(this, filter, options);
    }

    /** @deprecated Use {@link #streamEvents(EventStreamOptions)}. */
    @Deprecated(forRemoval = true)
    public SessionEventStream openEventStream(String cursor) {
        return openEventStream(EventFilter.all(), cursor);
    }

    /** @deprecated Use {@link #streamEvents(EventFilter, EventStreamOptions)}. */
    @Deprecated(forRemoval = true)
    public SessionEventStream openEventStream(EventFilter filter, String cursor) {
        return new SessionEventStream(this, filter, cursor);
    }

    /**
     * Subscribe to one typed event feed. A null cursor replays retained committed history; a saved
     * cursor resumes after it. Deltas are opt-in per subscription.
     */
    public SessionEventSubscription subscribeEvents(
            EventStreamOptions options, Consumer<SessionStreamEvent> events) {
        return subscribeEvents(EventFilter.all(), options, events);
    }

    /** Subscribe to a filtered typed event feed. */
    public SessionEventSubscription subscribeEvents(
            EventFilter filter, EventStreamOptions options, Consumer<SessionStreamEvent> events) {
        Objects.requireNonNull(options);
        Objects.requireNonNull(events);
        return runtime.observe(
                this,
                filter,
                options.cursor(),
                false,
                snapshot -> events.accept(new SessionStreamEvent.SnapshotReset(snapshot)),
                event -> events.accept(new SessionStreamEvent.Committed(event)),
                options.deltas().isEmpty()
                        ? null
                        : event -> {
                            if (options.accepts(event))
                                events.accept(SessionStreamEvent.from(event));
                        });
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            Consumer<Snapshot> snapshots, Consumer<PublicEvent> events) {
        return observe(snapshots, events, EventFilter.all());
    }

    /** @deprecated Use {@link #subscribeEvents(EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            String cursor, Consumer<Snapshot> snapshots, Consumer<PublicEvent> events) {
        return observe(EventFilter.all(), cursor, snapshots, events);
    }

    /** @deprecated Use {@link #subscribeEvents(EventFilter, EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            Consumer<Snapshot> snapshots, Consumer<PublicEvent> events, EventFilter filter) {
        return runtime.observe(this, filter, null, true, snapshots, events);
    }

    /** @deprecated Use {@link #subscribeEvents(EventFilter, EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            EventFilter filter,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        return runtime.observe(this, filter, cursor, false, snapshots, events);
    }

    /** @deprecated Use {@link #subscribeEvents(EventFilter, EventStreamOptions, Consumer)}. */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription observe(
            EventFilter filter,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        return runtime.observe(this, filter, cursor, false, snapshots, events, liveEvents);
    }

    /** Submit to the persisted primary Thread. */
    public DurableTurn submit(String requestId, Input input) {
        return mainThread().submit(requestId, input);
    }

    public DurableTurn submit(String requestId, String text) {
        return submit(requestId, Input.text(text));
    }

    /** Resolve a Turn in the persisted primary Thread. */
    public DurableTurn getTurn(String turnId) {
        return mainThread().getTurn(turnId);
    }

    /** List Turns admitted to the persisted primary Thread. */
    public TurnPage listTurns(String cursor, int limit) {
        return mainThread().listTurns(cursor, limit);
    }

    /** Read the context history of the persisted primary Thread. */
    public HistoryPage history(String cursor, int limit) {
        return mainThread().history(cursor, limit);
    }

    public CompletionStage<TurnView> call(String requestId, Input input, Duration timeout) {
        return mainThread().call(requestId, input, timeout);
    }

    /**
     * @deprecated Open {@link #subscribeEvents(EventStreamOptions, Consumer)} before calling
     *     {@link #submit(String, Input)}.
     */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription stream(
            String requestId,
            Input input,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        return mainThread().stream(requestId, input, snapshots, events);
    }

    /**
     * @deprecated Open {@link #subscribeEvents(EventStreamOptions, Consumer)} before calling
     *     {@link #submit(String, Input)}.
     */
    @Deprecated(forRemoval = true)
    public SessionEventSubscription stream(
            String requestId,
            Input input,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents) {
        return mainThread().stream(requestId, input, snapshots, events, liveEvents);
    }
}
