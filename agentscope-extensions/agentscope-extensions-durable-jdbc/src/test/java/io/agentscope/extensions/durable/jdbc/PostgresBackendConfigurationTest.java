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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.Wakeups;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresBackendConfigurationTest {
    DataSource source;
    Connection connection;
    Statement statement;
    ResultSet result;

    @BeforeEach
    void setup() throws Exception {
        source = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(result);
    }

    @Test
    void normalStartupRequiresExistingSchemaAndBorrowsPoolAndNotifications() throws Exception {
        var hints = mock(Wakeups.class, withSettings().extraInterfaces(AutoCloseable.class));
        try (var backend =
                PostgresBackend.builder().dataSource(source).notifications(hints).build()) {
            assertEquals(hints, backend.wakeups());
            assertEquals("postgresql", backend.capabilities().storage());
        }
        verify(statement, never()).executeUpdate(anyString());
        verify(statement).executeQuery("SELECT record_key, payload FROM durable_records WHERE 1=0");
        verify(statement).executeQuery("SELECT part_key, payload FROM durable_parts WHERE 1=0");
        verify(statement)
                .executeQuery(
                        "SELECT queue, record_key, work_id, ready_at, payload FROM durable_work"
                                + " WHERE 1=0");
        verify(result, times(3)).close();
        verify(connection).close();
        verify((AutoCloseable) source, never()).close();
        verify((AutoCloseable) hints, never()).close();
    }

    @Test
    void schemaInitializationIsExplicitAndStillReturnsBorrowedConnection() throws Exception {
        PostgresBackend.initializeSchema(source);
        verify(connection).setAutoCommit(true);
        verify(statement)
                .executeUpdate(
                        "CREATE TABLE IF NOT EXISTS durable_records (record_key VARCHAR(512)"
                                + " PRIMARY KEY, payload TEXT)");
        verify(statement)
                .executeUpdate(
                        "CREATE TABLE IF NOT EXISTS durable_work (queue VARCHAR(256) NOT NULL,"
                            + " record_key VARCHAR(512) NOT NULL, work_id VARCHAR(512) NOT NULL,"
                            + " ready_at BIGINT NOT NULL, payload TEXT NOT NULL, PRIMARY"
                            + " KEY(queue,record_key,work_id))");
        verify(statement)
                .executeQuery(
                        "SELECT queue, record_key, work_id, ready_at, payload FROM durable_work"
                                + " WHERE 1=0");
        verify(result, times(3)).close();
        verify(connection).close();
        verify((AutoCloseable) source, never()).close();
    }

    @Test
    void missingSchemaFailsAtStartupWithoutAttemptingDdlOrLeakingConnection() throws Exception {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("Missing schema"));
        var failure =
                assertThrows(
                        DurableException.class,
                        () -> PostgresBackend.builder().dataSource(source).build());
        assertEquals(DurableException.Code.INCOMPATIBLE, failure.code());
        verify(statement, never()).executeUpdate(anyString());
        verify(connection).close();
        verify((AutoCloseable) source, never()).close();
    }
}
