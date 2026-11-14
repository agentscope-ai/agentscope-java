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
package io.agentscope.extensions.jdbc.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The keys written through {@link JdbcStore} are workspace file paths and session journal paths
 * ({@code agents/<name>/users/<user>/sessions/<id>.log.jsonl} and the like), whose ids regularly
 * push them past 255 characters. The store DDL has to accept them, or the very first write of a
 * fresh session fails before the model is ever called.
 */
@DisplayName("JdbcStore long item_key")
class JdbcStoreLongKeyH2Test {

    private JdbcStore store;

    @BeforeEach
    void setUp() {
        DataSource ds = H2TestSupport.createDataSource("jdbc_store_long_key_test");
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        store = JdbcStore.builder(ds).dialect(dialect).build();
    }

    @Test
    @DisplayName("putIfVersion accepts an item key longer than 255 characters")
    void putIfVersionAcceptsLongKey() {
        List<String> ns = List.of("agents", "data-agent", "users", "user-1");
        String key = keyOfLength(300);

        assertTrue(store.putIfVersion(ns, key, Map.of("v", 1), 0L));

        StoreItem item = store.get(ns, key);
        assertNotNull(item);
        assertEquals(key, item.key());
        assertEquals(1L, item.version());
    }

    @Test
    @DisplayName("put accepts an item key longer than 512 characters")
    void putAcceptsLongKey() {
        List<String> ns = List.of("ns");
        String key = keyOfLength(600);

        store.put(ns, key, Map.of("v", 1));

        assertEquals(key, store.get(ns, key).key());
    }

    /** A session journal path padded with ids until it reaches exactly {@code length} chars. */
    private static String keyOfLength(int length) {
        StringBuilder sb = new StringBuilder("agents/data-agent/users/u1/sessions/");
        while (sb.length() < length) {
            sb.append("sess_0123456789abcdef-");
        }
        sb.setLength(length);
        return sb.toString();
    }
}
