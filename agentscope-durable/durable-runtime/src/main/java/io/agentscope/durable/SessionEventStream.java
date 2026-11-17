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
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A replayable Session event stream. Paging, cursor progress, wakeups and resynchronization stay
 * inside Durable; adapters only translate stream callbacks to their transport.
 */
public final class SessionEventStream implements AutoCloseable {
    private static final int PAGE_SIZE = 100;
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(250);

    /** Event delivery callbacks. Only event handling is required for the common case. */
    @FunctionalInterface
    public interface Sink extends Consumer<PublicEvent> {
        /** A best-effort live event with no durable cursor or replay guarantee. */
        default void live(LiveEvent event) {}

        /** The committed Session cursor advanced, including past filtered-out events. */
        default void cursor(String cursor) {}

        /** No event was available before the stream waits for a change. */
        default void idle() {}

        /** The supplied cursor expired and the consumer must replace its local projection. */
        default void reset(Snapshot snapshot) {
            throw new DurableException(
                    DurableException.Code.CURSOR_EXPIRED, "Event stream requires a fresh snapshot");
        }
    }

    private final DurableSession session;
    private final EventFilter filter;
    private final EventPage firstPage;
    private final ConcurrentLinkedQueue<LiveEvent> live = new ConcurrentLinkedQueue<>();
    private final Set<String> completedMessages = ConcurrentHashMap.newKeySet();
    private final Set<String> completedToolCalls = ConcurrentHashMap.newKeySet();
    private final AutoCloseable liveSubscription;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile SessionEventClient reader;

    SessionEventStream(DurableSession session, EventFilter filter, String cursor) {
        this(session, filter, EventStreamOptions.legacy(cursor));
    }

    SessionEventStream(DurableSession session, EventFilter filter, EventStreamOptions options) {
        this.session = Objects.requireNonNull(session);
        this.filter = Objects.requireNonNull(filter);
        Objects.requireNonNull(options);
        AutoCloseable subscription = () -> {};
        if (!options.deltas().isEmpty()) {
            try {
                subscription =
                        session.liveEvents()
                                .subscribe(
                                        event -> {
                                            if (!closed.get()
                                                    && filter.matches(event)
                                                    && options.accepts(event)) live.add(event);
                                        });
            } catch (RuntimeException error) {
                logLiveFailure(error);
            }
        }
        try {
            this.firstPage = session.listEvents(filter, options.cursor(), PAGE_SIZE);
            this.liveSubscription = subscription;
        } catch (RuntimeException error) {
            close(subscription);
            throw error;
        }
    }

    /**
     * Consume committed events, opted-in previews and snapshot resets as one typed stream. This is
     * the preferred application-facing API; protocol adapters may continue to use {@link Sink}.
     */
    public void forEach(Consumer<SessionStreamEvent> events) {
        forEach(events, null, DEFAULT_POLL_INTERVAL);
    }

    /** Consume the typed stream until the duration expires or the stream is closed. */
    public void forEach(Consumer<SessionStreamEvent> events, Duration duration) {
        forEach(events, duration, DEFAULT_POLL_INTERVAL);
    }

    /** Consume the typed stream with explicit bounds for hosts that manage connection lifetimes. */
    public void forEach(
            Consumer<SessionStreamEvent> events, Duration duration, Duration pollInterval) {
        Objects.requireNonNull(events);
        transferTo(
                new Sink() {
                    @Override
                    public void accept(PublicEvent event) {
                        events.accept(new SessionStreamEvent.Committed(event));
                    }

                    @Override
                    public void live(LiveEvent event) {
                        events.accept(SessionStreamEvent.from(event));
                    }

                    @Override
                    public void reset(Snapshot snapshot) {
                        events.accept(new SessionStreamEvent.SnapshotReset(snapshot));
                    }
                },
                duration,
                pollInterval);
    }

    /** Transfer events until the stream is closed. */
    public void transferTo(Sink sink) {
        transferTo(sink, null, DEFAULT_POLL_INTERVAL);
    }

