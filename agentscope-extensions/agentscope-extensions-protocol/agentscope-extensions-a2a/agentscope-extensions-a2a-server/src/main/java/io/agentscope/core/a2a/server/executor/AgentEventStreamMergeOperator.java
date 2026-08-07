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
package io.agentscope.core.a2a.server.executor;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

final class AgentEventStreamMergeOperator {

    private AgentEventStreamMergeOperator() {}

    static Flux<AgentEvent> merge(
            Flux<AgentEvent> source,
            int maxSize,
            Duration interval,
            Consumer<MergeWindow> statsConsumer) {
        int effectiveMaxSize = Math.max(1, maxSize);
        Duration effectiveInterval =
                interval == null || interval.isNegative() || interval.isZero()
                        ? Duration.ofMillis(1)
                        : interval;
        Consumer<MergeWindow> effectiveStats =
                statsConsumer == null ? ignored -> {} : statsConsumer;
        return Flux.create(
                sink -> {
                    MergeState state =
                            new MergeState(
                                    sink, effectiveMaxSize, effectiveInterval, effectiveStats);
                    Disposable upstream =
                            source.subscribe(state::onNext, state::onError, state::onComplete);
                    sink.onDispose(
                            () -> {
                                state.dispose();
                                upstream.dispose();
                            });
                });
    }

    record MergeWindow(int inputEvents, int outputEvents, boolean hitMaxSize) {

        int reducedEvents() {
            return inputEvents - outputEvents;
        }
    }

    private static final class MergeState {

        private final FluxSink<AgentEvent> sink;
        private final int maxSize;
        private final Duration interval;
        private final Consumer<MergeWindow> statsConsumer;
        private final List<AgentEvent> bufferedEvents = new ArrayList<>();
        private Disposable timer;
        private boolean terminated;

        private MergeState(
                FluxSink<AgentEvent> sink,
                int maxSize,
                Duration interval,
                Consumer<MergeWindow> statsConsumer) {
            this.sink = sink;
            this.maxSize = maxSize;
            this.interval = interval;
            this.statsConsumer = statsConsumer;
        }

        void onNext(AgentEvent event) {
            synchronized (this) {
                if (terminated) {
                    return;
                }
                if (isMergeable(event)) {
                    bufferedEvents.add(event);
                    if (bufferedEvents.size() == 1) {
                        scheduleFlush();
                    }
                    if (bufferedEvents.size() >= maxSize) {
                        emitLocked(flushLocked(true));
                    }
                } else {
                    emitLocked(flushLocked(false));
                    sink.next(event);
                }
            }
        }

        void onError(Throwable error) {
            synchronized (this) {
                if (terminated) {
                    return;
                }
                terminated = true;
                emitLocked(flushLocked(false));
                sink.error(error);
            }
        }

        void onComplete() {
            synchronized (this) {
                if (terminated) {
                    return;
                }
                terminated = true;
                emitLocked(flushLocked(false));
                sink.complete();
            }
        }

        synchronized void dispose() {
            terminated = true;
            disposeTimerLocked();
        }

        private void scheduleFlush() {
            disposeTimerLocked();
            timer =
                    Mono.delay(interval)
                            .subscribe(
                                    ignored -> {
                                        synchronized (this) {
                                            if (!terminated) {
                                                emitLocked(flushLocked(false));
                                            }
                                        }
                                    });
        }

        private Flush flushLocked(boolean hitMaxSize) {
            if (bufferedEvents.isEmpty()) {
                return null;
            }
            disposeTimerLocked();
            List<AgentEvent> snapshot = List.copyOf(bufferedEvents);
            bufferedEvents.clear();
            return new Flush(snapshot, hitMaxSize);
        }

        private void disposeTimerLocked() {
            if (timer != null) {
                timer.dispose();
                timer = null;
            }
        }

        private void emitLocked(Flush flush) {
            if (flush == null) {
                return;
            }
            List<AgentEvent> merged = coalesce(flush.events());
            statsConsumer.accept(
                    new MergeWindow(flush.events().size(), merged.size(), flush.hitMaxSize()));
            merged.forEach(sink::next);
        }
    }

    private static List<AgentEvent> coalesce(List<AgentEvent> events) {
        List<AgentEvent> result = new ArrayList<>();
        List<AgentEvent> run = new ArrayList<>();
        for (AgentEvent event : events) {
            if (!run.isEmpty() && !sameRun(run.get(0), event)) {
                result.add(mergeRun(run));
                run.clear();
            }
            run.add(event);
        }
        if (!run.isEmpty()) {
            result.add(mergeRun(run));
        }
        return result;
    }

    private static AgentEvent mergeRun(List<AgentEvent> run) {
        if (run.size() == 1) {
            return run.get(0);
        }
        AgentEvent head = run.get(0);
        StringBuilder delta = new StringBuilder();
        for (AgentEvent event : run) {
            delta.append(delta(event));
        }
        AgentEvent merged;
        if (head instanceof TextBlockDeltaEvent text) {
            merged =
                    new TextBlockDeltaEvent(
                            head.getId(),
                            head.getCreatedAt(),
                            text.getReplyId(),
                            text.getBlockId(),
                            delta.toString());
        } else {
            ThinkingBlockDeltaEvent thinking = (ThinkingBlockDeltaEvent) head;
            merged =
                    new ThinkingBlockDeltaEvent(
                            head.getId(),
                            head.getCreatedAt(),
                            thinking.getReplyId(),
                            thinking.getBlockId(),
                            delta.toString());
        }
        merged.withSource(head.getSource());
        merged.withMetadata(head.getMetadata());
        return merged;
    }

    private static boolean isMergeable(AgentEvent event) {
        return event instanceof TextBlockDeltaEvent || event instanceof ThinkingBlockDeltaEvent;
    }

    private static boolean sameRun(AgentEvent left, AgentEvent right) {
        return left.getType() == right.getType()
                && Objects.equals(replyId(left), replyId(right))
                && Objects.equals(blockId(left), blockId(right))
                && Objects.equals(left.getSource(), right.getSource());
    }

    private static String replyId(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getReplyId();
        }
        return ((ThinkingBlockDeltaEvent) event).getReplyId();
    }

    private static String blockId(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getBlockId();
        }
        return ((ThinkingBlockDeltaEvent) event).getBlockId();
    }

    private static String delta(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getDelta();
        }
        return ((ThinkingBlockDeltaEvent) event).getDelta();
    }

    private record Flush(List<AgentEvent> events, boolean hitMaxSize) {}
}
