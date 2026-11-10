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

import io.agentscope.core.agui.event.AguiFramedEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Process-local broadcast of the live AG-UI frames of a running thread.
 *
 * <p>An ordinary run publishes every frame it sends to its caller. A reconnecting client can then
 * replay the session log up to {@link LiveRun#lastSeq()} and continue with {@link
 * LiveRun#frames()} without a visible seam. The session log remains the source of truth: the
 * broadcast is only a low-latency tail, so a missing channel simply degrades to log polling.
 *
 * <p>Besides the live tails, a channel keeps a bounded buffer of recent frames. A reconnecting
 * caller can use that buffer to bridge from a recent cursor without waiting for the session log to
 * commit, while older cursors still fall back to the log. The buffer and the tail are captured
 * under the same lock, so a caller can treat {@code lastSeq}, {@code recent} and {@code frames} as
 * one consistent snapshot. A tail is removed when its subscription terminates.
 *
 * <p>The broadcaster is intentionally process-local. Deployments with several instances can
 * provide a shared implementation, but correctness never depends on it.
 */
public final class AguiRunBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(AguiRunBroadcaster.class);
    private static final int DEFAULT_BUFFER_SIZE = 4096;

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final int bufferSize;

    public AguiRunBroadcaster() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * @param bufferSize how many frames one tail may lag behind before it is failed and the client
     *     has to reconnect (falling back to the session log)
     */
    public AguiRunBroadcaster(int bufferSize) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.bufferSize = bufferSize;
    }

    /**
     * Registers the channel of a starting run, completing a previous run on the same key.
     *
     * @param channelKey the processor's {@code userId + agentId + threadId} channel key
     * @param runId the AG-UI run id
     */
    public void open(String channelKey, String runId) {
        if (isBlank(channelKey) || isBlank(runId)) {
            return;
        }
        // A channel key runs one run at a time: the processor rejects a second run on the same
        // thread, so a channel that is still present belongs to a finished run.
        channels.compute(
                channelKey,
                (ignored, previous) -> {
                    if (previous != null) {
                        complete(previous);
                    }
                    return new Channel(runId);
                });
    }

    /**
     * Publishes one frame of the given run. Frames of an unknown or finished run are ignored.
     *
     * @param channelKey the processor's {@code userId + agentId + threadId} channel key
     * @param runId the AG-UI run id
     * @param frame the frame to broadcast
     */
    public void publish(String channelKey, String runId, AguiFramedEvent frame) {
        if (frame != null) {
            publish(channelKey, runId, List.of(frame));
        }
    }

    /**
     * Publishes frames of the given run. Frames of an unknown or finished run are ignored, as are
     * frames of a run with no subscriber yet.
     *
     * @param channelKey the processor's {@code userId + agentId + threadId} channel key
     * @param runId the AG-UI run id
     * @param frames frames to broadcast, in order
     */
    public void publish(String channelKey, String runId, List<AguiFramedEvent> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        Channel channel = channels.get(channelKey);
        if (channel == null || !channel.runId.equals(runId)) {
            return;
        }
        // Keep the watermark update, the subscriber snapshot and the actual emission under one
        // lock. Otherwise a tail registered between the snapshot and the emit would report a
        // lastSeq that already covers a frame it never received.
        synchronized (channel.lock) {
            if (channel.closed) {
                return;
            }
            for (AguiFramedEvent frame : frames) {
                if (frame.sourceSeq() > channel.lastSeq) {
                    channel.lastSeq = frame.sourceSeq();
                }
            }
            channel.addRecent(frames, bufferSize);
            // Copy: a subscriber may unregister from doFinally while it receives the frame.
            List<Sinks.Many<AguiFramedEvent>> subscribers = new ArrayList<>(channel.subscribers);
            for (Sinks.Many<AguiFramedEvent> subscriber : subscribers) {
                for (AguiFramedEvent frame : frames) {
                    Sinks.EmitResult result = subscriber.tryEmitNext(frame);
                    if (result == Sinks.EmitResult.FAIL_OVERFLOW) {
                        // A tail that cannot keep up is failed instead of growing without bound;
                        // the client reconnects and falls back to the session log.
                        subscriber.tryEmitError(
                                new IllegalStateException(
                                        "AG-UI live tail overflow for run " + runId));
                        LOGGER.warn(
                                "AG-UI live tail overflow channel={} runId={}", channelKey, runId);
                    } else if (result.isFailure()
                            && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
                        LOGGER.debug(
                                "AG-UI broadcast dropped a frame channel={} runId={} result={}",
                                channelKey,
                                runId,
                                result);
                    }
                }
            }
        }
    }

    /**
     * Subscribes to the live tail of a running channel.
     *
     * <p>The snapshot is taken before {@link LiveRun#lastSeq()} is returned, so every event after
     * that sequence is guaranteed to arrive on {@link LiveRun#frames()}. Callers bridge the gap
     * with {@code recent} and the log, then continue with the returned stream.
     *
     * <p>The returned flux must be subscribed; frames published before it is subscribed are
     * buffered by its sink. The tail unregisters itself when the subscription terminates.
     *
     * @param channelKey the processor's {@code userId + agentId + threadId} channel key
     * @return the live tail, or {@code null} when no run is active in this process
     */
    public LiveRun live(String channelKey) {
        Channel channel = channels.get(channelKey);
        if (channel == null) {
            return null;
        }
        Sinks.Many<AguiFramedEvent> tail =
                Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(bufferSize));
        synchronized (channel.lock) {
            if (channel.closed) {
                return null;
            }
            channel.subscribers.add(tail);
            List<AguiFramedEvent> recent = channel.recentFrames();
            Flux<AguiFramedEvent> frames =
                    tail.asFlux()
                            .doFinally(
                                    signal -> {
                                        synchronized (channel.lock) {
                                            channel.subscribers.remove(tail);
                                        }
                                    });
            return new LiveRun(channel.runId, channel.lastSeq, channel.oldestSeq(), recent, frames);
        }
    }

    /**
     * Completes and removes the channel of a finished run.
     *
     * @param channelKey the processor's {@code userId + agentId + threadId} channel key
     * @param runId the AG-UI run id
     */
    public void close(String channelKey, String runId) {
        Channel channel = channels.get(channelKey);
        // runId is final, so this read needs no lock; complete() takes the channel lock itself.
        if (channel == null || !channel.runId.equals(runId)) {
            return;
        }
        complete(channel);
        channels.remove(channelKey, channel);
    }

    private static void complete(Channel channel) {
        List<Sinks.Many<AguiFramedEvent>> subscribers;
        synchronized (channel.lock) {
            if (channel.closed) {
                return;
            }
            channel.closed = true;
            subscribers = new ArrayList<>(channel.subscribers);
            channel.subscribers.clear();
        }
        // Complete outside the lock: a tail unregisters itself from doFinally.
        for (Sinks.Many<AguiFramedEvent> subscriber : subscribers) {
            subscriber.tryEmitComplete();
        }
    }

    /**
     * Live tail of one active run.
     *
     * @param runId the run id
     * @param lastSeq the highest event sequence already broadcast
     * @param oldestSeq the smallest source sequence retained in {@code recent}, or {@code -1}
     * @param recent frames already broadcast before this snapshot, oldest first
     * @param frames frames published after this tail was taken
     */
    public record LiveRun(
            String runId,
            long lastSeq,
            long oldestSeq,
            List<AguiFramedEvent> recent,
            Flux<AguiFramedEvent> frames) {
        public LiveRun {
            Objects.requireNonNull(runId, "runId cannot be null");
            recent = List.copyOf(Objects.requireNonNull(recent, "recent cannot be null"));
            Objects.requireNonNull(frames, "frames cannot be null");
        }

        public LiveRun(String runId, long lastSeq, Flux<AguiFramedEvent> frames) {
            this(runId, lastSeq, -1, List.of(), frames);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Channel {
        private final String runId;

        /**
         * Dedicated monitor: synchronizing on the channel itself would make the lock depend on how
         * the object is passed around, and the channel is never synchronized by its callers.
         */
        private final Object lock = new Object();

        private final List<Sinks.Many<AguiFramedEvent>> subscribers = new ArrayList<>();

        /**
         * Recent complete publish batches. A batch is whatever one {@code publish} call carried,
         * so trimming removes whole source events instead of leaving half an event.
         */
        private final Deque<List<AguiFramedEvent>> recentBatches = new ArrayDeque<>();

        private int recentFrameCount;
        private long lastSeq = -1;
        private boolean closed;

        private Channel(String runId) {
            this.runId = runId;
        }

        private void addRecent(List<AguiFramedEvent> frames, int limit) {
            recentBatches.addLast(List.copyOf(frames));
            recentFrameCount += frames.size();
            // Keep at least one batch: a single event can exceed the limit without being
            // truncated into an unusable prefix.
            while (recentFrameCount > limit && recentBatches.size() > 1) {
                List<AguiFramedEvent> removed = recentBatches.removeFirst();
                recentFrameCount -= removed.size();
            }
        }

        private List<AguiFramedEvent> recentFrames() {
            List<AguiFramedEvent> frames = new ArrayList<>(recentFrameCount);
            for (List<AguiFramedEvent> batch : recentBatches) {
                frames.addAll(batch);
            }
            return frames;
        }

        private long oldestSeq() {
            for (List<AguiFramedEvent> batch : recentBatches) {
                for (AguiFramedEvent frame : batch) {
                    if (frame.sourceSeq() >= 0) {
                        return frame.sourceSeq();
                    }
                }
            }
            return -1;
        }
    }
}
