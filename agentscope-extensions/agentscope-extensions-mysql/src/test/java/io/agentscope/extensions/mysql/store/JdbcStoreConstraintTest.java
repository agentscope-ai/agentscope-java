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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.SQLiteDataSource;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class JdbcStoreConstraintTest {
    @TempDir Path temp;
    private final List<String> ns = List.of("test");

    @Test
    void duplicatePrimaryKeyReturnsFalseAndPreservesExistingItem() throws Exception {
        JdbcStore store = open("");
        assertTrue(store.supportsAtomicSessionStorage());
        assertTrue(store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
        StoreItem before = store.get(ns, "key");
        assertFalse(store.putIfVersion(ns, "key", Map.of("value", "replacement"), 0));
        assertEquals(before, store.get(ns, "key"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CHECK (value_json NOT LIKE '%rejected%')",
                "FOREIGN KEY (item_key) REFERENCES allowed_keys(item_key)",
                "required_value INTEGER NOT NULL"
            })
    void otherConstraintsPropagateWithoutCreatingAnItem(String constraint) throws Exception {
        JdbcStore store = open(constraint);
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.putIfVersion(ns, "key", Map.of("value", "rejected"), 0));
        assertInstanceOf(SQLException.class, failure.getCause());
        assertNull(store.get(ns, "key"));
    }

    @Test
    void failedUpdatePreservesValueAndVersion() throws Exception {
        JdbcStore store = open("CHECK (value_json NOT LIKE '%rejected%')");
        assertTrue(store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
        StoreItem before = store.get(ns, "key");
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                store.putIfVersion(
                                        ns, "key", Map.of("value", "rejected"), before.version()));
        assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals(before, store.get(ns, "key"));
    }

    @ParameterizedTest
    @MethodSource("sqlErrors")
    void onlySpecificDuplicateSignalsReturnFalse(SQLException cause, boolean duplicate)
            throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(cause);
        JdbcStore store = JdbcStore.builder(ds).dialect(new H2JdbcStoreDialect()).build();
        if (duplicate) {
            assertFalse(store.putIfVersion(ns, "key", Map.of(), 0));
        } else {
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> store.putIfVersion(ns, "key", Map.of(), 0));
            assertSame(cause, failure.getCause());
        }
    }

    private static Stream<Arguments> sqlErrors() {
        return Stream.of(
                Arguments.of(new SQLException("duplicate", "23505"), true),
                Arguments.of(new SQLException("MySQL duplicate", "23000", 1062), true),
                Arguments.of(new SQLException("MySQL duplicate", "23000", 1022), true),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("foreign key", "23000", 1452),
                        false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("not null", "23000", 1048),
                        false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("check", "23513", 23513),
                        false),
                Arguments.of(new SQLException("generic constraint", "23000"), false),
                Arguments.of(new SQLIntegrityConstraintViolationException("unknown"), false),
                Arguments.of(new SQLException("connection", "08006"), false),
                Arguments.of(
                        new SQLiteException(
                                "duplicate", SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY),
                        true),
                Arguments.of(
                        new SQLiteException("duplicate", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE),
                        true),
                Arguments.of(
                        new SQLiteException("unknown", SQLiteErrorCode.SQLITE_CONSTRAINT), false));
    }

    private JdbcStore open(String extra) throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("store.db"));
        ds.setEnforceForeignKeys(true);
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE allowed_keys (item_key TEXT PRIMARY KEY)");
            statement.execute(
                    "CREATE TABLE agentscope_store (namespace_path TEXT NOT NULL,"
                            + " item_key TEXT NOT NULL, value_json TEXT NOT NULL,"
                            + " version BIGINT NOT NULL, updated_at BIGINT NOT NULL,"
                            + (extra.isEmpty() ? "" : extra + ",")
                            + " PRIMARY KEY (namespace_path, item_key))");
        }
        return JdbcStore.builder(ds).dialect(new SqliteJdbcStoreDialect()).build();
    }
}
