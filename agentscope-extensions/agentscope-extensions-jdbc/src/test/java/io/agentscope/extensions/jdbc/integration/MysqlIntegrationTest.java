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
package io.agentscope.extensions.jdbc.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test for MySQL — validates all four components
 * (state store, KV store, snapshot, lock) with a real MySQL database.
 *
 * <p>Requires Docker. Run with:
 * {@code mvn -pl agentscope-extensions/agentscope-extensions-jdbc -am verify -Pintegration}
 *
 * @author shanhongyu
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.DisplayName.class)
class MysqlIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0").withDatabaseName("agentscope");

    record TestState(String value) implements State {}

    @Test
    @DisplayName("01: AbstractJdbcDialect.from detects MySQL")
    void dialectDetection() {
        DataSource ds = createDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        assertTrue(dialect.getClass().getSimpleName().contains("Mysql"));
    }

    @Test
    @DisplayName("02: state store CRUD")
    void stateStoreCrud() {
        var store = JdbcDistributedStore.create(createDataSource()).agentStateStore();
        store.save("user1", "s1", "key", new TestState("value"));

        Optional<TestState> result = store.get("user1", "s1", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("value", result.get().value());
    }

    @Test
    @DisplayName("03: KV store CAS")
    void kvStoreCas() {
        var store = JdbcDistributedStore.create(createDataSource()).baseStore();
        assertTrue(store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L));
        StoreItem item = store.get(List.of("ns"), "k");
        assertNotNull(item);
        assertEquals(1L, item.version());
    }

    @Test
    @DisplayName("04: snapshot BLOB upload/download")
    void snapshotBlob() throws Exception {
        var ds = createDataSource();
        var spec =
                new io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec(
                        ds, AbstractJdbcDialect.from(ds).build());
        RemoteSnapshotClient client = spec.getClient();

        byte[] data = "mysql blob content".getBytes();
        client.upload("mysql-snap", new ByteArrayInputStream(data));
        try (var downloaded = client.download("mysql-snap")) {
            assertEquals(data.length, downloaded.readAllBytes().length);
        }
    }

    @Test
    @DisplayName("05: sandbox lock with a >64-char lock name is SHA-256-normalized")
    void longLockName() throws Exception {
        var guard = JdbcDistributedStore.create(createDataSource()).sandboxExecutionGuard();
        // The composed lock name exceeds MySQL's 64-char GET_LOCK limit; the dialect must
        // normalize it (truncated prefix + SHA-256 digest) so acquisition still works.
        String longValue = "agent-" + "x".repeat(120);
        var key = SandboxIsolationKey.resolve(IsolationScope.GLOBAL, null, longValue).orElseThrow();

        SandboxLease lease = guard.tryEnter(key);
        assertNotNull(lease);
        lease.close();
    }

    private DataSource createDataSource() {
        MysqlDataSource ds = new MysqlDataSource();
        ds.setUrl(mysql.getJdbcUrl());
        ds.setUser(mysql.getUsername());
        ds.setPassword(mysql.getPassword());
        return ds;
    }
}