    /** Transfer events until the duration expires or the stream is closed. */
    public void transferTo(Sink sink, Duration duration) {
        transferTo(sink, duration, DEFAULT_POLL_INTERVAL);
    }

    /** Transfer events with explicit bounds for hosts that manage connection lifetimes. */
    public void transferTo(Sink sink, Duration duration, Duration pollInterval) {
        Objects.requireNonNull(sink);
        Objects.requireNonNull(pollInterval);
        if (duration != null && duration.toMillis() < 1)
            throw new IllegalArgumentException("Positive stream duration required");
        if (pollInterval.toMillis() < 1)
            throw new IllegalArgumentException("Positive poll interval required");
        if (!started.compareAndSet(false, true))
            throw new IllegalStateException("Event stream already transferred");
        if (closed.get()) return;
        SessionEventClient current = null;
        long until = duration == null ? 0 : System.nanoTime() + duration.toNanos();
        try {
            for (var event : firstPage.events()) {
                if (closed.get()) return;
                durable(sink, event);
            }
            boolean replayCaughtUp = Objects.equals(firstPage.cursor(), firstPage.highWatermark());
            if (replayCaughtUp) drainLive(sink);
            if (filter.threadId() != null) sink.cursor(firstPage.cursor());
            if (closed.get() || expired(until)) return;
            var reset = new AtomicBoolean();
            current =
                    new SessionEventClient(
                            session,
                            filter,
                            firstPage.cursor(),
                            snapshot -> {
                                reset.set(true);
                                sink.reset(snapshot);
                            },
                            event -> durable(sink, event));
            reader = current;
            if (closed.get()) return;
            while (!closed.get()) {
                int delivered = current.poll();
                replayCaughtUp = replayCaughtUp || current.replayCaughtUp();
                if (replayCaughtUp) delivered += drainLive(sink);
                if (reset.get()) return;
                if (filter.threadId() != null) sink.cursor(current.cursor());
                if (expired(until)) return;
                if (delivered == 0) {
                    sink.idle();
                    current.awaitChanges(waitTime(until, pollInterval));
                }
            }
        } catch (IllegalStateException error) {
            if (!closed.get()) throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            reader = null;
            if (current != null) current.close();
            close(liveSubscription);
            closed.set(true);
        }
    }

    private void durable(Sink sink, PublicEvent event) {
        if ("message.completed".equals(event.type()))
            completedMessages.add(event.data().path("messageId").asText());
        if ("tool.call.updated".equals(event.type()))
            completedToolCalls.add(event.data().path("callId").asText());
        sink.accept(event);
    }

    private int drainLive(Sink sink) {
        int delivered = 0;
        for (LiveEvent event; (event = live.poll()) != null; ) {
            String messageId = event.data().path("messageId").asText(null);
            if (messageId != null && completedMessages.contains(messageId)) continue;
            String toolCallId = event.data().path("toolCallId").asText(null);
            if (toolCallId != null && completedToolCalls.contains(toolCallId)) continue;
            sink.live(event);
            delivered++;
        }
        return delivered;
    }

    private static boolean expired(long until) {
        return until != 0 && until - System.nanoTime() <= 0;
    }

    private static Duration waitTime(long until, Duration pollInterval) {
        if (until == 0) return pollInterval;
        long remaining = until - System.nanoTime();
        return Duration.ofMillis(
                Math.max(
                        1,
                        Math.min(pollInterval.toMillis(), Duration.ofNanos(remaining).toMillis())));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        var current = reader;
        if (current != null) current.close();
        close(liveSubscription);
    }

    private static void close(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception error) {
            System.getLogger(SessionEventStream.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Live event subscription failed to close",
                            error);
        }
    }

    private static void logLiveFailure(RuntimeException error) {
        System.getLogger(SessionEventStream.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Live event subscription unavailable; durable streaming continues",
                        error);
    }
}
