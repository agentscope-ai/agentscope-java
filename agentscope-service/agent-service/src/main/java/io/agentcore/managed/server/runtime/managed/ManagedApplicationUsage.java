/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import java.util.Collections;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, idempotent application accounting outside the Turn commit transaction. */
public final class ManagedApplicationUsage implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String claim;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        var thread = new Thread(task, "managed-application-usage");
                        thread.setDaemon(true);
                        return thread;
                    });

    public ManagedApplicationUsage(DataSource source) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(3);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(3);
        claim = ManagedDatabaseLocks.skipLocked(source);
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
                () -> {
                    try {
                        flush(512);
                    } catch (RuntimeException failure) {
                        LoggerFactory.getLogger(ManagedApplicationUsage.class)
                                .warn(
                                        "Application usage aggregation pending: {}",
                                        failure.getClass().getSimpleName());
                    }
                },
                1000,
                1000,
                TimeUnit.MILLISECONDS);
    }

    public int flush(int limit) {
        if (limit < 1 || limit > 4096)
            throw new IllegalArgumentException("Invalid aggregation batch");
        return tx.execute(
                ignored -> {
                    var rows =
                            jdbc.queryForList(
                                    "SELECT usage_id,quota_key,tokens_used FROM"
                                        + " ma.application_usage_delta ORDER BY created_at,usage_id"
                                        + " LIMIT ?"
                                            + claim,
                                    limit);
                    if (rows.isEmpty()) return 0;
                    var totals = new TreeMap<String, Long>();
                    for (var row : rows)
                        totals.merge(
                                (String) row.get("quota_key"),
                                ((Number) row.get("tokens_used")).longValue(),
                                Math::addExact);
                    // Consistent application order prevents cycles between concurrent aggregators.
                    totals.forEach(
                            (quota, tokens) -> {
                                if (jdbc.update(
                                                "UPDATE ma.admission_quota SET"
                                                    + " managed_tokens_used=managed_tokens_used+?"
                                                    + " WHERE quota_key=?",
                                                tokens,
                                                quota)
                                        != 1)
                                    throw new IllegalStateException("Application quota missing");
                            });
                    jdbc.update(
                            "DELETE FROM ma.application_usage_delta WHERE usage_id IN ("
                                    + String.join(",", Collections.nCopies(rows.size(), "?"))
                                    + ")",
                            rows.stream().map(row -> row.get("usage_id")).toArray());
                    return rows.size();
                });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
