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

import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
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
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers integration test for PostgreSQL — validates all four components
 * with a real PostgreSQL database, especially ON CONFLICT UPSERT and BYTEA handling.
 *
 * <p>Requires Docker. Run with:
 * {@code mvn -pl agentscope-extensions/agentscope-extensions-jdbc -am verify -Pintegration}
 *
 * @author shanhongyu
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.DisplayName.class)
class PostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16").withDatabaseName("agentscope");

    record TestState(String value) implements State {}

    @Test
    @DisplayName("01: AbstractJdbcDialect.from detects PostgreSQL")
    void dialectDetection() {
        DataSource ds = createDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        assertTrue(dialect.getClass().getSimpleName().contains("Postgres"));
    }

    @Test
    @DisplayName("02: state store CRUD with ON CONFLICT")
    void stateStoreCrud() {
        var store = JdbcDistributedStore.create(createDataSource()).agentStateStore();
        store.save("user1", "s1", "key", new TestState("value"));

        Optional<TestState> result = store.get("user1", "s1", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("value", result.get().value());
    }

    @Test
    @DisplayName("03: KV store CAS with ON CONFLICT")
    void kvStoreCas() {
        var store = JdbcDistributedStore.create(createDataSource()).baseStore();
        assertTrue(store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L));
        StoreItem item = store.get(List.of("ns"), "k");
        assertNotNull(item);
        assertEquals(1L, item.version());
    }

    @Test
    @DisplayName("04: snapshot BYTEA upload/download")
    void snapshotBytea() throws Exception {
        var ds = createDataSource();
        var spec =
                new io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec(
                        ds, AbstractJdbcDialect.from(ds).build());
        RemoteSnapshotClient client = spec.getClient();

        byte[] data = "postgres bytea content".getBytes();
        client.upload("pg-snap", new ByteArrayInputStream(data));
        try (var downloaded = client.download("pg-snap")) {
            assertEquals(data.length, downloaded.readAllBytes().length);
        }
    }

    private DataSource createDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }
}
