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
package io.agentscope.extensions.jdbc.dialect;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AbstractJdbcDialect#from(DataSource)} SPI-based auto-detection.
 *
 * @author shanhongyu
 */
@DisplayName("AbstractJdbcDialect.from() SPI auto-detection")
class AbstractJdbcDialectFromTest {

    @Test
    @DisplayName("detects PostgreSQL")
    void detectsPostgres() throws Exception {
        assertInstanceOf(PostgresDialect.class, detect("PostgreSQL"));
    }

    @Test
    @DisplayName("detects MySQL")
    void detectsMysql() throws Exception {
        assertInstanceOf(MysqlDialect.class, detect("MySQL"));
    }

    @Test
    @DisplayName("detects MariaDB as MysqlDialect")
    void detectsMariaDb() throws Exception {
        assertInstanceOf(MysqlDialect.class, detect("MariaDB"));
    }

    @Test
    @DisplayName("detects H2")
    void detectsH2() throws Exception {
        assertInstanceOf(H2Dialect.class, detect("H2"));
    }

    @Test
    @DisplayName("detects SQLite")
    void detectsSqlite() throws Exception {
        assertInstanceOf(SqliteDialect.class, detect("SQLite"));
    }

    @Test
    @DisplayName("throws IllegalStateException for unsupported database")
    void throwsForUnsupported() throws Exception {
        DataSource ds = mockDataSource("Oracle");
        assertThrows(
                IllegalStateException.class,
                () -> AbstractJdbcDialect.from(ds).autoCreateTable(false).build());
    }

    @Test
    @DisplayName("throws IllegalStateException on connection failure")
    void throwsOnConnectionFailure() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection failed"));
        assertThrows(
                IllegalStateException.class,
                () -> AbstractJdbcDialect.from(ds).autoCreateTable(false).build());
    }

    @Test
    @DisplayName("throws IllegalArgumentException for null dataSource")
    void throwsForNullDataSource() {
        assertThrows(IllegalArgumentException.class, () -> AbstractJdbcDialect.from(null));
    }

    /**
     * Builds a dialect against a mocked database reporting {@code productName}.
     *
     * @param productName the JDBC product name to report
     * @return the detected dialect
     */
    private static AbstractJdbcDialect detect(String productName) throws Exception {
        DataSource ds = mockDataSource(productName);
        return AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
    }

    /**
     * Column sets the three agentscope tables report to build()'s schema validation. Lowercase
     * is fine — the comparison is case-insensitive.
     */
    private static final Map<String, List<String>> VALIDATION_COLUMNS =
            Map.of(
                    "agentscope_store",
                            List.of(
                                    "namespace_path",
                                    "item_key",
                                    "value_json",
                                    "version",
                                    "updated_at"),
                    "agentscope_sessions",
                            List.of(
                                    "session_id",
                                    "state_key",
                                    "item_index",
                                    "state_data",
                                    "version",
                                    "created_at",
                                    "updated_at"),
                    "agentscope_snapshots", List.of("snapshot_id", "data", "created_at"));

    /**
     * A mocked DataSource reporting {@code productName} and answering validation probes.
     *
     * @param productName the JDBC product name to report
     * @return the mocked DataSource
     */
    private static DataSource mockDataSource(String productName) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn(productName);
        // build() validates all three tables even with autoCreateTable(false); answer every
        // "SELECT * FROM <t> WHERE 1=0" probe with that table's full column metadata.
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString()))
                .thenAnswer(invocation -> mockColumns(invocation.getArgument(0, String.class)));
        return ds;
    }

    /**
     * A ResultSet mock whose metadata reports the probed table's full column set.
     *
     * @param probeSql the validation probe, {@code SELECT * FROM <t> WHERE 1=0}
     * @return the mocked ResultSet
     */
    private static ResultSet mockColumns(String probeSql) throws SQLException {
        String table = probeSql.substring("SELECT * FROM ".length(), probeSql.indexOf(" WHERE"));
        List<String> columns = VALIDATION_COLUMNS.get(table);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            when(md.getColumnLabel(i + 1)).thenReturn(columns.get(i));
        }
        ResultSet rs = mock(ResultSet.class);
        when(rs.getMetaData()).thenReturn(md);
        return rs;
    }
}
