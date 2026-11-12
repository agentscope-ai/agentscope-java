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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Post-commit projection delivery. Independent of tool publication and execution scheduling. */
public final class ManagedProjectionPublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedProjectionPublisher.class);
    private final ManagedProjectionOutbox outbox;
    private final ManagedMessageBus bus;
    private final ObjectMapper mapper;
    private final int concurrency;
    private final long coalesceMs;
    private final Semaphore capacity;
    private final Set<ManagedScope> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private AutoCloseable signals;
    private volatile boolean running;

    public ManagedProjectionPublisher(
            ManagedJournalStore journal, ManagedMessageBus bus, ObjectMapper mapper) {
        this(journal, bus, mapper, 16);
    }

    public ManagedProjectionPublisher(
            ManagedJournalStore journal,
            ManagedMessageBus bus,
            ObjectMapper mapper,
            int concurrency) {
        this(journal, bus, mapper, concurrency, 200);
    }

    public ManagedProjectionPublisher(
            ManagedJournalStore journal,
            ManagedMessageBus bus,
            ObjectMapper mapper,
            int concurrency,
            long coalesceMs) {
        if (coalesceMs < 0 || coalesceMs > 1000)
            throw new IllegalArgumentException("Projection coalescing must be 0..1000ms");
        this.coalesceMs = coalesceMs;
        if (concurrency < 1 || concurrency > 128)
            throw new IllegalArgumentException("Projection publisher concurrency must be 1..128");
        this.outbox = journal.projections().outbox();
        this.bus = bus;
        this.mapper = mapper;
        this.concurrency = concurrency;
        capacity = new Semaphore(concurrency);
        workers =
                Executors.newFixedThreadPool(
                        concurrency,
                        r -> {
                            var t = new Thread(r, "projection-delivery-send");
                            t.setDaemon(true);
                            return t;
                        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        signals = ManagedProjectionSignals.subscribe(outbox.source(), this::wakeup);
        scheduler.scheduleWithFixedDelay(this::drain, 0, 30, TimeUnit.SECONDS);
    }

    private void wakeup() {
        schedule(coalesceMs);
    }

    private void drain() {
        schedule(0);
    }

    private void schedule(long delayMs) {
        if (!running || !scheduled.compareAndSet(false, true)) return;
        try {
            scheduler.schedule(
                    () -> {
                        scheduled.set(false);
                        try {
                            dispatch();
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Projection outbox remains pending: {}",
                                    e.getClass().getSimpleName());
                        }
                    },
                    delayMs,
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException stopped) {
            scheduled.set(false);
        }
    }

    private void dispatch() {
        int free = capacity.availablePermits();
        if (!running || free == 0) return;
        for (var scope : outbox.pending(Math.min(512, concurrency * 32))) {
            if (!active.add(scope)) continue;
            if (!capacity.tryAcquire()) {
                active.remove(scope);
                break;
            }
            try {
                workers.execute(
                        () -> {
                            boolean delivered = false;
                            try {
                                delivered = publish(scope);
                            } finally {
                                active.remove(scope);
                                capacity.release();
                                if (running) {
                                    if (delivered) drain();
                                    else scheduler.schedule(this::wakeup, 1, TimeUnit.SECONDS);
                                }
                            }
                        });
            } catch (RuntimeException stopped) {
                active.remove(scope);
                capacity.release();
            }
        }
    }

    /** Explicit maintenance/test pump, never part of normal scheduling. */
    public void scan() {
        for (var scope : outbox.pending(128)) publish(scope);
    }

    private boolean publish(ManagedScope scope) {
        ManagedProjectionOutbox.Claim claim = null;
        try {
            claim = outbox.claim(scope);
            if (claim == null) return false;
            var notice = outbox.message(claim);
            byte[] bytes = mapper.writeValueAsBytes(notice);
            if (bytes.length > 1024 * 1024)
                throw new IllegalArgumentException("Projection delivery exceeds message budget");
            bus.publish(
                    Channel.SESSION_PROJECTIONS,
                    new Message(
                            "projection_" + notice.commitVersion(), scope.messageGroup(), bytes));
            outbox.completed(claim);
            return true;
        } catch (Exception error) {
            if (claim != null) {
                try {
                    outbox.retry(claim);
                } catch (RuntimeException databaseUnavailable) {
                    // The existing lease permits recovery; do not spin or lose the committed row.
                }
            }
            log.warn("Projection delivery remains pending: {}", error.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (signals != null)
            try {
                signals.close();
            } catch (Exception ignored) {
            }
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
