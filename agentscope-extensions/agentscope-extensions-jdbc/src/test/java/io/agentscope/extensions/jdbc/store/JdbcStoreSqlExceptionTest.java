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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JdbcStoreSqlExceptionTest {

    private PreparedStatement statement;
    private JdbcStore store;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        store = JdbcStore.builder(dataSource).dialect(new H2Dialect()).build();
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "0, insert, 22001",
                "1, update, 22001",
                "0, insert, 42P01",
                "1, update, 42P01",
                "0, insert, 42703",
                "1, update, 42703",
                "0, insert, 08006",
                "1, update, 08006",
                "0, insert, 40001",
                "1, update, 40001",
                "0, insert, NULL",
                "1, update, NULL"
            },
            nullValues = "NULL")
    void putIfVersionIncludesSqlDiagnostics(long expectedVersion, String operation, String sqlState)
            throws SQLException {
        SQLException cause = new SQLException("database write failed", sqlState);
        when(statement.executeUpdate()).thenThrow(cause);

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                store.putIfVersion(
                                        List.of("ns"), "key", Map.of("v", 1), expectedVersion));

        assertEquals(
                "JdbcStore putIfVersion ("
                        + operation
                        + ") failed [SQLState="
                        + sqlState
                        + "]: database write failed",
                failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"23505", "23000"})
    void putIfVersionStillReturnsFalseForDuplicateInsert(String sqlState) throws SQLException {
        when(statement.executeUpdate()).thenThrow(new SQLException("duplicate key", sqlState));

        assertFalse(store.putIfVersion(List.of("ns"), "key", Map.of("v", 1), 0L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L})
    void putIfVersionStillSucceeds(long expectedVersion) throws SQLException {
        when(statement.executeUpdate()).thenReturn(1);

        assertTrue(store.putIfVersion(List.of("ns"), "key", Map.of("v", 1), expectedVersion));
    }
}
