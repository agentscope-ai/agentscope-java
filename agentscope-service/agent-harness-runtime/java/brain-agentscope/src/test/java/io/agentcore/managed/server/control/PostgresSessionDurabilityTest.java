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
package io.agentcore.managed.server.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.core.session.JournalSessionLog;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.session.StoreSessionStorage;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Runs only against an explicitly supplied disposable PostgreSQL regression database. */
@EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_TEST_POSTGRES_URL", matches = ".+")
class PostgresSessionDurabilityTest {
    private final String url = System.getenv("MANAGED_AGENTS_TEST_POSTGRES_URL");
    private final String user = System.getenv("MANAGED_AGENTS_TEST_POSTGRES_USER");
    private final String password = System.getenv("MANAGED_AGENTS_TEST_POSTGRES_PASSWORD");
    private final String schema = "regression_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        execute("CREATE SCHEMA " + schema);
    }

    @AfterEach
    void removeOnlyThisTestsSchema() throws Exception {
        execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test
    void independentJdbcInstancesFenceNativeWritersAndRecoverOnlyCommittedEvents() {
        var dataSource = new DriverManagerDataSource(scopedUrl(), user, password);
        var firstStore =
                JdbcStore.builder(dataSource)
                        .dialect(AbstractJdbcDialect.from(dataSource).build())
                        .build();
        var secondStore =
                JdbcStore.builder(dataSource)
                        .dialect(AbstractJdbcDialect.from(dataSource).build())
                        .build();
        var first =
                new JournalSessionLog(
                        new StoreSessionStorage(firstStore, List.of("tenant-a")), "session");
        var second =
                new JournalSessionLog(
                        new StoreSessionStorage(secondStore, List.of("tenant-a")), "session");
        var writer = first.acquire("run-a", Duration.ofMinutes(1));
        assertThatThrownBy(() -> second.acquire("run-b", Duration.ofMinutes(1)))
                .isInstanceOf(SessionLogException.class);
        var event = new SessionEvent(1, "event", 1, 1, "run/start", "run-a", "turn", true, "{}");
        first.commit(writer, "batch", 0, List.of(event));
        first.commit(writer, "batch", 0, List.of(event));
        assertThat(second.readAfter(0, 100)).containsExactly(event);
        second.sealWriter("run-a");
        assertThatThrownBy(
                        () ->
                                first.commit(
                                        writer,
                                        "late",
                                        1,
                                        List.of(
                                                new SessionEvent(
                                                        1, "late", 2, 2, "run/end", "run-a", "turn",
                                                        true, "{}"))))
                .isInstanceOf(SessionLogException.class);
        assertThatThrownBy(() -> first.acquire("run-a", Duration.ofMinutes(1)))
                .isInstanceOf(SessionLogException.class);
        var next = second.acquire("run-b", Duration.ofMinutes(1));
        second.release(next);
        assertThat(
                        new JournalSessionLog(
                                        new StoreSessionStorage(secondStore, List.of("tenant-b")),
                                        "session")
                                .head()
                                .seq())
                .isZero();
    }

    private String scopedUrl() {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
