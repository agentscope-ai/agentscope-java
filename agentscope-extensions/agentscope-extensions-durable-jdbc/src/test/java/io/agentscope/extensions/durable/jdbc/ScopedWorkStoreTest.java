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
package io.agentscope.extensions.durable.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.SessionSpec;
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.store.RecordStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Multiple Thread work items commit with their Session, without replacing sibling work. */
class ScopedWorkStoreTest {
    @TempDir Path directory;
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");
    private String schema;
    private JdbcRecordStore.Connections connections;
    private JdbcRecordStore.Dialect dialect;
    private JdbcRecordStore store;

    @BeforeEach
    void open() throws Exception {
        if (postgresUrl == null) {
            dialect = JdbcRecordStore.Dialect.SQLITE;
            connections =
                    () ->
                            DriverManager.getConnection(
                                    "jdbc:sqlite:" + directory.resolve("work.db"));
        } else {
            dialect = JdbcRecordStore.Dialect.POSTGRESQL;
            schema = "durable_work_" + UUID.randomUUID().toString().replace("-", "");
            try (var c = DriverManager.getConnection(postgresUrl);
                    var s = c.createStatement()) {
                s.execute("CREATE SCHEMA " + schema);
            }
            var properties = new Properties();
            properties.setProperty("currentSchema", schema);
            connections = () -> DriverManager.getConnection(postgresUrl, properties);
        }
        store = new JdbcRecordStore(connections, dialect);
    }

