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
package io.agentcore.managed.server.runtime.managed;

import io.micrometer.context.ContextRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

/** Sampled, bounded diagnostic intervals; never participates in execution or recovery decisions. */
public final class ManagedExecutionTrace implements AutoCloseable {
    static final String KEY = "managed.execution.observation";
    private static final ThreadLocal<ManagedExecutionTrace> CURRENT = new ThreadLocal<>();
    private static volatile int sampleEvery;
    private static boolean installed;
    private final ManagedExecutionTrace previous;
    private final ManagedScope scope;
    private final String turn;
    private final String id = UUID.randomUUID().toString();
    private final long origin = System.nanoTime();
    private final long epochMs = System.currentTimeMillis();
    private final List<List<Object>> intervals = new ArrayList<>();
    private int dropped;
    private boolean closed;

    static synchronized void configure(int every) {
        if (every < 0) throw new IllegalArgumentException("Negative observation sample interval");
        sampleEvery = every;
        if (every == 0 || installed) return;
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(KEY, CURRENT::get, CURRENT::set, CURRENT::remove);
        Hooks.enableAutomaticContextPropagation();
        Schedulers.addExecutorServiceDecorator(
                KEY, (scheduler, executor) -> new ManagedObservedScheduler(executor));
        installed = true;
    }

    static ManagedExecutionTrace begin(ManagedScope scope, String turn) {
        if (sampleEvery == 0 || Math.floorMod(turn.hashCode(), sampleEvery) != 0) return null;
        return new ManagedExecutionTrace(scope, turn);
    }

    ManagedExecutionTrace(ManagedScope scope, String turn) {
        this.previous = CURRENT.get();
        this.scope = scope;
        this.turn = turn;
        CURRENT.set(this);
    }

    static <T> Mono<T> observe(ManagedScope scope, String turn, Supplier<Mono<T>> work) {
        return Mono.defer(
                () -> {
                    var trace = begin(scope, turn);
                    if (trace == null) return Mono.defer(work);
                    trace.restoreThread();
                    return Mono.defer(work)
                            .doFinally(ignored -> trace.finish())
                            .contextWrite(context -> context.put(KEY, trace));
                });
    }

    private void restoreThread() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    static ManagedExecutionTrace current() {
        return CURRENT.get();
    }

    static Context context(Context context) {
        var trace = current();
        return trace == null ? context : context.put(KEY, trace);
    }

    static Span span(String kind) {
        return new Span(current(), kind, System.nanoTime());
    }

    static <T> Mono<T> mono(String kind, Supplier<Mono<T>> work) {
        return Mono.using(() -> span(kind), ignored -> Mono.defer(work), Span::close, true);
    }

    synchronized void record(String kind, long start, long end) {
        if (closed) return;
        if (intervals.size() >= 4096) {
            dropped++;
            return;
        }
        intervals.add(
                List.of(
                        kind,
                        Math.max(0, (start - origin) / 1000),
                        Math.max(0, (end - start) / 1000)));
    }

    synchronized List<List<Object>> intervals() {
        return List.copyOf(intervals);
    }

    public void close() {
        restoreThread();
        finish();
    }

    private void finish() {
        List<List<Object>> snapshot;
        long elapsed = System.nanoTime() - origin;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = List.copyOf(intervals);
        }
        // Chunk output so log collectors cannot silently truncate a long sampled execution.
        for (int start = 0; start < snapshot.size(); start += 128)
            ManagedRuntimeLog.info(
                    LoggerFactory.getLogger(ManagedExecutionTrace.class),
                    "harness_runtime.observation",
                    scope,
                    turn,
                    "trace_id",
                    id,
                    "origin_ms",
                    epochMs,
                    "elapsed_us",
                    elapsed / 1000,
                    "part",
                    start / 128,
                    "parts",
                    (snapshot.size() + 127) / 128,
                    "dropped",
                    dropped,
                    "sample_every",
                    sampleEvery,
                    "intervals",
                    snapshot.subList(start, Math.min(snapshot.size(), start + 128)));
    }

    static final class Span implements AutoCloseable {
        private final ManagedExecutionTrace trace;
        private final String kind;
        private final long start;
        private final AtomicBoolean done = new AtomicBoolean();

        Span(ManagedExecutionTrace trace, String kind, long start) {
            this.trace = trace;
            this.kind = kind;
            this.start = start;
        }

        public void close() {
            if (trace != null && done.compareAndSet(false, true))
                trace.record(kind, start, System.nanoTime());
        }
    }
}
