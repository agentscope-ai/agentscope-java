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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Commit-scoped hints only: the Resume outbox remains the durable source of truth. */
public final class ManagedResumeSignals {
    static final String CHANNEL = "ma_turn_resumes";
    private static final ConcurrentHashMap<DataSource, Set<Consumer<String>>> LOCAL =
            new ConcurrentHashMap<>();

    public static AutoCloseable subscribe(DataSource source, Consumer<String> listener) {
        LOCAL.compute(
                source,
                (key, listeners) -> {
                    if (listeners == null) listeners = ConcurrentHashMap.newKeySet();
                    listeners.add(listener);
                    return listeners;
                });
        return () ->
                LOCAL.computeIfPresent(
                        source,
                        (key, listeners) -> {
                            listeners.remove(listener);
                            return listeners.isEmpty() ? null : listeners;
                        });
    }

    public static void committed(JdbcTemplate jdbc, String scopeKey) {
        jdbc.execute(
                (ConnectionCallback<Void>)
                        connection -> {
                            if (connection.isWrapperFor(PGConnection.class)) {
                                try (var statement =
                                        connection.prepareStatement(
                                                "SELECT pg_notify('" + CHANNEL + "', ?)")) {
                                    statement.setString(1, scopeKey);
                                    statement.execute();
                                }
                            }
                            return null;
                        });
        Runnable emit =
                () -> {
                    var listeners = LOCAL.get(jdbc.getDataSource());
                    if (listeners != null) listeners.forEach(listener -> listener.accept(scopeKey));
                };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emit.run();
                        }
                    });
        } else emit.run();
    }
}
