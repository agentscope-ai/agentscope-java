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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.jdbc.H2TestSupport;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
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

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void duplicatePrimaryKeyReturnsFalseAndPreservesExistingItem(String database) throws Exception {
        JdbcStore store = open(database, "");
        assertTrue(store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
        StoreItem before = store.get(ns, "key");
        assertFalse(store.putIfVersion(ns, "key", Map.of("value", "replacement"), 0));
        assertEquals(before, store.get(ns, "key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void checkViolationsPropagateOnInsertAndUpdateWithoutChangingState(String database)
            throws Exception {
        JdbcStore store = open(database, "CHECK (value_json NOT LIKE '%rejected%')");
        IllegalStateException insert =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.putIfVersion(ns, "key", Map.of("value", "rejected"), 0));
        assertInstanceOf(SQLException.class, insert.getCause());
        assertNull(store.get(ns, "key"));
        assertTrue(store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
        StoreItem before = store.get(ns, "key");
        IllegalStateException update =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                store.putIfVersion(
                                        ns, "key", Map.of("value", "rejected"), before.version()));
        assertInstanceOf(SQLException.class, update.getCause());
        assertEquals(before, store.get(ns, "key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void foreignKeyViolationsPropagateWithoutCreatingItem(String database) throws Exception {
        JdbcStore store =
                open(database, "FOREIGN KEY (item_key) REFERENCES allowed_keys(item_key)");
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                store.putIfVersion(
                                        ns, "missing-parent", Map.of("value", "original"), 0));
        assertInstanceOf(SQLException.class, error.getCause());
        assertNull(store.get(ns, "missing-parent"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "sqlite"})
    void notNullViolationsPropagateWithoutCreatingItem(String database) throws Exception {
        JdbcStore store = open(database, "required_value INTEGER NOT NULL");
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
        assertInstanceOf(SQLException.class, error.getCause());
        assertNull(store.get(ns, "key"));
    }

    @ParameterizedTest
    @MethodSource("sqlErrors")
    void onlySpecificDuplicateSignalsReturnFalse(SQLException sqlError, boolean duplicate)
            throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(sqlError);
        JdbcStore store = JdbcStore.builder(ds).dialect(new H2Dialect()).build();
        if (duplicate) {
            assertFalse(store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
            var result = new RemoteFilesystem(store, ns).write(null, "file.txt", "content");
            assertFalse(result.isSuccess());
            assertTrue(result.error().contains("already exists"));
        } else {
            IllegalStateException failure =
                    assertThrows(
                            IllegalStateException.class,
                            () -> store.putIfVersion(ns, "key", Map.of("value", "original"), 0));
            assertSame(sqlError, failure.getCause());
        }
    }

    private static Stream<Arguments> sqlErrors() {
        return Stream.of(
                Arguments.of(new SQLException("duplicate", "23505", 0), true),
                Arguments.of(new SQLException("MySQL old duplicate", "23000", 1022), true),
                Arguments.of(new SQLException("Oracle duplicate", "23000", 1), true),
                Arguments.of(new SQLException("SQL Server duplicate index", "23000", 2601), true),
                Arguments.of(
                        new SQLException("SQL Server duplicate constraint", "23000", 2627), true),
                Arguments.of(new SQLException("Informix duplicate", null, -239), true),
                Arguments.of(new SQLException("Informix duplicate constraint", null, -268), true),
                Arguments.of(new SQLException("Oracle check", "23000", 2290), false),
                Arguments.of(new SQLException("SQL Server foreign key", "23000", 547), false),
                Arguments.of(new SQLException("MySQL foreign key delete", "23000", 1451), false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("duplicate", "23000", 1062),
                        true),
                Arguments.of(
                        new SQLiteException(
                                "duplicate", SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY),
                        true),
                Arguments.of(
                        new SQLiteException("duplicate", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE),
                        true),
                Arguments.of(
                        new SQLiteException(
                                "unknown constraint", SQLiteErrorCode.SQLITE_CONSTRAINT),
                        false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("check", "23513", 23513),
                        false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("foreign key", "23000", 1452),
                        false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException("not null", "23000", 1048),
                        false),
                Arguments.of(new SQLException("generic constraint", "23000", 0), false),
                Arguments.of(
                        new SQLIntegrityConstraintViolationException(
                                "unknown constraint", null, 19),
                        false),
                Arguments.of(new SQLException("connection failure", "08006", 0), false));
    }

    private JdbcStore open(String database, String extra) throws Exception {
        DataSource ds;
        if (database.equals("sqlite")) {
            SQLiteDataSource sqlite = new SQLiteDataSource();
            sqlite.setUrl("jdbc:sqlite:" + temp.resolve("store.db"));
            sqlite.setEnforceForeignKeys(true);
            ds = sqlite;
        } else {
            ds = H2TestSupport.createDataSource("constraints");
        }
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).build();
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + dialect.storeTableName());
            statement.execute("CREATE TABLE allowed_keys (item_key VARCHAR(255) PRIMARY KEY)");
            statement.execute(
                    "CREATE TABLE "
                            + dialect.storeTableName()
                            + " (namespace_path VARCHAR(2048) NOT NULL, item_key VARCHAR(255) NOT"
                            + " NULL, value_json TEXT NOT NULL, version BIGINT NOT NULL, updated_at"
                            + " BIGINT NOT NULL,"
                            + (extra.isEmpty() ? "" : extra + ",")
                            + " PRIMARY KEY (namespace_path, item_key))");
        }
        return JdbcStore.builder(ds).dialect(dialect).build();
    }
}
