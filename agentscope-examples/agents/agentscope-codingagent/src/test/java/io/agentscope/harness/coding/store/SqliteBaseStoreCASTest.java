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
package io.agentscope.harness.coding.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.store.StoreItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies CAS semantics of {@link SqliteBaseStore#putIfVersion} backed by SQLite's atomic
 * single-statement UPDATE / INSERT-OR-IGNORE.
 */
class SqliteBaseStoreCASTest {

    private SqliteBaseStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new SqliteBaseStore(":memory:");
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private static final List<String> NS = List.of("cas-test");

    @Test
    void put_initializesVersionToOne() {
        store.put(NS, "k", Map.of("v", 1));
        StoreItem item = store.get(NS, "k");
        assertNotNull(item);
        assertEquals(1L, item.version());
    }

    @Test
    void put_incrementsVersionOnUpdate() {
        store.put(NS, "k", Map.of("v", 1));
        store.put(NS, "k", Map.of("v", 2));
        StoreItem item = store.get(NS, "k");
        assertEquals(2L, item.version());
        assertEquals(2, item.value().get("v"));
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
    void putIfVersion_bumpsVersionOnSuccess_rejectsStaleVersion() {
        store.put(NS, "k", Map.of("v", 1));
        StoreItem v1 = store.get(NS, "k");

        assertTrue(store.putIfVersion(NS, "k", Map.of("v", 2), v1.version()));
        assertEquals(2L, store.get(NS, "k").version());

        assertFalse(store.putIfVersion(NS, "k", Map.of("v", 3), v1.version()));
        assertEquals(2, store.get(NS, "k").value().get("v"));
    }

    @Test
    void putIfVersion_targetingMissingKey_withNonZeroExpected_fails() {
        // No row exists yet; expecting any version > 0 must fail.
        assertFalse(store.putIfVersion(NS, "absent", Map.of("v", 1), 1L));
    }

    @Test
    void search_populatesVersion() {
        store.put(NS, "a", Map.of("v", 1));
        store.put(NS, "a", Map.of("v", 2));
        store.put(NS, "b", Map.of("v", 1));

        List<StoreItem> items = store.search(NS, -1, 0);
        assertEquals(2, items.size());
        for (StoreItem it : items) {
            assertTrue(it.version() >= 1L);
        }
    }
}
