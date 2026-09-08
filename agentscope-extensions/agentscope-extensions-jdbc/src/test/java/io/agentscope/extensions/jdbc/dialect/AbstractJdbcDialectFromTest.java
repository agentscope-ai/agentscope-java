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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
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

    private static AbstractJdbcDialect detect(String productName) throws Exception {
        DataSource ds = mockDataSource(productName);
        return AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
    }

    private static DataSource mockDataSource(String productName) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDatabaseProductName()).thenReturn(productName);
        return ds;
    }
}
