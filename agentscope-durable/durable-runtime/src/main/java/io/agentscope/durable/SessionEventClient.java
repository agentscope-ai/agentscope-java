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

import io.agentscope.durable.DurableTypes.EventFilter;
import io.agentscope.durable.DurableTypes.EventPage;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.Snapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Replay client with explicit snapshot resynchronization; callbacks acknowledge before cursor advances. */
public final class SessionEventClient implements AutoCloseable {
    private final Supplier<Snapshot> snapshot;
    private final BiFunction<String, Integer, EventPage> eventPage;
    private final SessionKey session;
    private final Consumer<Snapshot> snapshots;
    private final Consumer<PublicEvent> events;
    private volatile String cursor;
    private volatile String replayHighWatermark;
    private boolean replayBoundaryCaptured;
    private volatile boolean replayCaughtUp;
    private final ArrayBlockingQueue<Boolean> changes = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AutoCloseable subscription;

    public SessionEventClient(
            SessionBackend backend,
            SessionKey session,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        this(backend, session, cursor, snapshots, events, () -> {});
    }

    SessionEventClient(
            SessionBackend backend,
            SessionKey session,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Runnable changed) {
        this(
                backend.wakeups(),
                session,
                () -> backend.snapshot(session),
                (after, limit) -> backend.events(session, after, limit),
                cursor,
                snapshots,
                events,
                changed);
    }

    public SessionEventClient(
            DurableSession session,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        this(session, EventFilter.all(), cursor, snapshots, events);
    }

    public SessionEventClient(
            DurableSession session,
            EventFilter filter,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events) {
        this(session, filter, cursor, snapshots, events, () -> {});
    }

    SessionEventClient(
            DurableSession session,
            EventFilter filter,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Runnable changed) {
        this(
                session.wakeups(),
                session.key(),
                () -> session.snapshot(filter),
                (after, limit) -> session.listEvents(filter, after, limit),
                cursor,
                snapshots,
                events,
                changed);
    }

    private SessionEventClient(
            Wakeups wakeups,
            SessionKey session,
            Supplier<Snapshot> snapshot,
            BiFunction<String, Integer, EventPage> eventPage,
            String cursor,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Runnable changed) {
        this.snapshot = Objects.requireNonNull(snapshot);
        this.eventPage = Objects.requireNonNull(eventPage);
        this.session = Objects.requireNonNull(session);
        this.cursor = cursor;
        this.snapshots = Objects.requireNonNull(snapshots);
        this.events = Objects.requireNonNull(events);
        AutoCloseable registered;
        try {
            registered =
                    wakeups.subscribe(
                            key -> {
                                if (session.equals(key)) {
                                    changes.offer(Boolean.TRUE);
                                    changed.run();
                                }
                            });
        } catch (RuntimeException error) {
            NotifyingSessionBackend.logFailure(session, error);
            registered = () -> {};
        }
        subscription = registered;
    }

    /**
     * Runs callbacks serially on the calling worker. Normal callback return acknowledges delivery;
     * an exception leaves that event/snapshot unacknowledged for the next poll. Persist consumer
     * state and cursor together inside the callback if recovery across consumer restarts is needed.
     * Delivery is at least once; callbacks must tolerate a crash after their side effect.
     */
    public synchronized int poll() {
        if (closed.get()) throw new IllegalStateException("Event client closed");
        changes.clear();
        EventPage page;
        try {
            page = eventPage.apply(cursor, 100);
        } catch (DurableException error) {
            if (error.code() != DurableException.Code.CURSOR_EXPIRED) throw error;
            resynchronize();
            return 0;
        }
        if (!replayBoundaryCaptured) {
            replayHighWatermark = page.highWatermark();
            replayBoundaryCaptured = true;
        }
        for (var event : page.events()) {
            if (closed.get()) return 0;
            events.accept(event);
            cursor = event.cursor();
            if (Objects.equals(cursor, replayHighWatermark)) replayCaughtUp = true;
        }
        cursor = page.cursor();
        if (Objects.equals(cursor, replayHighWatermark)) replayCaughtUp = true;
        return page.events().size();
    }

    synchronized void resynchronize() {
        if (closed.get()) return;
        var snapshot = this.snapshot.get();
        if (closed.get()) return;
        snapshots.accept(snapshot);
        cursor = snapshot.cursor();
        replayHighWatermark = snapshot.cursor();
        replayBoundaryCaptured = true;
        replayCaughtUp = true;
    }

    boolean replayCaughtUp() {
        return replayCaughtUp;
    }

    /**
     * Wait on a worker between empty polls. A matching hint or close ends the wait early.
     * Always poll again after the timeout: hints have no delivery guarantee. No thread is owned
     * by this client, and waiting or disconnecting never owns or cancels execution.
     */
    public boolean awaitChanges(Duration timeout) throws InterruptedException {
        if (timeout.toMillis() < 1) throw new IllegalArgumentException("Positive wait required");
        if (closed.get()) return false;
        return changes.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) != null && !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        changes.offer(Boolean.TRUE);
        try {
            subscription.close();
        } catch (Exception error) {
            NotifyingSessionBackend.logFailure(session, error);
        }
    }

    public String cursor() {
        return cursor;
    }
}
