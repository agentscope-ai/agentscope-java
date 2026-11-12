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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receipts cover only bounded query writes; ACK follows target visibility, never an in-memory enqueue. */
public final class ManagedProjectionConsumer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedProjectionConsumer.class);
    private final ManagedSessionProjections projections;
    private final ManagedMessageBus bus;
    private final ObjectMapper mapper;
    private final int concurrency;
    private final ManagedReceiveCapacity receiveCapacity;
    private final Set<ManagedScope> active = ConcurrentHashMap.newKeySet();
    private final LongAdder retries = new LongAdder();
    private volatile boolean closed;
    private ManagedMessageBus.Subscription subscription;

    public ManagedProjectionConsumer(
            ManagedSessionProjections projections,
            ManagedMessageBus bus,
            ObjectMapper mapper,
            int concurrency) {
        this(projections, bus, mapper, concurrency, null);
    }

    public ManagedProjectionConsumer(
            ManagedSessionProjections projections,
            ManagedMessageBus bus,
            ObjectMapper mapper,
            int concurrency,
            MeterRegistry metrics) {
        if (concurrency < 1 || concurrency > 128)
            throw new IllegalArgumentException("Projection concurrency must be 1..128");
        this.projections = projections;
        this.bus = bus;
        this.mapper = mapper;
        this.concurrency = concurrency;
        receiveCapacity = new ManagedReceiveCapacity(new Semaphore(concurrency), concurrency);
        if (metrics != null) {
            metrics.gauge("managed.agents.projection.active", active, Set::size);
            metrics.gauge("managed.agents.projection.retries", retries, LongAdder::sum);
        }
    }

    public synchronized void start() {
        if (subscription != null || closed) return;
        subscription =
                bus.subscribe(
                        Channel.SESSION_PROJECTIONS,
                        concurrency,
                        new ManagedMessageBus.Listener() {
                            @Override
                            public ManagedReceiveCapacity receiveCapacity(Channel channel) {
                                return receiveCapacity;
                            }

                            @Override
                            public void consume(ManagedPushDelivery delivery) {
                                ManagedProjectionNotice notice;
                                try {
                                    if (delivery.message().body().length > 1024 * 1024)
                                        throw new IllegalArgumentException(
                                                "Projection message exceeds budget");
                                    notice =
                                            mapper.readValue(
                                                    delivery.message().body(),
                                                    ManagedProjectionNotice.class);
                                } catch (Exception invalid) {
                                    throw new IllegalArgumentException(
                                            "Invalid projection delivery", invalid);
                                }
                                if (!notice.scope()
                                        .messageGroup()
                                        .equals(delivery.message().messageGroup()))
                                    throw new IllegalArgumentException("Projection scope differs");
                                offer(notice);
                                // Returning from this bounded write ACKs the original receipt.
                                // Exceptions release it for retry.
                            }
                        },
                        (delivery, error) ->
                                log.warn(
                                        "Projection delivery retry: message={}, attempt={},"
                                                + " cause={}",
                                        delivery.brokerMessageId(),
                                        delivery.attempt(),
                                        error.getClass().getSimpleName()));
    }

    void offer(ManagedProjectionNotice notice) {
        if (closed || !active.add(notice.scope()))
            throw new ManagedDeliverySuspended(Duration.ofMillis(250));
        try {
            long started = System.nanoTime();
            try {
                projections.deliver(notice);
            } finally {
                Metrics.timer("managed.agents.projection.delivery")
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        } catch (RuntimeException error) {
            retries.increment();
            throw error;
        } finally {
            active.remove(notice.scope());
        }
    }

    int pendingCount() {
        return active.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (subscription != null) subscription.close();
    }
}
