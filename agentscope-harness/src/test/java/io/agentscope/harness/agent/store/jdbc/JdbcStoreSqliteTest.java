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
package io.agentscope.harness.agent.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.store.StoreItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

/**
 * End-to-end exercise of {@link JdbcStore} against a real SQLite database. Validates both the
 * common BaseStore contract and the SQLite-specific dialect (UPSERT, CAS UPDATE, prefix LIKE
 * search).
 */
class JdbcStoreSqliteTest {

    private static final List<String> NS = List.of("test", "items");

    private Path dbFile;
    private JdbcStore store;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("agentscope-jdbcstore-", ".db");
        Files.deleteIfExists(dbFile);
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        store = JdbcStore.builder(ds).initializeSchema(true).build();
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(dbFile);
    }

    @Test
    void dialectAutoDetect_picksSqlite() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite::memory:");
        JdbcStoreDialect dialect = JdbcStoreDialect.from(ds);
        assertInstanceOf(SqliteJdbcStoreDialect.class, dialect);
    }

    @Test
    void putAndGet_roundTripsValueAndStartsAtVersionOne() {
        store.put(NS, "k1", Map.of("v", 1));
        StoreItem item = store.get(NS, "k1");
        assertNotNull(item);
        assertEquals("k1", item.key());
        assertEquals(1, item.value().get("v"));
        assertEquals(1L, item.version());
    }

    @Test
    void put_overwritesAndBumpsVersion() {
        store.put(NS, "k1", Map.of("v", 1));
        store.put(NS, "k1", Map.of("v", 2));
        StoreItem item = store.get(NS, "k1");
        assertEquals(2, item.value().get("v"));
        assertEquals(2L, item.version());
    }

    @Test
    void delete_removesItem() {
        store.put(NS, "k1", Map.of("v", 1));
        store.delete(NS, "k1");
        assertNull(store.get(NS, "k1"));
    }

    @Test
    void putIfVersionZero_succeedsWhenAbsent_failsWhenPresent() {
        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 1), 0L));
        StoreItem first = store.get(NS, "k");
        assertEquals(1L, first.version());

        assertFalse(store.putIfVersion(NS, "k", Map.of("v", 2), 0L));
        assertEquals(1, store.get(NS, "k").value().get("v"));
    }

    @Test
    void putIfVersion_bumpsVersionOnSuccess() {
        store.put(NS, "k", Map.of("v", 1));
        StoreItem v1 = store.get(NS, "k");

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 2), v1.version()));
        assertEquals(2L, store.get(NS, "k").version());

        // Re-using the stale version must fail.
        assertFalse(store.putIfVersion(NS, "k", Map.of("v", 3), v1.version()));
        assertEquals(2, store.get(NS, "k").value().get("v"));
    }

    @Test
    void putIfVersionZero_onMissingKeyAfterDelete_succeeds() {
        store.put(NS, "k", Map.of("v", 1));
        store.delete(NS, "k");
        assertNull(store.get(NS, "k"));

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 99), 0L));
        assertEquals(1L, store.get(NS, "k").version());
    }

    @Test
    void putIfVersion_rejectsNegativeExpectedVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfVersion(NS, "k", Map.of("v", 1), -1L));
    }

    @Test
    void search_returnsItemsInTheNamespaceAndSubNamespaces_paged() {
        store.put(NS, "a", Map.of("v", 1));
        store.put(NS, "b", Map.of("v", 2));
        store.put(NS, "c", Map.of("v", 3));
        // Sub-namespace items: same prefix matches.
        store.put(List.of("test", "items", "sub"), "d", Map.of("v", 4));

        List<StoreItem> page1 = store.search(NS, 2, 0);
        assertEquals(2, page1.size());
        assertEquals("a", page1.get(0).key());
        assertEquals("b", page1.get(1).key());

        List<StoreItem> page2 = store.search(NS, 2, 2);
        assertEquals(2, page2.size());
        // Both "c" (own ns) and "d" (sub-ns) should be returned across the two pages, in
        // alphabetical key order.
        assertEquals("c", page2.get(0).key());
        assertEquals("d", page2.get(1).key());
    }

    @Test
    void search_isolatesUnrelatedNamespacesWithSamePrefix() {
        store.put(List.of("items"), "a", Map.of("v", 1));
        store.put(List.of("itemsExtra"), "b", Map.of("v", 2)); // shares "items" prefix as text

        // Searching ["items"] must NOT include the "itemsExtra" namespace item, even though the
        // raw prefix string starts the same — the trailing 0x1F separator on the stored path
        // prevents the false match.
        List<StoreItem> items = store.search(List.of("items"), 10, 0);
        assertEquals(1, items.size());
        assertEquals("a", items.get(0).key());
    }

    @Test
    void search_withZeroLimit_returnsEmpty() {
        store.put(NS, "a", Map.of("v", 1));
        assertEquals(0, store.search(NS, 0, 0).size());
    }

    @Test
    void put_acceptsNestedJsonValues() {
        Map<String, Object> nested =
                Map.of("name", "alice", "tags", List.of("x", "y"), "meta", Map.of("age", 30));
        store.put(NS, "user", nested);
        StoreItem item = store.get(NS, "user");
        assertEquals("alice", item.value().get("name"));
        assertEquals(List.of("x", "y"), item.value().get("tags"));
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) item.value().get("meta");
        assertEquals(30, meta.get("age"));
    }

    @Test
    void namespaceSegmentContainingUnitSeparator_isRejected() {
        // The unit separator is reserved as the internal path delimiter — segments may not contain
        // it (otherwise prefix matching would silently break).
        List<String> bad = List.of("test", "itemsboom");
        assertThrows(IllegalArgumentException.class, () -> store.put(bad, "k", Map.of("v", 1)));
    }

    @Test
    void builderRejectsBadTableName() {
        DataSource ds = new SQLiteDataSource();
        assertThrows(
                IllegalArgumentException.class,
                () -> JdbcStore.builder(ds).tableName("DROP TABLE users;"));
        assertThrows(IllegalArgumentException.class, () -> JdbcStore.builder(ds).tableName(""));
    }
}
