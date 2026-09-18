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
package io.agentscope.extensions.postgresql.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class PostgresRemoteSnapshotClientTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private PreparedStatement preparedStatement;
    @Mock private ResultSet resultSet;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void constructorWithDefaultTableInitializesSchema() throws SQLException {
        new PostgresRemoteSnapshotClient(dataSource, true);
        verify(statement).execute(anyString());
    }

    @Test
    void constructorWithCustomTableInitializesSchema() throws SQLException {
        new PostgresRemoteSnapshotClient(dataSource, "custom_snapshots", true);
        verify(statement).execute(anyString());
    }

    @Test
    void constructorDoesNotInitializeSchemaWhenFalse() throws SQLException {
        new PostgresRemoteSnapshotClient(dataSource, false);
        verify(statement, never()).execute(anyString());
    }

    @Test
    void constructorWithNullTableNameUsesDefault() throws SQLException {
        PostgresRemoteSnapshotClient client =
                new PostgresRemoteSnapshotClient(dataSource, null, false);
        assertNotNull(client);
    }

    @Test
    void constructorRejectsNullDataSource() {
        assertThrows(
                NullPointerException.class, () -> new PostgresRemoteSnapshotClient(null, true));
    }

    @Test
    void uploadReadsStreamAndExecutesUpsert() throws Exception {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(preparedStatement.executeUpdate()).thenReturn(1);

        byte[] data = "snapshot-data".getBytes();
        client.upload("snap-1", new ByteArrayInputStream(data));

        verify(preparedStatement).setString(1, "snap-1");
        verify(preparedStatement).setBytes(2, data);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void uploadRejectsNullSnapshotId() {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        assertThrows(
                NullPointerException.class,
                () -> client.upload(null, new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void uploadRejectsNullData() {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        assertThrows(NullPointerException.class, () -> client.upload("snap-1", null));
    }

    @Test
    void downloadReturnsByteArrayInputStream() throws Exception {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        byte[] data = "downloaded".getBytes();
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBytes("data")).thenReturn(data);

        InputStream is = client.download("snap-1");
        assertArrayEquals(data, is.readAllBytes());
    }

    @Test
    void downloadThrowsWhenSnapshotMissing() throws Exception {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(resultSet.next()).thenReturn(false);

        assertThrows(FileNotFoundException.class, () -> client.download("snap-1"));
    }

    @Test
    void existsReturnsTrue() throws Exception {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(resultSet.next()).thenReturn(true);
        assertTrue(client.exists("snap-1"));
    }

    @Test
    void existsReturnsFalse() throws Exception {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(resultSet.next()).thenReturn(false);
        assertFalse(client.exists("snap-1"));
    }

    @Test
    void existsRejectsNullSnapshotId() {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        assertThrows(NullPointerException.class, () -> client.exists(null));
    }

    @Test
    void initSchemaFailureIsLogged() throws SQLException {
        when(connection.createStatement()).thenThrow(new SQLException("boom"));
        assertNotNull(new PostgresRemoteSnapshotClient(dataSource, true));
    }

    @Test
    void snapshotSpecWithCustomTableName() throws SQLException {
        RemoteSnapshotSpec spec = new PostgresSnapshotSpec(dataSource, "custom_snapshots");
        assertNotNull(spec);
    }

    @Test
    void uploadThrowsWhenSqlFails() throws SQLException {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(
                SQLException.class,
                () -> client.upload("snap-1", new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void downloadThrowsWhenSqlFails() throws SQLException {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(SQLException.class, () -> client.download("snap-1"));
    }

    @Test
    void existsThrowsWhenSqlFails() throws SQLException {
        PostgresRemoteSnapshotClient client = new PostgresRemoteSnapshotClient(dataSource, false);
        when(dataSource.getConnection()).thenThrow(new SQLException("boom"));
        assertThrows(SQLException.class, () -> client.exists("snap-1"));
    }
}
