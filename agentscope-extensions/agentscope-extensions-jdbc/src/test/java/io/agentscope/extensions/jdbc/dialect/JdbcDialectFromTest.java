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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JdbcDialect#from(DataSource)} auto-detection logic.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcDialect.from() auto-detection")
class JdbcDialectFromTest {

    @Test
    @DisplayName("detects PostgreSQL")
    void detectsPostgres() throws Exception {
        DataSource ds = mockDataSource("PostgreSQL");
        assertInstanceOf(PostgresDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("detects MySQL")
    void detectsMysql() throws Exception {
        DataSource ds = mockDataSource("MySQL");
        assertInstanceOf(MysqlDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("detects MariaDB as MySQL")
    void detectsMariaDb() throws Exception {
        DataSource ds = mockDataSource("MariaDB");
        assertInstanceOf(MysqlDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("detects H2")
    void detectsH2() throws Exception {
        DataSource ds = mockDataSource("H2");
        assertInstanceOf(H2Dialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("detects SQLite")
    void detectsSqlite() throws Exception {
        DataSource ds = mockDataSource("SQLite");
        assertInstanceOf(SqliteDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("defaults to PostgresDialect for unknown database")
    void defaultsToPostgresForUnknown() throws Exception {
        DataSource ds = mockDataSource("Oracle");
        assertInstanceOf(PostgresDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("defaults to PostgresDialect when metadata is unavailable")
    void defaultsToPostgresOnSqlException() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection failed"));
        assertInstanceOf(PostgresDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("defaults to PostgresDialect when product name is blank")
    void defaultsToPostgresForBlankName() throws Exception {
        DataSource ds = mockDataSource("  ");
        assertInstanceOf(PostgresDialect.class, JdbcDialect.from(ds));
    }

    @Test
    @DisplayName("throws NullPointerException for null dataSource")
    void throwsForNullDataSource() {
        assertThrows(NullPointerException.class, () -> JdbcDialect.from(null));
    }

    /**
     * Creates a mock DataSource whose connection reports the given product name.
     */
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
