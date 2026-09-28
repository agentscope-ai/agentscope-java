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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import javax.sql.DataSource;

/**
 * Builder for {@link AbstractJdbcDialect} — chainable configuration then {@link #build()}.
 *
 * <p>{@code build()} performs three steps: detect DB type via SPI → assemble table names →
 * initialize and validate the schema of all three tables in one connection; schema work
 * happens here and only here. With {@code autoCreateTable = true} (default) it executes
 * {@link AbstractJdbcDialect#createTableDdls()} first ({@code IF NOT EXISTS} is a no-op on
 * existing tables) and validates columns afterwards, catching a pre-existing table silently
 * adopted under the same name; with {@code false} it runs no DDL and goes straight to the
 * same validation. Either way, on normal return the three tables carry exactly the declared
 * columns and no component constructor touches the schema afterwards.
 *
 * <p>Detection uses JDK {@link ServiceLoader} to discover all {@link AbstractJdbcDialect}
 * implementations on the classpath. Candidates are sorted by
 * {@link AbstractJdbcDialect#getOrder()} (ascending); ties are broken by inheritance depth
 * (subclass before parent). The first candidate whose
 * {@link AbstractJdbcDialect#supports(DatabaseMetaData)} returns {@code true} wins.
 *
 * <p>Third-party extensions: place a
 * {@code META-INF/services/io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect} file
 * in your jar listing the dialect class name. Downstream projects add the dependency and
 * the dialect is auto-discovered — zero code changes.
 *
 * @author shanhongyu
 */
public class AbstractJdbcDialectBuilder {

    private final DataSource dataSource;
    private String tablePrefix = "agentscope_";
    private String storeTableName;
    private String sessionStateTableName;
    private String snapshotTableName;
    private boolean autoCreateTable = true;

    AbstractJdbcDialectBuilder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Sets the unified table prefix (default {@code agentscope_}). */
    public AbstractJdbcDialectBuilder tablePrefix(String tablePrefix) {
        this.tablePrefix = validateIdentifier(tablePrefix, "tablePrefix");
        return this;
    }

    /** Overrides the full KV store table name (takes priority over prefix + base). */
    public AbstractJdbcDialectBuilder storeTableName(String name) {
        this.storeTableName = validateIdentifier(name, "storeTableName");
        return this;
    }

    /** Overrides the full session-state table name. */
    public AbstractJdbcDialectBuilder sessionStateTableName(String name) {
        this.sessionStateTableName = validateIdentifier(name, "sessionStateTableName");
        return this;
    }

    /** Overrides the full snapshots table name. */
    public AbstractJdbcDialectBuilder snapshotTableName(String name) {
        this.snapshotTableName = validateIdentifier(name, "snapshotTableName");
        return this;
    }

    /**
     * Whether to auto-create tables during {@link #build()} (default true). Either way,
     * all three tables are validated afterwards: {@code true} executes the idempotent DDL
     * first; {@code false} runs no DDL, so a missing table or column fails assembly with
     * the reference DDL in the error message.
     */
    public AbstractJdbcDialectBuilder autoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
        return this;
    }

    /** Detects the dialect, assembles table names, then creates and validates the schema. */
    public AbstractJdbcDialect build() {
        AbstractJdbcDialect dialect = detectDialect();
        dialect.tablePrefix(this.tablePrefix);
        if (this.storeTableName != null) {
            dialect.storeTableName(this.storeTableName);
        }
        if (this.sessionStateTableName != null) {
            dialect.sessionStateTableName(this.sessionStateTableName);
        }
        if (this.snapshotTableName != null) {
            dialect.snapshotTableName(this.snapshotTableName);
        }
        dialect.bindDataSource(this.dataSource);
        initializeAndValidateSchema(dialect);
        return dialect;
    }

    /**
     * SPI detection: the first candidate whose {@code supports()} accepts the database wins.
     *
     * @return the matching dialect instance
     */
    private AbstractJdbcDialect detectDialect() {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            List<AbstractJdbcDialect> candidates = new ArrayList<>();
            ServiceLoader.load(AbstractJdbcDialect.class).forEach(candidates::add);
            candidates.sort(
                    (a, b) -> {
                        int byOrder = Integer.compare(a.getOrder(), b.getOrder());
                        if (byOrder != 0) {
                            return byOrder;
                        }
                        return Integer.compare(
                                dialectDepth(b.getClass()), dialectDepth(a.getClass()));
                    });
            for (AbstractJdbcDialect candidate : candidates) {
                if (candidate.supports(metaData)) {
                    return candidate;
                }
            }
            throw new IllegalStateException(
                    "No JDBC dialect found for database '"
                            + metaData.getDatabaseProductName()
                            + "'; verify the dialect jar and META-INF/services/"
                            + AbstractJdbcDialect.class.getName()
                            + " registration");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to obtain JDBC connection or metadata for dialect detection", e);
        }
    }

    /**
     * Runs all schema work on one connection: optional DDL execution, then column validation
     * of each table against its own DDLs — both from {@link
     * AbstractJdbcDialect#createTableDdls()}, the single source pairing tables with their
     * statements.
     *
     * @param dialect the assembled dialect
     * @throws IllegalStateException when a table is missing or lacks declared columns
     */
    private void initializeAndValidateSchema(AbstractJdbcDialect dialect) {
        Map<String, List<String>> ddlsByTable = dialect.createTableDdls();
        try (Connection conn = dataSource.getConnection()) {
            createTablesIfNeeded(conn, ddlsByTable);
            for (Map.Entry<String, List<String>> table : ddlsByTable.entrySet()) {
                TableSchemaValidator.validate(conn, table.getKey(), table.getValue());
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to obtain a connection for schema initialization during dialect"
                            + " assembly",
                    e);
        }
    }

    /**
     * Executes every CREATE DDL on {@code conn}; a no-op unless {@code autoCreateTable} is set.
     *
     * @param conn the assembly connection
     * @param ddlsByTable each table's DDL statements, keyed by resolved table name
     */
    private void createTablesIfNeeded(Connection conn, Map<String, List<String>> ddlsByTable) {
        if (!this.autoCreateTable) {
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            for (List<String> ddls : ddlsByTable.values()) {
                for (String ddl : ddls) {
                    stmt.execute(ddl);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Auto-create table(s) failed during dialect assembly: " + e.getMessage(), e);
        }
    }

    /**
     * Inheritance depth relative to {@link AbstractJdbcDialect} (direct subclass = 1).
     *
     * @param clazz the dialect class
     * @return the number of classes between {@code clazz} and {@code AbstractJdbcDialect}
     */
    private static int dialectDepth(Class<?> clazz) {
        int depth = 0;
        for (Class<?> c = clazz;
                c != AbstractJdbcDialect.class && c != null;
                c = c.getSuperclass()) {
            depth++;
        }
        return depth;
    }

    /**
     * Validates that an identifier is safe to interpolate into SQL strings. Table names and
     * prefixes cannot be parameterised in prepared statements, so this regex is the only
     * injection guard. Accepts {@code [A-Za-z_][A-Za-z0-9_]*} — no hyphens, spaces, or
     * special characters.
     *
     * @param identifier the caller-supplied identifier
     * @param paramName the identifier's parameter name, for the error message
     * @return the validated identifier
     */
    private static String validateIdentifier(String identifier, String paramName) {
        if (identifier == null
                || identifier.isBlank()
                || !TableSchemaValidator.VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    paramName + " must match [A-Za-z_][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }
}
