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
package io.agentscope.extensions.mysql.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class JdbcStoreTest {
    @TempDir Path directory;

    @Test
    void getAllReturnsExistingItemsAndOmitsMissing() {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("store.db"));
        var store = JdbcStore.builder(dataSource).initializeSchema(true).build();
        List<String> namespace = List.of("batch");

        store.put(namespace, "a", Map.of("v", 1));
        store.put(namespace, "b", Map.of("v", 2));

        Map<String, StoreItem> items = store.getAll(namespace, List.of("a", "missing", "b", "a"));

        assertEquals(2, items.size());
        assertEquals(1, items.get("a").value().get("v"));
        assertEquals(1L, items.get("b").version());
        assertFalse(items.containsKey("missing"));
    }
}
