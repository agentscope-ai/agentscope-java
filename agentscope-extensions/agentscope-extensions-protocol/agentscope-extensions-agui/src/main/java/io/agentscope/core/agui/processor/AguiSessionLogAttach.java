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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiFramedEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * Replays committed {@link SessionLog} events after a recovery cursor.
 *
 * <p>This is a pure observer: it never starts or resumes an Agent run and never builds a message
 * snapshot. The application supplies the conversation history and a cursor that is a presentation
 * boundary. If the run is still active, the stream polls the session head and reads new commits
 * until the run ends; if it has already ended, the stream drains the tail and finishes.
 *
 * <p>The wait between polls is scheduled through Reactor, so an idle attach does not hold a worker
 * thread. The blocking store reads run briefly on {@code boundedElastic}.
 *
 * <p>{@code config.getRunTimeout()} bounds an idle wait, not the total duration: it fires when the
 * committed head stops advancing for that long, not while the run keeps producing events.
 *
 * <p>{@code executionRunId}, when provided, must be the session writer's run id — the
 * {@link SessionLog.Head#owner()} value written by the {@code SessionRecorder} that produced the
 * events — not an unrelated application-level run identifier. Ownership decides whether a cursor
 * ahead of the committed head is still reachable.
 */
final class AguiSessionLogAttach {

    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(200);

    /**
     * Upper bound for waiting on an uncommitted cursor or boundary. Commits happen on the writer's
     * heartbeat, so this is a short wait, not a full attach: reusing the run timeout would keep an
     * SSE connection open long after the client or gateway has given up.
     */
    private static final Duration COMMIT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    private static final int BATCH_SIZE = 256;

    private AguiSessionLogAttach() {}

    static Flux<AguiFramedEvent> attach(
            RunAgentInput input,
            SessionLog log,
            long after,
            String executionRunId,
            AguiAdapterConfig config) {
        return attach(input, log, after, executionRunId, config, DEFAULT_POLL_INTERVAL);
    }

    static Flux<AguiFramedEvent> attach(
            RunAgentInput input,
            SessionLog log,
            long after,
            String executionRunId,
            AguiAdapterConfig config,
            Duration pollInterval) {
        return Flux.concat(
                runStartedPrefix(input, log, after, executionRunId),
                Flux.<AguiFramedEvent>create(
                        sink ->
                                runAttach(
                                        sink,
                                        input,
                                        log,
                                        after,
                                        executionRunId,
                                        config,
                                        pollInterval)));
    }

    /**
     * AG-UI transports require the first event of a run/connect stream to be {@code RUN_STARTED}.
     * A reconnect whose cursor already passed {@code run/start} would otherwise start with a
     * content or tool event, which official clients reject. Emit a synthetic, uncursored
     * RUN_STARTED when the replay range no longer contains the committed run/start event.
     */
    private static Flux<AguiFramedEvent> runStartedPrefix(
            RunAgentInput input, SessionLog log, long after, String executionRunId) {
        return Flux.defer(
                        () -> {
                            try {
                                long runStart = lastRunStartSeq(log);
                                if (runStart > 0 && after >= runStart) {
                                    String runId =
                                            executionRunId == null || executionRunId.isBlank()
                                                    ? input.getRunId()
                                                    : executionRunId;
                                    return Flux.just(
                                            new AguiFramedEvent(
                                                    -1,
                                                    -1,
                                                    new AguiEvent.RunStarted(
                                                            input.getThreadId(),
                                                            runId,
                                                            null,
                                                            input)));
                                }
                            } catch (RuntimeException ignored) {
                                // Fall back to the committed log, which contains run/start when
                                // it is available.
                            }
                            return Flux.empty();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Sequence of the most recent committed {@code run/start} event, or {@code 0} when none.
     *
     * <p>Reads backwards from the head in batches, so the check costs a few recent segments
     * rather than the whole journal.
     */
    private static long lastRunStartSeq(SessionLog log) {
        long end = log.head().seq();
        while (end > 0) {
            long start = Math.max(0, end - BATCH_SIZE);
            List<SessionEvent> batch = log.readAfter(start, (int) (end - start));
            for (int i = batch.size() - 1; i >= 0; i--) {
                SessionEvent event = batch.get(i);
                if ("run/start".equals(event.type())) {
                    return event.seq();
                }
            }
            end = start;
        }
        return 0;
    }

    /**
     * Bridges the session log and the live tail at the closest available boundary.
     *
     * <p>When the broadcaster's recent-frame buffer reaches the cursor, that part is served from
     * memory and the stream continues with the live tail without waiting for a commit. An older
     * cursor replays the log up to the oldest buffered event, then continues from the buffer and
     * tail. Frames at or before the handoff boundary are dropped, including the leading frames of
     * an event that carry no cursor of their own. When no run is active in this process, the stream
     * falls back to following the session log.
     *
     * @param input the AG-UI input
     * @param log the session log to read
     * @param after the cursor already represented by the application history
     * @param live the live tail of the running thread, or {@code null} when none is active here
     * @param executionRunId the execution run id, or {@code null} for any run
     * @param config the adapter config
     * @return framed AG-UI events with no gap and no duplicate
     */
    static Flux<AguiFramedEvent> attach(
            RunAgentInput input,
            SessionLog log,
            long after,
            AguiRunBroadcaster.LiveRun live,
            String executionRunId,
            AguiAdapterConfig config) {
        // A live tail is only usable when it belongs to the requested execution; the log always
        // covers the cursor itself.
        if (live == null || (executionRunId != null && !executionRunId.equals(live.runId()))) {
            if (live != null) {
                // Release the registered tail: its subscription never starts, so it would
                // otherwise stay buffered on the channel for the rest of the run.
                releaseLiveRun(live);
            }
            return attach(input, log, after, executionRunId, config);
        }
        if (!live.recent().isEmpty() && live.lastSeq() < 0 && live.oldestSeq() < 0) {
            // Buffered frames exist but none carries a session sequence (for example a HarnessAgent
            // whose events carry none). Such a tail cannot be deduplicated against the log, so use
            // the deterministic log path instead. An empty snapshot is different: later frames may
            // still carry sequences, so keep the live tail.
            releaseLiveRun(live);
            return attach(input, log, after, executionRunId, config);
        }
        return Flux.defer(
                        () -> {
                            SessionLog.Head initialHead = log.head();
                            boolean bufferCoversAfter =
                                    !live.recent().isEmpty()
                                            && live.oldestSeq() >= 0
                                            && after >= live.oldestSeq() - 1
                                            && after <= Math.max(live.lastSeq(), initialHead.seq());
                            if (after > initialHead.seq()
                                    && !bufferCoversAfter
                                    && !isActive(initialHead, executionRunId)) {
                                // A cursor ahead of a dead writer cannot be served; let the log
                                // path report it instead of silently returning only future frames.
                                releaseLiveRun(live);
                                return attach(input, log, after, executionRunId, config);
                            }
                            // One projection for both ranges, so a message started before the tail
                            // still receives its terminal frame from the drain below.
                            AguiSessionPresentation presentation =
                                    new AguiSessionPresentation(
                                            input.getThreadId(), input.getRunId(), config, input);

                            List<AguiFramedEvent> buffered;
                            Flux<AguiFramedEvent> catchUp;
                            long tailBoundary;
                            long oldestSeq = live.oldestSeq();
                            if (live.recent().isEmpty()) {
                                // No recent frames yet: keep the log-only bridge.
                                long through =
                                        Math.max(
                                                after, Math.max(initialHead.seq(), live.lastSeq()));
                                catchUp =
                                        through > after
                                                ? attachUntil(
                                                        input,
                                                        log,
                                                        after,
                                                        through,
                                                        executionRunId,
                                                        config,
                                                        presentation)
                                                : Flux.empty();
                                buffered = List.of();
                                tailBoundary = through;
                            } else if (bufferCoversAfter) {
                                // The recent buffer reaches the client cursor: no log read and no
                                // commit wait is needed for the handoff.
                                catchUp = Flux.empty();
                                buffered = recentAfter(live.recent(), after);
                                tailBoundary = Math.max(after, live.lastSeq());
                            } else {
                                // The cursor is older than the buffer: replay the log up to the
                                // first buffered event, then continue from the buffer.
                                long through =
                                        oldestSeq >= 0
                                                ? Math.max(after, oldestSeq - 1)
                                                : Math.max(after, initialHead.seq());
                                catchUp =
                                        through > after
                                                ? attachUntil(
                                                        input,
                                                        log,
                                                        after,
                                                        through,
                                                        executionRunId,
                                                        config,
                                                        presentation)
                                                : Flux.empty();
                                // The log covers up to oldestSeq-1 and the buffer covers from
                                // oldestSeq; still trim anything at or before the requested
                                // cursor so a partly overlapping buffer cannot duplicate it.
                                buffered = recentAfter(live.recent(), after);
                                tailBoundary = Math.max(after, Math.max(through, live.lastSeq()));
                            }

                            AtomicLong delivered = new AtomicLong(tailBoundary);
                            AtomicLong completed = new AtomicLong(tailBoundary);
                            AtomicLong lastSeen = new AtomicLong(-1);
                            AtomicBoolean tailFailed = new AtomicBoolean();
                            AtomicBoolean tailSubscribed = new AtomicBoolean();
                            Flux<AguiFramedEvent> tail =
                                    live.frames()
                                            .doOnSubscribe(ignored -> tailSubscribed.set(true))
                                            .doOnNext(
                                                    frame -> {
                                                        if (frame.sourceSeq() > delivered.get()) {
                                                            delivered.set(frame.sourceSeq());
                                                        }
                                                        long sourceSeq = frame.sourceSeq();
                                                        if (sourceSeq >= 0) {
                                                            long previous =
                                                                    lastSeen.getAndSet(sourceSeq);
                                                            if (previous >= 0
                                                                    && previous != sourceSeq) {
                                                                completed.accumulateAndGet(
                                                                        previous, Math::max);
                                                            }
                                                        }
                                                        if (frame.hasCursor()) {
                                                            completed.accumulateAndGet(
                                                                    frame.seq(), Math::max);
                                                        }
                                                    })
                                            .onErrorResume(
                                                    error -> {
                                                        // An overflowing tail degrades to the
                                                        // session log instead of failing attach.
                                                        tailFailed.set(true);
                                                        return Flux.empty();
                                                    })
                                            .filter(
                                                    frame ->
                                                            frame.sourceSeq() < 0
                                                                    || frame.isAfter(tailBoundary));
                            // The tail only carries frames published after it was taken. Anything
                            // broadcast earlier is committed in the log; read what is left once
                            // the tail ends instead of losing its terminal frames.
                            Flux<AguiFramedEvent> drain =
                                    Flux.defer(
                                                    () -> {
                                                        if (tailFailed.get()) {
                                                            // Fall back to log polling from the
                                                            // last complete event, so a partially
                                                            // delivered event is replayed.
                                                            return attach(
                                                                    input,
                                                                    log,
                                                                    completed.get(),
                                                                    executionRunId,
                                                                    config);
                                                        }
                                                        long cursor = delivered.get();
                                                        SessionLog.Head head = log.head();
                                                        if (cursor < head.seq()) {
                                                            return attachUntil(
                                                                    input,
                                                                    log,
                                                                    cursor,
                                                                    head.seq(),
                                                                    executionRunId,
                                                                    config,
                                                                    presentation);
                                                        }
                                                        if (after > head.seq()
                                                                && !bufferCoversAfter
                                                                && !isActive(
                                                                        head, executionRunId)) {
                                                            // The writer ended without ever
                                                            // reaching the requested cursor: let
                                                            // the log path report it instead of
                                                            // completing silently.
                                                            return attach(
                                                                    input,
                                                                    log,
                                                                    after,
                                                                    executionRunId,
                                                                    config);
                                                        }
                                                        return Flux.<AguiFramedEvent>empty();
                                                    })
                                            .subscribeOn(Schedulers.boundedElastic());
                            return Flux.concat(catchUp, Flux.fromIterable(buffered), tail, drain)
                                    .doFinally(
                                            signal -> {
                                                if (!tailSubscribed.get()) {
                                                    // The replay ended before the tail was
                                                    // subscribed: release its channel slot.
                                                    releaseLiveRun(live);
                                                }
                                            });
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Replays committed events in {@code (after, through]} and completes, without following the
     * run. Used to bridge the gap between the application cursor and a live tail buffer whose
     * oldest frame is newer than the cursor.
     *
     * <p>Like {@link #attach}, a range that cannot be replayed ends with a {@code RUN_ERROR} frame
     * instead of a transport error, so the caller can bridge a live tail or fall back to a full
     * replay. An inverted range ({@code after > through}) is rejected as invalid input.
     */
    static Flux<AguiFramedEvent> attachUntil(
            RunAgentInput input,
            SessionLog log,
            long after,
            long through,
            String executionRunId,
            AguiAdapterConfig config) {
        return attachUntil(input, log, after, through, executionRunId, config, null);
    }

    /**
     * Replays a range, optionally continuing the projection state of an earlier call so that a
     * message started before the range still receives its terminal frame.
     */
    private static Flux<AguiFramedEvent> attachUntil(
            RunAgentInput input,
            SessionLog log,
            long after,
            long through,
            String executionRunId,
            AguiAdapterConfig config,
            AguiSessionPresentation sharedPresentation) {
        return Flux.create(
                sink -> {
                    if (after > through) {
                        emitFrames(
                                sink,
                                errorFrames(
                                        input,
                                        after,
                                        "Session log attach range is inverted: after="
                                                + after
                                                + " through="
                                                + through,
                                        "INVALID_INPUT_ERROR",
                                        config,
                                        false));
                        return;
                    }
                    if (after == through) {
                        sink.complete();
                        return;
                    }
                    AtomicReference<Disposable> pending = new AtomicReference<>();
                    sink.onDispose(
                            () -> {
                                Disposable disposable = pending.getAndSet(null);
                                if (disposable != null) {
                                    disposable.dispose();
                                }
                            });
                    Disposable scheduled =
                            Schedulers.boundedElastic()
                                    .schedule(
                                            () -> {
                                                if (sink.isCancelled()) {
                                                    return;
                                                }
                                                // Hoisted so a failure frame reports the last
                                                // fully emitted sequence, not the bridge start.
                                                long cursor = after;
                                                try {
                                                    AguiSessionPresentation presentation =
                                                            sharedPresentation != null
                                                                    ? sharedPresentation
                                                                    : new AguiSessionPresentation(
                                                                            input.getThreadId(),
                                                                            input.getRunId(),
                                                                            config,
                                                                            input);
                                                    long deadline =
                                                            System.nanoTime()
                                                                    + commitWaitTimeout(config)
                                                                            .toNanos();
                                                    while (cursor < through
                                                            && !sink.isCancelled()) {
                                                        List<SessionEvent> batch =
                                                                log.readAfter(cursor, BATCH_SIZE);
                                                        if (batch.isEmpty()) {
                                                            SessionLog.Head head = log.head();
                                                            // The boundary is either buffered in
                                                            // the writer or readAfter raced the
                                                            // head update it already reflects.
                                                            // Retry while it stays reachable; a
                                                            // dead writer with an unreachable
                                                            // boundary ends below.
                                                            boolean reachable =
                                                                    head.seq() >= through
                                                                            || isActive(
                                                                                    head,
                                                                                    executionRunId);
                                                            if (!reachable) break;
                                                            if (System.nanoTime() >= deadline) {
                                                                emitFrames(
                                                                        sink,
                                                                        errorFrames(
                                                                                input,
                                                                                cursor,
                                                                                "Session log attach"
                                                                                        + " timed"
                                                                                        + " out at"
                                                                                        + " cursor="
                                                                                        + cursor
                                                                                        + " through="
                                                                                        + through,
                                                                                "TIMEOUT_ERROR",
                                                                                config,
                                                                                false));
                                                                return;
                                                            }
                                                            try {
                                                                Thread.sleep(
                                                                        DEFAULT_POLL_INTERVAL
                                                                                .toMillis());
                                                            } catch (
                                                                    InterruptedException
                                                                            interrupted) {
                                                                Thread.currentThread().interrupt();
                                                                if (!sink.isCancelled()) {
                                                                    emitFrames(
                                                                            sink,
                                                                            errorFrames(
                                                                                    input,
                                                                                    cursor,
                                                                                    "Session log"
                                                                                        + " attach"
                                                                                        + " interrupted",
                                                                                    "SESSION_LOG_ERROR",
                                                                                    config,
                                                                                    false));
                                                                }
                                                                return;
                                                            }
                                                            continue;
                                                        }
                                                        long before = cursor;
                                                        for (SessionEvent event : batch) {
                                                            if (event.seq() > through) break;
                                                            boolean target =
                                                                    executionRunId == null
                                                                            || executionRunId
                                                                                    .equals(
                                                                                            event
                                                                                                    .executionRunId());
                                                            if (target) {
                                                                for (AguiFramedEvent frame :
                                                                        frames(
                                                                                presentation.apply(
                                                                                        event),
                                                                                event.seq(),
                                                                                cursored(
                                                                                        event
                                                                                                .type()))) {
                                                                    if (sink.isCancelled()) {
                                                                        return;
                                                                    }
                                                                    sink.next(frame);
                                                                }
                                                            }
                                                            // Advance only after the event's
                                                            // frames are emitted, so a later
                                                            // failure reports a cursor the client
                                                            // already holds.
                                                            cursor = event.seq();
                                                        }
                                                        if (cursor == before) break;
                                                    }
                                                    if (cursor < through) {
                                                        emitFrames(
                                                                sink,
                                                                errorFrames(
                                                                        input,
                                                                        cursor,
                                                                        "Session log attach"
                                                                                + " range not fully"
                                                                                + " committed:"
                                                                                + " cursor="
                                                                                + cursor
                                                                                + " through="
                                                                                + through,
                                                                        "INVALID_INPUT_ERROR",
                                                                        config,
                                                                        false));
                                                    } else {
                                                        sink.complete();
                                                    }
                                                } catch (RuntimeException error) {
                                                    emitFrames(
                                                            sink,
                                                            errorFrames(
                                                                    input,
                                                                    cursor,
                                                                    errorMessage(error),
                                                                    "SESSION_LOG_ERROR",
                                                                    config,
                                                                    false));
                                                }
                                            });
                    pending.set(scheduled);
                    if (sink.isCancelled()) {
                        Disposable disposable = pending.getAndSet(null);
                        if (disposable != null) {
                            disposable.dispose();
                        }
                    }
                });
    }

    private static void runAttach(
            FluxSink<AguiFramedEvent> sink,
            RunAgentInput input,
            SessionLog log,
            long after,
            String executionRunId,
            AguiAdapterConfig config,
            Duration pollInterval) {
        AtomicReference<Disposable> pending = new AtomicReference<>();
        sink.onDispose(
                () -> {
                    Disposable disposable = pending.getAndSet(null);
                    if (disposable != null) {
                        disposable.dispose();
                    }
                });

        // The store is a blocking SPI: probe the head off the subscribing thread, and only emit
        // RUN_STARTED once the probe succeeded.
        Schedulers.boundedElastic()
                .schedule(
                        () -> {
                            if (sink.isCancelled()) {
                                return;
                            }
                            try {
                                // A cursor may reference an event that is appended but not yet
                                // committed; that case waits in pollOnce while the writer is
                                // alive. A cursor that no writer can ever commit is rejected
                                // now, not on the first asynchronous poll after RUN_STARTED.
                                SessionLog.Head head =
                                        Objects.requireNonNull(log.head(), "session log head");
                                if (after > head.seq() && !isActive(head, executionRunId)) {
                                    emitFrames(
                                            sink,
                                            errorFrames(
                                                    input,
                                                    // Never publish the invalid cursor itself:
                                                    // a non-negative seq is an SSE id a client
                                                    // would store and resume from again.
                                                    -1,
                                                    "Session log cursor "
                                                            + after
                                                            + " is ahead of "
                                                            + head.seq(),
                                                    "INVALID_INPUT_ERROR",
                                                    config,
                                                    true));
                                    return;
                                }
                            } catch (RuntimeException error) {
                                emitFrames(
                                        sink,
                                        errorFrames(
                                                input,
                                                -1,
                                                errorMessage(error),
                                                "SESSION_LOG_ERROR",
                                                config,
                                                true));
                                return;
                            }
                            startAttach(
                                    sink,
                                    input,
                                    log,
                                    after,
                                    executionRunId,
                                    config,
                                    pollInterval,
                                    pending);
                        });
    }

    private static void startAttach(
            FluxSink<AguiFramedEvent> sink,
            RunAgentInput input,
            SessionLog log,
            long after,
            String executionRunId,
            AguiAdapterConfig config,
            Duration pollInterval,
            AtomicReference<Disposable> pending) {
        Duration poll = pollInterval != null ? pollInterval : DEFAULT_POLL_INTERVAL;
        Duration timeout =
                config.getRunTimeout() != null ? config.getRunTimeout() : Duration.ofMinutes(10);
        long deadline = System.nanoTime() + timeout.toNanos();
        long commitDeadline = System.nanoTime() + commitWaitTimeout(config).toNanos();
        AttachState state =
                new AttachState(
                        after,
                        new AguiSessionPresentation(
                                input.getThreadId(), input.getRunId(), config, input));

        // RUN_STARTED is projected from the committed run/start event, so a reconnect that already
        // passed it is not told a new run began.
        AtomicReference<Runnable> pollRef = new AtomicReference<>();
        Runnable pollTask =
                () -> {
                    if (sink.isCancelled()) {
                        return;
                    }
                    Disposable scheduled =
                            Schedulers.boundedElastic()
                                    .schedule(
                                            () -> {
                                                if (sink.isCancelled()) {
                                                    return;
                                                }
                                                PollOutcome outcome;
                                                try {
                                                    outcome =
                                                            pollOnce(
                                                                    log,
                                                                    state,
                                                                    input,
                                                                    executionRunId,
                                                                    config,
                                                                    deadline,
                                                                    commitDeadline);
                                                } catch (RuntimeException error) {
                                                    outcome =
                                                            new PollOutcome(
                                                                    errorFrames(
                                                                            input,
                                                                            state.cursor,
                                                                            errorMessage(error),
                                                                            "SESSION_LOG_ERROR",
                                                                            config,
                                                                            false),
                                                                    true);
                                                }
                                                for (AguiFramedEvent frame : outcome.frames()) {
                                                    if (sink.isCancelled()) {
                                                        return;
                                                    }
                                                    sink.next(frame);
                                                }
                                                if (outcome.terminal()) {
                                                    sink.complete();
                                                    return;
                                                }
                                                long delay =
                                                        outcome.frames().isEmpty()
                                                                ? Math.max(0L, poll.toMillis())
                                                                : 0L;
                                                pending.set(
                                                        Schedulers.parallel()
                                                                .schedule(
                                                                        pollRef.get(),
                                                                        delay,
                                                                        TimeUnit.MILLISECONDS));
                                            });
                    pending.set(scheduled);
                    if (sink.isCancelled()) {
                        Disposable disposable = pending.getAndSet(null);
                        if (disposable != null) {
                            disposable.dispose();
                        }
                    }
                };
        pollRef.set(pollTask);
        Schedulers.parallel().schedule(pollTask, 0, TimeUnit.MILLISECONDS);
    }

    /** Returns the committed event at the given sequence, or {@code null} when unavailable. */
    private static SessionEvent eventAt(SessionLog log, long seq) {
        if (seq <= 0) {
            return null;
        }
        List<SessionEvent> events = log.readAfter(seq - 1, 1);
        return events.isEmpty() ? null : events.get(0);
    }

    private static PollOutcome pollOnce(
            SessionLog log,
            AttachState state,
            RunAgentInput input,
            String executionRunId,
            AguiAdapterConfig config,
            long deadline,
            long commitDeadline) {
        SessionLog.Head head = log.head();
        if (head.seq() < state.cursor) {
            // The cursor references an event that is appended but not yet committed. Wait for the
            // writer to commit it; a dead writer means the cursor can never be satisfied. This
            // wait is bounded by commitDeadline, not by the run timeout: a cursor that does not
            // belong to this log must not hold the stream open for the whole run.
            if (!isActive(head, executionRunId)) {
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log cursor "
                                        + state.cursor
                                        + " is ahead of committed "
                                        + head.seq(),
                                "INVALID_INPUT_ERROR",
                                config,
                                false),
                        true);
            }
            if (System.nanoTime() >= commitDeadline) {
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log has not committed cursor " + state.cursor,
                                "TIMEOUT_ERROR",
                                config,
                                false),
                        true);
            }
            return new PollOutcome(List.of(), false);
        }
        if (head.seq() == state.cursor) {
            if (!isActive(head, executionRunId)) {
                // The cursor may already cover a failed run/end. Recover the terminal status from
                // the log instead of reporting every finished run as successful.
                if (state.cursor == 0 && head.seq() == 0) {
                    if (executionRunId != null) {
                        // A run was requested but the log holds nothing for this session: report
                        // it instead of pretending the run finished successfully.
                        return new PollOutcome(
                                errorFrames(
                                        input,
                                        -1,
                                        "Session log has no events for run " + executionRunId,
                                        "INVALID_INPUT_ERROR",
                                        config,
                                        false),
                                true);
                    }
                    // Nothing was ever committed for this session.
                    return new PollOutcome(
                            finishFrames(input, state.presentation, 0, null, config), true);
                }
                SessionEvent terminal = eventAt(log, state.cursor);
                boolean terminalRunEnd =
                        terminal != null
                                && "run/end".equals(terminal.type())
                                && (executionRunId == null
                                        || executionRunId.equals(terminal.executionRunId()));
                if (terminalRunEnd) {
                    return new PollOutcome(
                            finishFrames(input, state.presentation, state.cursor, terminal, config),
                            true);
                }
                if (executionRunId != null && !state.targetSeen) {
                    // The target run ended at or before the cursor; any later events belong to
                    // another run on the same session.
                    return new PollOutcome(
                            finishFrames(input, state.presentation, state.cursor, null, config),
                            true);
                }
                // The writer is gone but the log does not end with a run/end of this run: the
                // process died mid-run, so a successful finish would be a lie.
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log ended without a terminal run event",
                                "INTERNAL_ERROR",
                                config,
                                false),
                        true);
            }
            if (System.nanoTime() >= deadline) {
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log observation timed out",
                                "TIMEOUT_ERROR",
                                config,
                                false),
                        true);
            }
            return new PollOutcome(List.of(), false);
        }

        List<SessionEvent> batch = log.readAfter(state.cursor, BATCH_SIZE);
        if (batch.isEmpty()) {
            // The head already reported committed events after the cursor, so an empty read
            // cannot mean the run ended: finishing here would drop them permanently.
            if (!isActive(head, executionRunId)) {
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log has committed events that could not be read",
                                "SESSION_LOG_ERROR",
                                config,
                                false),
                        true);
            }
            if (System.nanoTime() >= deadline) {
                return new PollOutcome(
                        errorFrames(
                                input,
                                state.cursor,
                                "Session log observation timed out",
                                "TIMEOUT_ERROR",
                                config,
                                false),
                        true);
            }
            return new PollOutcome(List.of(), false);
        }

        // Advance only after the batch is projected, so a failure mid-batch leaves the cursor at
        // the last sequence the caller already received.
        long cursor = state.cursor;
        List<AguiFramedEvent> frames = new ArrayList<>();
        for (SessionEvent event : batch) {
            boolean target =
                    executionRunId == null || executionRunId.equals(event.executionRunId());
            if (target) {
                state.targetSeen = true;
                frames.addAll(
                        frames(
                                state.presentation.apply(event),
                                event.seq(),
                                cursored(event.type())));
                if ("run/end".equals(event.type()) && executionRunId != null) {
                    state.cursor = event.seq();
                    frames.addAll(
                            finishFrames(input, state.presentation, event.seq(), event, config));
                    return new PollOutcome(frames, true);
                }
            }
            // Without a target run id the stream follows the log instead of stopping at the first
            // run/end: the cursor may sit inside an older run, and ending there would drop the run
            // the caller is actually reconnecting to.
            cursor = event.seq();
        }
        state.cursor = cursor;
        return new PollOutcome(frames, false);
    }

    /** Keeps an uncommitted-cursor wait short even when the configured run timeout is larger. */
    private static Duration commitWaitTimeout(AguiAdapterConfig config) {
        Duration runTimeout = config.getRunTimeout();
        if (runTimeout != null && runTimeout.compareTo(COMMIT_WAIT_TIMEOUT) < 0) {
            return runTimeout;
        }
        return COMMIT_WAIT_TIMEOUT;
    }

    /**
     * Releases a registered tail whose subscription never started. Subscribing with an error
     * consumer also swallows an already-failed sink instead of reporting an onErrorDropped.
     */
    private static void releaseLiveRun(AguiRunBroadcaster.LiveRun live) {
        live.frames().subscribe(ignored -> {}, ignored -> {}).dispose();
    }

    private static boolean isActive(SessionLog.Head head, String executionRunId) {
        if (head.owner() == null || head.leaseUntil() <= System.currentTimeMillis()) {
            return false;
        }
        return executionRunId == null || executionRunId.equals(head.owner());
    }

    private static List<AguiFramedEvent> finishFrames(
            RunAgentInput input,
            AguiSessionPresentation presentation,
            long cursor,
            SessionEvent runEnd,
            AguiAdapterConfig config) {
        List<AguiEvent.Interrupt> interrupts = presentation.interrupts();
        List<AguiFramedEvent> frames = new ArrayList<>();
        if (runEnd != null && "failed".equalsIgnoreCase(status(runEnd))) {
            String reason = reason(runEnd);
            frames.add(
                    new AguiFramedEvent(
                            cursor,
                            new AguiEvent.RunError(
                                    input.getThreadId(),
                                    input.getRunId(),
                                    reason != null ? reason : "Agent run failed",
                                    "INTERNAL_ERROR",
                                    System.currentTimeMillis(),
                                    null)));
            if (config.isEmitRunFinishedAfterError()) {
                frames.add(
                        new AguiFramedEvent(
                                cursor,
                                new AguiEvent.RunFinished(input.getThreadId(), input.getRunId())));
            }
            return frames;
        }

        AguiEvent.RunFinishedOutcome outcome =
                interrupts.isEmpty() ? null : new AguiEvent.RunFinishedInterruptOutcome(interrupts);
        frames.add(
                new AguiFramedEvent(
                        cursor,
                        new AguiEvent.RunFinished(
                                input.getThreadId(), input.getRunId(), null, outcome)));
        return frames;
    }

    private static List<AguiFramedEvent> errorFrames(
            RunAgentInput input,
            long cursor,
            String message,
            String code,
            AguiAdapterConfig config,
            boolean includeRunStarted) {
        List<AguiFramedEvent> frames = new ArrayList<>();
        if (includeRunStarted) {
            frames.add(
                    AguiFramedEvent.uncursored(
                            new AguiEvent.RunStarted(
                                    input.getThreadId(), input.getRunId(), null, input)));
        }
        frames.add(
                new AguiFramedEvent(
                        cursor,
                        new AguiEvent.RunError(
                                input.getThreadId(),
                                input.getRunId(),
                                message,
                                code,
                                System.currentTimeMillis(),
                                null)));
        if (config.isEmitRunFinishedAfterError()) {
            frames.add(
                    new AguiFramedEvent(
                            cursor,
                            new AguiEvent.RunFinished(input.getThreadId(), input.getRunId())));
        }
        return frames;
    }

    /**
     * Returns the buffered frames after a cursor. Frames of events at or before the cursor are
     * dropped whole, so a half-delivered event is not resumed from an arbitrary frame.
     */
    private static List<AguiFramedEvent> recentAfter(List<AguiFramedEvent> recent, long after) {
        int start = 0;
        for (int i = 0; i < recent.size(); i++) {
            AguiFramedEvent frame = recent.get(i);
            if ((frame.sourceSeq() >= 0 && frame.sourceSeq() <= after)
                    || (frame.hasCursor() && frame.seq() <= after)) {
                start = i + 1;
            }
        }
        return start >= recent.size()
                ? List.of()
                : new ArrayList<>(recent.subList(start, recent.size()));
    }

    private static List<AguiFramedEvent> frames(
            List<AguiEvent> events, long sourceSeq, boolean cursored) {
        List<AguiFramedEvent> frames = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            long seq = cursored && i == events.size() - 1 ? sourceSeq : -1;
            frames.add(new AguiFramedEvent(seq, sourceSeq, events.get(i)));
        }
        return frames;
    }

    /**
     * A streamed chunk is not a replay boundary: its frames must not advance the SSE cursor, so a
     * reconnecting client resumes from a whole message instead of the middle of one.
     */
    private static boolean cursored(String type) {
        return !"model/chunk".equals(type);
    }

    private static void emitFrames(FluxSink<AguiFramedEvent> sink, List<AguiFramedEvent> frames) {
        for (AguiFramedEvent frame : frames) {
            sink.next(frame);
        }
        sink.complete();
    }

    private static String status(SessionEvent event) {
        Object value = event.data().get("status");
        return value != null ? String.valueOf(value) : null;
    }

    private static String reason(SessionEvent event) {
        Object value = event.data().get("reason");
        return value != null ? String.valueOf(value) : null;
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private record PollOutcome(List<AguiFramedEvent> frames, boolean terminal) {}

    private static final class AttachState {
        private long cursor;

        /** Whether an event of the target run has been projected since the cursor. */
        private boolean targetSeen;

        private final AguiSessionPresentation presentation;

        private AttachState(long cursor, AguiSessionPresentation presentation) {
            this.cursor = cursor;
            this.presentation = presentation;
        }
    }
}
