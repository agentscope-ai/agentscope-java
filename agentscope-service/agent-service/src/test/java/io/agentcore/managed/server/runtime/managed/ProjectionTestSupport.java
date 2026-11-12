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
import io.agentcore.managed.server.runtime.managed.projection.PostgresSessionProjectionStore;
import io.agentcore.managed.server.web.managed.service.SessionEventNotifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Deterministic query-side consumer pump for tests which assert fully caught-up public views. */
public final class ProjectionTestSupport {
    private ProjectionTestSupport() {}

    private static final Map<DataSource, ManagedQueryViews> VIEWS = new WeakHashMap<>();

    public static synchronized ManagedQueryViews views(DataSource source, ObjectMapper mapper) {
        var key = source;
        while (key instanceof DelegatingDataSource delegated) key = delegated.getTargetDataSource();
        var existing = VIEWS.get(key);
        if (existing != null) return existing;
        var query =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:query-"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        PostgresSessionProjectionStore.initialize(query);
        var view =
                new ManagedQueryViews(
                        new PostgresSessionProjectionStore(query, mapper),
                        mapper,
                        "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                        "v1",
                        1000,
                        new ManagedPublicResources(source, mapper));
        VIEWS.put(key, view);
        return view;
    }

    static JdbcTemplate queryJdbc(DataSource source, ObjectMapper mapper) {
        var store = ReflectionTestUtils.getField(views(source, mapper), "store");
        return (JdbcTemplate) ReflectionTestUtils.getField(store, "jdbc");
    }

    static ManagedAdmissionStore admission(DataSource source, ObjectMapper mapper) {
        var result = new ManagedAdmissionStore(source, mapper);
        result.queryViews(views(source, mapper));
        return result;
    }

    public static ManagedJournalStore journal(
            DataSource source, ObjectMapper mapper, ManagedCheckpointObjects objects) {
        return journal(source, mapper, null, objects);
    }

    public static ManagedJournalStore journal(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects) {
        return journal(source, mapper, budgets, objects, new SessionEventNotifier(source, 1000));
    }

    public static ManagedJournalStore journal(
            DataSource source,
            ObjectMapper mapper,
            ManagedBudgetStore budgets,
            ManagedCheckpointObjects objects,
            SessionEventNotifier notifications) {
        var journal = new ManagedJournalStore(source, mapper, budgets, objects, notifications);
        journal.queryViews(views(source, mapper));
        return journal;
    }

    static ManagedJournalStore project(ManagedJournalStore journal) {
        for (var scope : journal.projections().rebuildScopes(4096))
            while (journal.projections().project(scope, 128) > 0) {
                /* Drain this committed prefix. */
            }
        return journal;
    }

    static <T> T project(ManagedJournalStore journal, T view) {
        project(journal);
        return view;
    }

    /** Explicit synchronous pump emulates the post-commit publisher without sleeping in unit tests. */
    static ManagedRuntimeWriter writer(
            ManagedJournalStore journal,
            DataSource source,
            ManagedMessageBus bus,
            ManagedBatchCodec codec,
            ObjectMapper mapper) {
        var delegate = new ManagedSessionStore(journal, new SessionCommitTransactions(journal));
        var publisher = new ManagedToolCommandPublisher(source, bus, codec, mapper);
        return new ManagedRuntimeWriter(
                new SessionStore() {
                    public SessionCommitReceipt commit(SessionCommit batch) {
                        var receipt = delegate.commit(batch);
                        publisher.publishCommands(128);
                        return receipt;
                    }

                    public SessionCommitReceipt receipt(ManagedScope scope, String id) {
                        return delegate.receipt(scope, id);
                    }

                    public SessionRecoveryHead head(ManagedScope scope) {
                        return delegate.head(scope);
                    }
                });
    }
}
