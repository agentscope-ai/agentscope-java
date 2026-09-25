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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect.BinaryCollationColumn;
import io.agentscope.extensions.jdbc.dialect.vendor.H2Dialect;
import io.agentscope.extensions.jdbc.dialect.vendor.MysqlDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.PostgresDialect;
import io.agentscope.extensions.jdbc.dialect.vendor.SqliteDialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AbstractJdbcDialect#verifyBinaryCollation()}.
 *
 * <p>{@code CREATE TABLE IF NOT EXISTS} is a no-op on an existing table, so a deployment whose key
 * columns still carry a case-insensitive collation keeps behaving as before and, without this check,
 * would stay silent about it. These tests drive the {@code INFORMATION_SCHEMA.COLUMNS} read with a
 * mocked catalog, so no MySQL server is required.
 *
 * @author shanhongyu
 */
@DisplayName("AbstractJdbcDialect binary-collation drift detection")
class AbstractJdbcDialectBinaryCollationTest {

    private static final String BINARY = "utf8mb4_bin";

    /**
     * The key columns {@link MysqlDialect} pins a binary collation on, in the order it declares them.
     * Listed here rather than read from the dialect because {@code binaryCollationColumns()} is
     * {@code protected} and this test lives outside the vendor package; {@code DialectSqlTests}
     * asserts the same set against the generated DDL, so a change to one has to touch both.
     */
    private static final List<String> MYSQL_KEY_COLUMNS =
            List.of("namespace_path", "item_key", "session_id", "state_key", "snapshot_id");

    @Test
    @DisplayName("reports only the columns that are not on the binary collation")
    void reportsOnlyDriftedColumns() {
        MysqlDialect dialect = mysqlDialectWithCollations(List.of("utf8mb4_unicode_ci", BINARY));

        List<BinaryCollationColumn> drifted = dialect.verifyBinaryCollation();

        assertEquals(1, drifted.size());
        // namespace_path is the first declared key column; item_key is the second and is migrated.
        assertEquals("namespace_path", drifted.get(0).column());
        assertEquals(dialect.storeTableName(), drifted.get(0).table());
    }

    @Test
    @DisplayName("reports every key column when none is migrated yet")
    void reportsEveryColumnWhenNoneMigrated() {
        MysqlDialect dialect =
                mysqlDialectWithCollations(
                        List.of(
                                "utf8mb4_unicode_ci",
                                "utf8mb4_unicode_ci",
                                "utf8mb4_0900_ai_ci",
                                "utf8mb4_unicode_ci",
                                "utf8mb4_unicode_ci"));

        List<BinaryCollationColumn> drifted = dialect.verifyBinaryCollation();

        assertEquals(
                List.of("namespace_path", "item_key", "session_id", "state_key", "snapshot_id"),
                drifted.stream().map(BinaryCollationColumn::column).toList());
    }

    @Test
    @DisplayName("reports nothing once every key column is migrated")
    void reportsNothingWhenFullyMigrated() {
        MysqlDialect dialect =
                mysqlDialectWithCollations(List.of(BINARY, BINARY, BINARY, BINARY, BINARY));

        assertTrue(dialect.verifyBinaryCollation().isEmpty());
    }

    @Test
    @DisplayName("reports nothing when the catalog returns no rows for the key columns")
    void reportsNothingWhenCatalogHasNoRows() {
        // The common fresh-install case: the DDL just created the columns correctly, so there is no
        // row to complain about, and a table that is not there yet reports nothing either.
        assertTrue(mysqlDialectWithCollations(List.of()).verifyBinaryCollation().isEmpty());
    }

    @Test
    @DisplayName("degrades to no report when the catalog cannot be read")
    void degradesWhenCatalogUnreadable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("no catalog"));

        MysqlDialect dialect = new MysqlDialect();
        dialect.bindDataSource(dataSource);

        // Assembling a store must not fail just because the collation probe could not run.
        assertTrue(dialect.verifyBinaryCollation().isEmpty());
    }

    @Test
    @DisplayName("dialects that compare keys case-sensitively read no catalog and report nothing")
    void dialectsWithoutDeclaredColumnsReadNoCatalog() {
        for (AbstractJdbcDialect each :
                List.of(new PostgresDialect(), new H2Dialect(), new SqliteDialect())) {
            // No DataSource is bound here: reporting nothing without touching the catalog is the
            // evidence that these dialects declare no key columns to check.
            assertTrue(each.verifyBinaryCollation().isEmpty());
        }
    }

    /**
     * Builds a MySQL dialect whose catalog reports {@code collations} for the declared key columns in
     * declaration order. An empty list simulates a catalog that returns no row.
     */
    private static MysqlDialect mysqlDialectWithCollations(final List<String> collations) {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(resultSet);
            stubCatalogRows(resultSet, collations);

            MysqlDialect dialect = new MysqlDialect();
            dialect.bindDataSource(dataSource);
            return dialect;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void stubCatalogRows(final ResultSet resultSet, final List<String> collations)
            throws SQLException {
        // One row per collation: an empty list means the catalog reports nothing, which is the
        // fresh-install / table-absent case.
        if (collations.isEmpty()) {
            when(resultSet.next()).thenReturn(false);
            return;
        }
        // findCollation() selects a single column and reads getString(1) once per row, so only that
        // accessor needs stubbing. Index-driven answers keep the row-to-value mapping explicit and
        // independent of how Mockito interprets a sequence of returned values.
        when(resultSet.next()).thenAnswer(rowPresence(collations.size()));
        when(resultSet.getString(1)).thenAnswer(valueAt(collations));
    }

    /** Returns {@code true} for the first {@code rows} invocations, then {@code false} forever. */
    private static org.mockito.stubbing.Answer<Boolean> rowPresence(final int rows) {
        int[] calls = {0};
        return invocation -> calls[0]++ < rows;
    }

    /** Returns the value for the current row, keyed by how many rows have been read so far. */
    private static org.mockito.stubbing.Answer<String> valueAt(final List<String> values) {
        int[] cursor = {0};
        return invocation -> cursor[0] < values.size() ? values.get(cursor[0]++) : null;
    }
}
