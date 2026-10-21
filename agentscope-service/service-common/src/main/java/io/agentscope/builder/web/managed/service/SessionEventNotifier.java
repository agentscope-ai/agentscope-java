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
package io.agentscope.builder.web.managed.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Sinks;

/**
 * Wakes session-event readers without continuously polling the event table.
 *
 * <p>Every append emits an in-process signal. PostgreSQL deployments additionally publish and
 * listen on a database channel so subscribers in other data-plane replicas wake immediately. A
 * missed database notification is harmless: {@link SessionEventLog} periodically performs a
 * low-frequency recovery read from its durable sequence cursor.
 */
@Service
public class SessionEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(SessionEventNotifier.class);
    private static final String CHANNEL = "builder_session_events";

    private final DataSource dataSource;
    private final long reconnectDelayMs;
    private final String instanceId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Subscribers> subscribers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private ExecutorService listenerExecutor;
    private volatile boolean postgres;
    private volatile Connection listeningConnection;

    public SessionEventNotifier(
            DataSource dataSource,
            @Value("${builder.session-event.notification-reconnect-ms:1000}")
                    long reconnectDelayMs) {
        this.dataSource = dataSource;
        this.reconnectDelayMs = Math.max(100L, reconnectDelayMs);
    }

    @PostConstruct
    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        listenerExecutor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "session-event-notifications");
                            thread.setDaemon(true);
                            return thread;
                        });
        listenerExecutor.submit(this::listenLoop);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        Connection connection = listeningConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ex) {
                log.debug("Failed to close notification listener", ex);
            }
        }
        if (listenerExecutor != null) {
            listenerExecutor.shutdownNow();
        }
    }

    /** Publish on the event transaction's connection; rollback must never wake public readers. */
    public void committed(String sessionId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.hasResource(dataSource))
            throw new IllegalStateException(
                    "Public event notifications require the event transaction");
        CommitSignals signals =
                TransactionSynchronizationManager.getSynchronizations().stream()
                        .filter(value -> value instanceof CommitSignals)
                        .map(value -> (CommitSignals) value)
                        .filter(value -> value.owner == this)
                        .findFirst()
                        .orElse(null);
        if (signals == null) {
            signals = new CommitSignals();
            TransactionSynchronizationManager.registerSynchronization(signals);
        }
        if (!signals.sessions.add(sessionId)) return;
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (isPostgres(connection)) {
                try (PreparedStatement statement =
                        connection.prepareStatement("SELECT pg_notify('" + CHANNEL + "', ?)")) {
                    statement.setString(1, instanceId + "\n" + sessionId);
                    statement.execute();
                }
            }
        } catch (SQLException ex) {
            throw new DataAccessResourceFailureException(
                    "Cannot commit public event notification", ex);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private final class CommitSignals implements TransactionSynchronization {
        private final SessionEventNotifier owner = SessionEventNotifier.this;
        private final Set<String> sessions = new HashSet<>();

        @Override
        public void afterCommit() {
            sessions.forEach(SessionEventNotifier.this::emitLocal);
        }
    }

    /** Emits a local wakeup and best-effort PostgreSQL notification after the event commits. */
    public void publish(String sessionId) {
        emitLocal(sessionId);
        if (!postgres) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT pg_notify('" + CHANNEL + "', ?)")) {
            statement.setString(1, instanceId + "\n" + sessionId);
            statement.execute();
        } catch (SQLException ex) {
            // The durable row is already committed. The recovery read will find it even if this
            // transient notification fails.
            log.debug("Failed to publish session-event notification for {}", sessionId, ex);
        }
    }

    /** Returns coalescible wakeups for one session. */
    public Flux<String> wakeups(String sessionId) {
        return Flux.defer(
                () -> {
                    var channel =
                            subscribers.compute(
                                    sessionId,
                                    (id, existing) -> {
                                        var selected =
                                                existing == null ? new Subscribers() : existing;
                                        selected.references++;
                                        return selected;
                                    });
                    return Flux.<String>create(
                                    sink -> {
                                        var subscription =
                                                channel.sink
                                                        .asFlux()
                                                        .subscribe(
                                                                sink::next,
                                                                sink::error,
                                                                sink::complete);
                                        sink.onDispose(subscription);
                                        // Register first, then request a durable catch-up. No
                                        // replay/live gap.
                                        sink.next(sessionId);
                                    },
                                    FluxSink.OverflowStrategy.LATEST)
                            .doFinally(
                                    ignored ->
                                            subscribers.computeIfPresent(
                                                    sessionId,
                                                    (id, current) -> {
                                                        if (current != channel) return current;
                                                        return --current.references == 0
                                                                ? null
                                                                : current;
                                                    }));
                });
    }

    private void emitLocal(String sessionId) {
        var channel = subscribers.get(sessionId);
        if (channel != null) {
            synchronized (channel) {
                channel.sink.tryEmitNext(sessionId);
            }
        }
    }

    /** Refresh an existing local Session reader when another SSE observer joins. */
    public void refresh(String sessionId) {
        emitLocal(sessionId);
    }

    private static final class Subscribers {
        private final Sinks.Many<String> sink = Sinks.many().multicast().directBestEffort();
        private int references;
    }

    private boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres");
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                if (!isPostgres(connection)) return;
                postgres = true;
                listeningConnection = connection;
                connection.setAutoCommit(true);
                statement.execute("LISTEN " + CHANNEL);
                // LISTEN is active before reading any missed durable prefixes.
                subscribers.keySet().forEach(this::emitLocal);
                PGConnection pg = connection.unwrap(PGConnection.class);
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    PGNotification[] notifications = pg.getNotifications(10_000);
                    if (notifications == null) {
                        continue;
                    }
                    for (PGNotification notification : notifications) {
                        String payload = notification.getParameter();
                        int separator = payload == null ? -1 : payload.indexOf('\n');
                        if (separator > 0 && instanceId.equals(payload.substring(0, separator))) {
                            continue;
                        }
                        String sessionId =
                                separator > 0 ? payload.substring(separator + 1) : payload;
                        if (sessionId != null && !sessionId.isBlank()) {
                            emitLocal(sessionId);
                        }
                    }
                }
            } catch (SQLException ex) {
                if (running.get()) {
                    log.warn("Session-event notification listener disconnected; reconnecting", ex);
                    sleepBeforeReconnect();
                }
            } finally {
                listeningConnection = null;
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(reconnectDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
