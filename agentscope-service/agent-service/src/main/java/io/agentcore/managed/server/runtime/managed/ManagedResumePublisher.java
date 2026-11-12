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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Retries stable continuation identities; an ambiguous send never discards the durable intent. */
public final class ManagedResumePublisher implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedResumePublisher.class);
    private ScheduledExecutorService dispatcher;
    private ExecutorService listener;
    private ThreadPoolExecutor publications;
    private volatile Connection listeningConnection;
    private volatile boolean running;
    private AutoCloseable localSignals;
    private final Map<String, ScheduledFuture<?>> deferred = new HashMap<>();
    private final Map<String, Boolean> dirty = new HashMap<>();
    private final Duration repairInterval;
    private final ManagedSuspensions store;
    private final ManagedMessageBus bus;
    private final ManagedLeaseStore leases;
    private final ObjectMapper mapper;
    private final ManagedResumeRecovery recovery;
    private final ManagedInitialReadyRecovery initialRecovery;
    private long lastClaimRepairNanos;

    public ManagedResumePublisher(
            ManagedSuspensions store,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ObjectMapper mapper) {
        this(store, bus, leases, mapper, Duration.ofSeconds(30));
    }

    public ManagedResumePublisher(
            ManagedSuspensions store,
            ManagedMessageBus bus,
            ManagedLeaseStore leases,
            ObjectMapper mapper,
            Duration repairInterval) {
        ManagedMessageBus.positiveMillis(repairInterval);
        this.repairInterval = repairInterval;
        this.store = store;
        this.bus = bus;
        this.leases = leases;
        this.mapper = mapper;
        this.recovery = new ManagedResumeRecovery(store, leases, repairInterval);
        this.initialRecovery =
                new ManagedInitialReadyRecovery(store.dataSource(), mapper, leases, repairInterval);
        this.lastClaimRepairNanos = System.nanoTime() - repairInterval.toNanos();
    }

    public int retry(int limit) {
        return publish(store.pending(limit));
    }

    private int publish(List<ManagedSuspensions.Resume> pending) {
        int sent = 0;
        for (var resume : pending) {
            var lease =
                    leases.acquire(
                            resume.scope().key("resume-publication"), Duration.ofSeconds(60));
            if (lease.isEmpty()) continue;
            try {
                // Another publisher may have sent this row while we waited for ownership.
                var publication = store.publication(resume);
                if (publication.isEmpty()) continue;
                long version = publication.getAsLong();
                byte[] body = mapper.writeValueAsBytes(resume);
                if (!leases.owns(lease.get()))
                    throw new ManagedTransportException("Resume publication lease lost");
                var receipt =
                        bus.publish(
                                Channel.TURN_RESUMES,
                                new Message(
                                        resume.resumeId(), resume.scope().messageGroup(), body));
                if (!leases.owns(lease.get()))
                    throw new ManagedTransportException("Resume publication lease lost after send");
                if (!store.published(resume, receipt.brokerMessageId(), version)) continue;
                ManagedRuntimeLog.info(
                        log,
                        "harness_runtime.resume.published",
                        resume.scope(),
                        resume.turnId(),
                        "resume_id",
                        resume.resumeId(),
                        "suspension_id",
                        resume.suspensionId(),
                        "suspension_epoch",
                        resume.suspensionEpoch(),
                        "broker_message_id",
                        receipt.brokerMessageId());
                sent++;
            } catch (ManagedTransportException unavailable) {
                // A subsequent outbox scan republishes the same identity after restart/failure.
            } catch (JsonProcessingException invalid) {
                throw new IllegalArgumentException("Cannot encode resume command", invalid);
            } finally {
                leases.release(lease.get());
            }
        }
        return sent;
    }

    /** A hint only schedules outbox publication; it never executes a Turn. */
    public synchronized void wakeup(String scopeKey) {
        if (!running) return;
        int separator = scopeKey.lastIndexOf(':');
        if (separator >= 0) {
            String key = scopeKey.substring(0, separator);
            long delay =
                    Math.max(
                            1,
                            Long.parseLong(scopeKey.substring(separator + 1))
                                    - System.currentTimeMillis());
            var previous = deferred.remove(key);
            if (previous != null) previous.cancel(false);
            if (deferred.size() < 8192)
                deferred.put(
                        key,
                        dispatcher.schedule(
                                () -> {
                                    synchronized (this) {
                                        deferred.remove(key);
                                    }
                                    wakeup(key);
                                },
                                delay,
                                TimeUnit.MILLISECONDS));
            return; // Indexed low-frequency repair covers lost notifications and bounded queue
            // overflow.
        }
        if (dirty.put(scopeKey, true) != null) return;
        try {
            publications.execute(() -> drain(scopeKey));
        } catch (RejectedExecutionException busy) {
            dirty.remove(scopeKey); // Low-frequency repair owns missed hints.
        }
    }

    private void drain(String scopeKey) {
        try {
            while (running) {
                synchronized (this) {
                    dirty.put(scopeKey, false);
                }
                while (running && publish(store.pending(64, scopeKey)) > 0) {}
                synchronized (this) {
                    if (!Boolean.TRUE.equals(dirty.get(scopeKey))) {
                        dirty.remove(scopeKey);
                        return;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Resume publication remains pending: {}", unavailable.toString());
            synchronized (this) {
                dirty.remove(scopeKey);
            }
        }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        publications =
                new ThreadPoolExecutor(
                        8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8192));
        var timer = new ScheduledThreadPoolExecutor(1);
        timer.setRemoveOnCancelPolicy(true);
        dispatcher = timer;
        localSignals = ManagedResumeSignals.subscribe(store.dataSource(), this::wakeup);
        dispatcher.scheduleWithFixedDelay(
                this::repair, 0, repairInterval.toMillis(), TimeUnit.MILLISECONDS);
        listener = Executors.newSingleThreadExecutor();
        listener.execute(this::listen);
    }

    private void repair() {
        try {
            long now = System.nanoTime();
            if (now - lastClaimRepairNanos >= repairInterval.toNanos()) {
                lastClaimRepairNanos = now;
                recovery.repair();
                initialRecovery.repair();
            }
        } catch (RuntimeException unavailable) {
            log.warn("Resume claim scan pending: {}", unavailable.toString());
        }
        try {
            int offset = 0;
            while (running) {
                var batch = store.pending(256, null, offset);
                if (batch.isEmpty()) return;
                int sent = publish(batch);
                // Keep moving past unavailable rows instead of starving later Sessions.
                offset += batch.size() - sent;
                if (batch.size() < 256) return;
            }
        } catch (RuntimeException unavailable) {
            log.warn("Resume outbox remains pending: {}", unavailable.toString());
        }
    }

    private void listen() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try (var connection = store.dataSource().getConnection()) {
                if (!connection.isWrapperFor(PGConnection.class)) return;
                listeningConnection = connection;
                connection.setAutoCommit(true);
                try (var statement = connection.createStatement()) {
                    statement.execute("LISTEN " + ManagedResumeSignals.CHANNEL);
                }
                // Close the disconnect/LISTEN race with a durable recovery pass.
                dispatcher.execute(this::repair);
                var pg = connection.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications(1000);
                    if (notifications != null)
                        for (var notification : notifications) wakeup(notification.getParameter());
                }
            } catch (SQLException | RuntimeException unavailable) {
                if (running) {
                    log.warn(
                            "Resume notification listener reconnecting: {}",
                            unavailable.toString());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                listeningConnection = null;
            }
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        if (localSignals != null) {
            try {
                localSignals.close();
            } catch (Exception failure) {
                log.debug("Cannot remove local Resume listener", failure);
            }
        }
        if (listeningConnection != null) {
            try {
                listeningConnection.close();
            } catch (SQLException failure) {
                log.debug("Cannot close Resume listener", failure);
            }
        }
        if (listener != null) listener.shutdownNow();
        if (dispatcher != null) dispatcher.shutdownNow();
        if (publications != null) publications.shutdownNow();
        dirty.clear();
        deferred.values().forEach(f -> f.cancel(false));
        deferred.clear();
    }
}
