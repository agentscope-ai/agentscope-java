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
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runtime-owned, serial event delivery. Durable callbacks acknowledge on normal return; failures
 * retry after the runtime poll interval. Durable payloads are reread from the backend rather than
 * queued in memory; best-effort live payloads are buffered only while this subscription is active.
 * Closing stops future delivery without cancelling a Turn; a callback already executing may
 * finish.
 */
public final class SessionEventSubscription implements AutoCloseable {
    private final DurableSession session;
    private final EventFilter filter;
    private final String initialCursor;
    private final Consumer<Snapshot> snapshots;
    private final Consumer<PublicEvent> events;
    private final Consumer<LiveEvent> liveEvents;
    private final Consumer<Throwable> errors;
    private final Consumer<SessionEventSubscription> removed;
    private final ScheduledExecutorService scheduler;
    private final long pollMillis;
    private final long retryMillis;
    private final Object lifecycle = new Object();
    private volatile SessionEventClient reader;
    private volatile Throwable lastFailure;
    private volatile boolean closed;
    private boolean snapshotPending;
    private boolean running;
    private boolean signalled;
    private boolean immediate;
    private long generation;
    private long retryAfter;
    private ScheduledFuture<?> scheduled;
    private final ArrayBlockingQueue<LiveEvent> live =
            new ArrayBlockingQueue<>(EventStreamOptions.LIVE_BUFFER_CAPACITY);
    private final LivePreviewState previews = new LivePreviewState();
    private AutoCloseable liveSubscription;

    SessionEventSubscription(
            DurableSession session,
            EventFilter filter,
            String cursor,
            boolean initialSnapshot,
            Consumer<Snapshot> snapshots,
            Consumer<PublicEvent> events,
            Consumer<LiveEvent> liveEvents,
            Consumer<Throwable> errors,
            ScheduledExecutorService scheduler,
            Duration pollInterval,
            Duration retryInterval,
            Consumer<SessionEventSubscription> removed) {
        this.session = Objects.requireNonNull(session);
        this.filter = Objects.requireNonNull(filter);
        this.initialCursor = cursor;
        this.snapshotPending = initialSnapshot;
        this.snapshots = Objects.requireNonNull(snapshots);
        this.events = Objects.requireNonNull(events);
        this.liveEvents = liveEvents;
        this.errors = Objects.requireNonNull(errors);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.pollMillis = pollInterval.toMillis();
        this.retryMillis = retryInterval.toMillis();
        this.removed = Objects.requireNonNull(removed);
    }

    void start() {
        synchronized (lifecycle) {
            if (closed) return;
            if (liveEvents != null) {
                try {
                    liveSubscription =
                            session.liveEvents()
                                    .subscribe(
                                            event -> {
                                                if (!closed
                                                        && session.key().equals(event.session())
                                                        && filter.matches(event)
                                                        && live.offer(event)) {
                                                    changed();
                                                }
                                            });
                } catch (RuntimeException error) {
                    logLiveFailure(error);
                }
            }
            try {
                schedule(0);
            } catch (RuntimeException error) {
                closeLiveSubscription();
                throw error;
            }
        }
    }

    private void schedule(long delay) {
        long token = ++generation;
        immediate = delay == 0;
        scheduled = scheduler.schedule(() -> deliver(token), delay, TimeUnit.MILLISECONDS);
    }

    private void changed() {
        synchronized (lifecycle) {
            if (closed) return;
            if (running) {
                signalled = true;
                return;
            }
            if (immediate || (retryAfter != 0 && retryAfter - System.nanoTime() > 0)) return;
            if (scheduled != null) scheduled.cancel(false);
            schedule(0);
        }
    }

    private void deliver(long token) {
        synchronized (lifecycle) {
            if (closed || token != generation) return;
            scheduled = null;
            immediate = false;
            running = true;
            signalled = false;
        }
        boolean failed = false;
        long delay = pollMillis;
        try {
            if (reader == null) {
                reader =
                        new SessionEventClient(
                                session,
                                filter,
                                initialCursor,
                                snapshot -> {
                                    live.clear();
                                    snapshots.accept(snapshot);
                                },
                                this::durable,
                                this::changed);
                if (closed) {
                    reader.close();
                    return;
                }
            }
            if (snapshotPending) {
                reader.resynchronize();
                snapshotPending = false;
            }
            if (closed) return;
            String before = reader.cursor();
            if (reader.poll() > 0 || !Objects.equals(before, reader.cursor())) delay = 0;
            if (reader.replayCaughtUp() && drainLive() > 0) delay = 0;
            lastFailure = null;
        } catch (RuntimeException error) {
            failed = true;
            if (!closed) {
                lastFailure = error;
                errors.accept(error);
            }
        } finally {
            synchronized (lifecycle) {
                running = false;
                if (!closed) {
                    retryAfter =
                            failed
                                    ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryMillis)
                                    : 0;
                    schedule(failed ? retryMillis : signalled ? 0 : delay);
                }
            }
        }
    }

    private int drainLive() {
        int delivered = 0;
        for (int i = 0; i < EventStreamOptions.LIVE_BUFFER_CAPACITY; i++) {
            LiveEvent event = live.poll();
            if (event == null || closed) break;
            if (!previews.accepts(event)) continue;
            liveEvents.accept(event);
            delivered++;
        }
        return delivered;
    }

    private void durable(PublicEvent event) {
        previews.committed(event);
        events.accept(event);
    }

    /** Last successfully acknowledged cursor; persist event/snapshot cursors inside callbacks. */
    public String cursor() {
        var current = reader;
        return current == null ? initialCursor : current.cursor();
    }

    /** Most recent delivery failure, cleared after a successful poll. */
    public Throwable lastFailure() {
        return lastFailure;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            ++generation;
            if (scheduled != null) scheduled.cancel(false);
        }
        try {
            var current = reader;
            if (current != null) current.close();
        } finally {
            closeLiveSubscription();
            live.clear();
            removed.accept(this);
        }
    }

    private void closeLiveSubscription() {
        var current = liveSubscription;
        liveSubscription = null;
        if (current == null) return;
        try {
            current.close();
        } catch (Exception error) {
            System.getLogger(SessionEventSubscription.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Live event subscription failed to close",
                            error);
        }
    }

    private static void logLiveFailure(RuntimeException error) {
        System.getLogger(SessionEventSubscription.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Live event subscription unavailable; durable observation continues",
                        error);
    }
}
