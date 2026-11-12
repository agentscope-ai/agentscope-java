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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Per-process request/byte backpressure. Projection can add a smaller independent read budget. */
public final class BoundedRuntimeObjectStore implements RuntimeObjectStore {
    private final RuntimeObjectStore delegate;
    private final int maxRequests;
    private final long maxBytes;
    private final long waitNanos;
    private final MeterRegistry metrics;
    private final String lane;
    private final boolean ownsDelegate;
    private int active;
    private int waiting;
    private long bytes;

    public BoundedRuntimeObjectStore(
            RuntimeObjectStore delegate,
            int maxRequests,
            long maxBytes,
            Duration wait,
            MeterRegistry metrics,
            String lane,
            boolean ownsDelegate) {
        if (maxRequests < 1
                || maxRequests > 1024
                || maxBytes < MAX_BYTES
                || maxBytes > 128L * MAX_BYTES
                || wait == null
                || wait.isNegative()
                || wait.isZero()
                || wait.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("Invalid object-store budget");
        this.delegate = delegate;
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
        this.waitNanos = wait.toNanos();
        this.metrics = metrics;
        this.lane = lane;
        this.ownsDelegate = ownsDelegate;
        metrics.gauge(
                "managed.agents.object.store.active",
                List.of(Tag.of("lane", lane)),
                this,
                BoundedRuntimeObjectStore::active);
        metrics.gauge(
                "managed.agents.object.store.waiting",
                List.of(Tag.of("lane", lane)),
                this,
                BoundedRuntimeObjectStore::waiting);
        metrics.gauge(
                "managed.agents.object.store.inflight.bytes",
                List.of(Tag.of("lane", lane)),
                this,
                BoundedRuntimeObjectStore::inflightBytes);
    }

    public synchronized int active() {
        return active;
    }

    public synchronized int waiting() {
        return waiting;
    }

    public synchronized long inflightBytes() {
        return bytes;
    }

    public String backendId() {
        return delegate.backendId();
    }

    private void acquire(long size) {
        long deadline = System.nanoTime() + waitNanos;
        synchronized (this) {
            waiting++;
            try {
                while (active >= maxRequests || bytes + size > maxBytes) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        metrics.counter(
                                        "managed.agents.object.store.capacity.timeouts",
                                        "lane",
                                        lane)
                                .increment();
                        throw new ManagedTransportException(
                                "Object-store request or byte budget exhausted");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remaining);
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        throw new ManagedTransportException(
                                "Object operation cancelled", cancelled);
                    }
                }
                active++;
                bytes += size;
            } finally {
                waiting--;
            }
        }
    }

    private synchronized void release(long size) {
        active--;
        bytes -= size;
        notifyAll();
    }

    private <T> T invoke(String operation, String kind, long size, Supplier<T> action) {
        if (size < 1 || size > MAX_BYTES) throw new IllegalArgumentException("Invalid object size");
        var wait = Timer.start(metrics);
        try (var observation = ManagedExecutionTrace.span("object_capacity_" + lane)) {
            acquire(size);
        }
        wait.stop(metrics.timer("managed.agents.object.store.acquire", "lane", lane));
        var timer = Timer.start(metrics);
        String outcome = "success";
        long transferred = 0;
        try (var observation = ManagedExecutionTrace.span("object_" + operation + "_" + lane)) {
            T result = action.get();
            transferred =
                    result instanceof byte[] content
                            ? content.length
                            : result instanceof Reference ref ? ref.size() : 0;
            return result;
        } catch (RuntimeException failure) {
            outcome = "error";
            throw failure;
        } finally {
            timer.stop(
                    metrics.timer(
                            "managed.agents.object.store.requests",
                            "lane",
                            lane,
                            "operation",
                            operation,
                            "kind",
                            kind,
                            "outcome",
                            outcome));
            if ("success".equals(outcome) && !"head".equals(operation))
                metrics.counter(
                                "managed.agents.object.store.bytes",
                                "lane",
                                lane,
                                "operation",
                                operation)
                        .increment(transferred);
            release(size);
        }
    }

    public Reference put(String scope, String kind, String key, byte[] content) {
        return invoke("put", kind, content.length, () -> delegate.put(scope, kind, key, content));
    }

    public byte[] read(String scope, String kind, String key) {
        return invoke("get", kind, MAX_BYTES, () -> delegate.read(scope, kind, key));
    }

    @Override
    public byte[] read(Reference reference) {
        return invoke("get", reference.kind(), reference.size(), () -> delegate.read(reference));
    }

    public boolean exists(String scope, String kind, String key) {
        return invoke("head", kind, 1, () -> delegate.exists(scope, kind, key));
    }

    public void close() {
        if (ownsDelegate) delegate.close();
    }
}
