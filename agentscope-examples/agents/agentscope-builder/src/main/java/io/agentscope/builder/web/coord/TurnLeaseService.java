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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Acquires and heartbeats the in-flight turn lease for a managed session. Does not sticky-route
 * sessions — only mutexes concurrent turn execution across Brain replicas.
 */
@Service
public class TurnLeaseService {

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
     * @return a handle that must be {@link TurnLease#close()}-d (releases lease + stops heartbeat)
     */
    public TurnLease acquireOrConflict(String sessionId, String ownerId) {
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
        ScheduledFuture<?> future =
                heartbeatScheduler.scheduleAtFixedRate(
                        () ->
                                coordinationStore.heartbeatTurnLease(
                                        sessionId, instanceId.get(), ttl),
                        ttl.toMillis() / 3,
                        ttl.toMillis() / 3,
                        TimeUnit.MILLISECONDS);
        futureRef.set(future);
        return new TurnLease(sessionId, futureRef);
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

        private TurnLease(String sessionId, AtomicReference<ScheduledFuture<?>> heartbeat) {
            this.sessionId = sessionId;
            this.heartbeat = heartbeat;
        }

        public String sessionId() {
            return sessionId;
        }

        public String instanceId() {
            return TurnLeaseService.this.instanceId.get();
        }

        @Override
        public void close() {
            ScheduledFuture<?> f = heartbeat.getAndSet(null);
            if (f != null) {
                f.cancel(false);
            }
            coordinationStore.releaseTurnLease(sessionId, TurnLeaseService.this.instanceId.get());
        }
    }
}
