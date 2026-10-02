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
package io.agentscope.builder.web.coord;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Acquires and heartbeats the in-flight turn lease for a managed session. Does not sticky-route
 * sessions — only mutexes concurrent turn execution across Brain replicas. Heartbeats also consume
 * cross-replica interrupt tickets from {@link CoordinationStore}.
 */
@Service
public class TurnLeaseService {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseService.class);

    private final CoordinationStore coordinationStore;
    private final BuilderInstanceId instanceId;
    private final Duration ttl;
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "turn-lease-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });

    @Autowired
    public TurnLeaseService(
            CoordinationStore coordinationStore,
            BuilderInstanceId instanceId,
            @Value("${builder.coord.turn-lease-ttl-seconds:90}") long ttlSeconds) {
        this(coordinationStore, instanceId, Duration.ofSeconds(Math.max(15, ttlSeconds)));
    }

    TurnLeaseService(
            CoordinationStore coordinationStore, BuilderInstanceId instanceId, Duration ttl) {
        this.coordinationStore = coordinationStore;
        this.instanceId = instanceId;
        this.ttl = ttl;
    }

    /**
     * Tries to acquire the turn lease. Throws 409 when another instance holds a live lease.
     *
     * @param onRemoteInterrupt invoked when another plane requests interrupt via the coordination
     *     store (may be called from the heartbeat thread)
     * @return a handle that must be {@link TurnLease#close()}-d (releases lease + stops heartbeat)
     */
    public TurnLease acquireOrConflict(
            String sessionId, String ownerId, Runnable onRemoteInterrupt) {
        return acquireOrConflictFenced(
                sessionId,
                ownerId,
                ignored -> {
                    if (onRemoteInterrupt != null) {
                        onRemoteInterrupt.run();
                    }
                });
    }

    /** Acquires a turn lease whose interrupt callback receives the durable request fence. */
    public TurnLease acquireOrConflictFenced(
            String sessionId,
            String ownerId,
            Consumer<CoordinationStore.TurnInterruptRequest> onRemoteInterrupt) {
        // A JVM instance can receive the next message while the previous turn is still
        // publishing its final events. A process-wide owner id would make that second
        // turn look like a lease refresh and allow both turns to overlap. Fence every
        // turn with its own owner id so teardown from an older turn can never delete or
        // heartbeat the lease of a newer one.
        String leaseOwnerId = instanceId.get() + "/" + UUID.randomUUID();
        Optional<CoordinationStore.LeaseHandle> acquired =
                coordinationStore.tryAcquireTurnLease(sessionId, ownerId, leaseOwnerId, ttl);
        if (acquired.isEmpty()) {
            Optional<CoordinationStore.LeaseHandle> holder =
                    coordinationStore.getTurnLease(sessionId);
            String owner =
                    holder.map(CoordinationStore.LeaseHandle::instanceId)
                            .orElse("another-instance");
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Session turn already in progress on instance " + owner);
        }
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>> interruptRef =
                new AtomicReference<>(
                        onRemoteInterrupt != null ? onRemoteInterrupt : ignored -> {});
        AtomicLong validUntil = new AtomicLong(acquired.get().expiresAt());
        ScheduledFuture<?> future =
                heartbeatScheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                boolean stillOwner =
                                        coordinationStore.heartbeatTurnLease(
                                                sessionId, leaseOwnerId, ttl);
                                if (!stillOwner) {
                                    // A successful callback closes this TurnLease and cancels the
                                    // scheduler. If admission bookkeeping is not installed yet,
                                    // retry on the next tick instead of losing the lease-loss
                                    // signal.
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(
                                                new CoordinationStore.TurnInterruptRequest(
                                                        "turn_lease_lost", null));
                                    }
                                    return;
                                }
                                validUntil.set(System.currentTimeMillis() + ttl.toMillis());
                                Optional<CoordinationStore.TurnInterruptRequest> request =
                                        coordinationStore.consumeTurnInterruptRequest(sessionId);
                                if (request.isPresent()) {
                                    log.info(
                                            "Consumed remote interrupt for session {}: {}",
                                            sessionId,
                                            request.get().reason());
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(request.get());
                                    }
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Turn lease heartbeat failed for {}: {}",
                                        sessionId,
                                        ex.getMessage());
                                if (System.currentTimeMillis() >= validUntil.get()) {
                                    Consumer<CoordinationStore.TurnInterruptRequest> cb =
                                            interruptRef.get();
                                    if (cb != null) {
                                        cb.accept(
                                                new CoordinationStore.TurnInterruptRequest(
                                                        "turn_lease_lost", null));
                                    }
                                }
                            }
                        },
                        ttl.toMillis() / 3,
                        ttl.toMillis() / 3,
                        TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return new TurnLease(sessionId, leaseOwnerId, futureRef, interruptRef);
    }

    /** @deprecated use {@link #acquireOrConflict(String, String, Runnable)} */
    public TurnLease acquireOrConflict(String sessionId, String ownerId) {
        return acquireOrConflict(sessionId, ownerId, () -> {});
    }

    public Optional<CoordinationStore.LeaseHandle> currentLease(String sessionId) {
        return coordinationStore.getTurnLease(sessionId);
    }

    public String localInstanceId() {
        return instanceId.get();
    }

    public final class TurnLease implements AutoCloseable {
        private final String sessionId;
        private final String leaseOwnerId;
        private final AtomicReference<ScheduledFuture<?>> heartbeat;
        private final AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>>
                onRemoteInterrupt;

        private TurnLease(
                String sessionId,
                String leaseOwnerId,
                AtomicReference<ScheduledFuture<?>> heartbeat,
                AtomicReference<Consumer<CoordinationStore.TurnInterruptRequest>>
                        onRemoteInterrupt) {
            this.sessionId = sessionId;
            this.leaseOwnerId = leaseOwnerId;
            this.heartbeat = heartbeat;
            this.onRemoteInterrupt = onRemoteInterrupt;
        }

        public String sessionId() {
            return sessionId;
        }

        public String instanceId() {
            return TurnLeaseService.this.instanceId.get();
        }

        /** Unique shared-store owner token for this physical turn. */
        public String coordinationId() {
            return leaseOwnerId;
        }

        @Override
        public void close() {
            onRemoteInterrupt.set(null);
            ScheduledFuture<?> f = heartbeat.getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
            coordinationStore.releaseTurnLease(sessionId, leaseOwnerId);
        }
    }
}