    @AfterEach
    void close() throws Exception {
        if (store != null) store.close();
        if (schema != null)
            try (var c = DriverManager.getConnection(postgresUrl);
                    var s = c.createStatement()) {
                s.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }

    @Test
    void siblingsRetainIndependentPayloadsOrderingAndDeadlinesAfterReopen() {
        store.transaction(
                "session",
                tx -> {
                    tx.put("thread:a", DurableJson.value("queued"));
                    tx.put("thread:b", DurableJson.value("queued"));
                    tx.schedule("brain", "b", 2, DurableJson.value("second"));
                    tx.schedule("brain", "a", 1, DurableJson.value("first"));
                    tx.schedule("brain", "later", Long.MAX_VALUE, DurableJson.value("future"));
                    return null;
                });
        store.close();
        store = new JdbcRecordStore(connections, dialect, false);
        assertEquals(
                List.of(
                        new RecordStore.Work("session", "a", 1, DurableJson.value("first")),
                        new RecordStore.Work("session", "b", 2, DurableJson.value("second"))),
                store.ready("brain", 10));
        assertEquals(
                List.of("a"),
                store.ready("brain", 1).stream().map(RecordStore.Work::workId).toList());
    }

    @Test
    void rescheduleAndRemovalDoNotChangeSiblingsOtherScopesOrOtherQueues() {
        for (String scope : List.of("session-a", "session-b"))
            store.transaction(
                    scope,
                    tx -> {
                        tx.schedule("brain", "a", 1, DurableJson.value("original"));
                        tx.schedule("brain", "b", 1, DurableJson.value("sibling"));
                        tx.schedule("other", "a", 1, DurableJson.value("other"));
                        return null;
                    });
        store.transaction(
                "session-a",
                tx -> {
                    tx.schedule("brain", "a", 0, DurableJson.value("updated"));
                    tx.schedule("brain", "b", -1, null);
                    return null;
                });
        assertEquals(
                List.of(
                        new RecordStore.Work("session-a", "a", 0, DurableJson.value("updated")),
                        new RecordStore.Work("session-b", "a", 1, DurableJson.value("original")),
                        new RecordStore.Work("session-b", "b", 1, DurableJson.value("sibling"))),
                store.ready("brain", 10));
        assertEquals(2, store.ready("other", 10).size());
    }

    @Test
    void rollbackRestoresBothStateAndEveryWorkMutation() {
        store.transaction(
                "session",
                tx -> {
                    tx.put("", DurableJson.value("before"));
                    tx.schedule("brain", "a", 0, DurableJson.value("before"));
                    return null;
                });
        assertThrows(
                IllegalStateException.class,
                () ->
                        store.transaction(
                                "session",
                                tx -> {
                                    tx.put("", DurableJson.value("after"));
                                    tx.put("thread:b", DurableJson.value("queued"));
                                    tx.schedule("brain", "a", -1, null);
                                    tx.schedule("brain", "b", 0, DurableJson.value("new"));
                                    throw new IllegalStateException("abort before commit");
                                }));
        assertEquals(
                List.of(new RecordStore.Work("session", "a", 0, DurableJson.value("before"))),
                store.ready("brain", 10));
        store.transaction(
                "session",
                tx -> {
                    assertEquals("before", tx.get("").asText());
                    assertNull(tx.get("thread:b"));
                    return null;
                });
    }

    @Test
    void concurrentConnectionsCannotLoseAnotherThreadsWork() throws Exception {
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var other = new JdbcRecordStore(connections, dialect, false)) {
            var first = executor.submit(() -> writeAfter(start, store, "a"));
            var second = executor.submit(() -> writeAfter(start, other, "b"));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(
                    List.of("a", "b"),
                    store.ready("brain", 10).stream().map(RecordStore.Work::workId).toList());
            store.transaction(
                    "session",
                    tx -> {
                        assertEquals("a", tx.get("thread:a").asText());
                        assertEquals("b", tx.get("thread:b").asText());
                        return null;
                    });
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void writeAfter(CountDownLatch start, RecordStore records, String id) {
        try {
            if (!start.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("Writer not released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        records.transaction(
                "session",
                tx -> {
                    tx.put("thread:" + id, DurableJson.value(id));
                    tx.schedule("brain", id, 0, DurableJson.value(id));
                    return null;
                });
    }

    @Test
    void incompatibleWorkSchemaIsRejectedWithoutConvertingOrErasingRows() throws Exception {
        store.close();
        try (var c = connections.open();
                var s = c.createStatement()) {
            s.execute("DROP TABLE durable_work");
            s.execute(
                    "CREATE TABLE durable_work(queue VARCHAR(256), record_key VARCHAR(512),"
                            + " ready_at BIGINT, payload TEXT, PRIMARY KEY(queue,record_key))");
            s.execute("INSERT INTO durable_work VALUES ('brain','preserve',0,'original')");
        }
        for (boolean initialize : List.of(false, true)) {
            var failure =
                    assertThrows(
                            DurableException.class,
                            () -> new JdbcRecordStore(connections, dialect, initialize));
            assertEquals(DurableException.Code.INCOMPATIBLE, failure.code());
        }
        try (var c = connections.open();
                var s = c.createStatement();
                var rows = s.executeQuery("SELECT record_key,payload FROM durable_work")) {
            assertTrue(rows.next());
            assertEquals("preserve", rows.getString(1));
            assertEquals("original", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test
    void previousSessionLayoutWithoutLifecycleHistoryIsRejectedWithoutChangingStoredData() {
        var key = new SessionKey("test", "old-projection");
        var specification = new SessionSpec(key, new ThreadBinding("agent", "v1", "v1"), null);
        var backend = new TransactionalSessionBackend(store);
        backend.create(specification);
        var previous =
                store.transaction(
                        key.storageKey(),
                        tx -> {
                            var value = (ObjectNode) tx.get("").deepCopy();
                            value.put("format", 10);
                            tx.put("", value);
                            tx.put("tool-view:old-execution", DurableJson.value("original"));
                            return value;
                        });
        assertEquals(
                DurableException.Code.INCOMPATIBLE,
                assertThrows(DurableException.class, () -> backend.snapshot(key)).code());
        assertEquals(
                DurableException.Code.INCOMPATIBLE,
                assertThrows(DurableException.class, () -> backend.create(specification)).code());
        store.transaction(
                key.storageKey(),
                tx -> {
                    assertEquals(previous, tx.get(""));
                    assertEquals(DurableJson.value("original"), tx.get("tool-view:old-execution"));
                    return null;
                });
    }

    @Test
    void invalidIdentityRollsBackTheTransactionBeforeAnyReadyItemIsPublished() {
        for (String workId : List.of("", " ", "x".repeat(513)))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            store.transaction(
                                    "session",
                                    tx -> {
                                        tx.put("", DurableJson.value("uncommitted"));
                                        tx.schedule(
                                                "brain", workId, 0, DurableJson.value("invalid"));
                                        return null;
                                    }));
        assertTrue(store.ready("brain", 10).isEmpty());
        store.transaction(
                "session",
                tx -> {
                    assertNull(tx.get(""));
                    return null;
                });
    }
}
