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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TurnLeaseService(
            CoordinationStore coordinationStore,
            BuilderInstanceId instanceId,
            @Value("${builder.coord.turn-lease-ttl-seconds:90}") long ttlSeconds) {
        this.coordinationStore = coordinationStore;
        this.instanceId = instanceId;
        this.ttl = Duration.ofSeconds(Math.max(15, ttlSeconds));
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
        Optional<CoordinationStore.LeaseHandle> acquired =
                coordinationStore.tryAcquireTurnLease(sessionId, ownerId, instanceId.get(), ttl);
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
        AtomicReference<Runnable> interruptRef =
                new AtomicReference<>(onRemoteInterrupt != null ? onRemoteInterrupt : () -> {});
        ScheduledFuture<?> future =
                heartbeatScheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                coordinationStore.heartbeatTurnLease(
                                        sessionId, instanceId.get(), ttl);
                                Optional<String> reason =
                                        coordinationStore.consumeTurnInterrupt(sessionId);
                                if (reason.isPresent()) {
                                    log.info(
                                            "Consumed remote interrupt for session {}: {}",
                                            sessionId,
                                            reason.get());
                                    Runnable cb = interruptRef.get();
                                    if (cb != null) {
                                        cb.run();
                                    }
                                }
                            } catch (Exception ex) {
                                log.warn(
                                        "Turn lease heartbeat failed for {}: {}",
                                        sessionId,
                                        ex.getMessage());
                            }
                        },
                        ttl.toMillis() / 3,
                        ttl.toMillis() / 3,
                        TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return new TurnLease(sessionId, futureRef, interruptRef);
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
        private final AtomicReference<ScheduledFuture<?>> heartbeat;
        private final AtomicReference<Runnable> onRemoteInterrupt;

        private TurnLease(
                String sessionId,
                AtomicReference<ScheduledFuture<?>> heartbeat,
                AtomicReference<Runnable> onRemoteInterrupt) {
            this.sessionId = sessionId;
            this.heartbeat = heartbeat;
            this.onRemoteInterrupt = onRemoteInterrupt;
        }

        public String sessionId() {
            return sessionId;
        }

        public String instanceId() {
            return TurnLeaseService.this.instanceId.get();
        }

        @Override
        public void close() {
            onRemoteInterrupt.set(null);
            ScheduledFuture<?> f = heartbeat.getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
            coordinationStore.releaseTurnLease(sessionId, TurnLeaseService.this.instanceId.get());
        }
    }
}
