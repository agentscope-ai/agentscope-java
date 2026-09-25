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
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * Builder for {@link AbstractJdbcDialect} — chainable configuration then {@link #build()}.
 *
 * <p>{@code build()} performs three steps: detect DB type via SPI → assemble table
 * names → optionally auto-create tables via {@link AbstractJdbcDialect#createTableDdls()}.
 * The returned dialect instance does not hold a connection reference.
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

    /**
     * Valid SQL identifier pattern — table names and prefixes flow into SQL strings verbatim
     * via string concatenation (no parameterisation possible for DDL identifiers), so this
     * regex is the SQL-injection guard. Same pattern as the deprecated MySQL module's
     * {@code JdbcStore.VALID_TABLE_NAME}.
     */
    private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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

    /** Whether to auto-create tables during {@link #build()} (default true). */
    public AbstractJdbcDialectBuilder autoCreateTable(boolean autoCreateTable) {
        this.autoCreateTable = autoCreateTable;
        return this;
    }

    /** Detects the dialect, assembles table names, optionally creates tables. */
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
        createTablesIfNeeded(dialect);
        return dialect;
    }

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

    private void createTablesIfNeeded(AbstractJdbcDialect dialect) {
        if (!this.autoCreateTable) {
            return;
        }
        List<String> ddls = dialect.createTableDdls();
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {
            for (String ddl : ddls) {
                stmt.execute(ddl);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Auto-create table(s) failed during dialect assembly: " + e.getMessage(), e);
        }
    }

    /** Inheritance depth relative to {@link AbstractJdbcDialect} (direct subclass = 1). */
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
     */
    private static String validateIdentifier(String identifier, String paramName) {
        if (identifier == null
                || identifier.isBlank()
                || !VALID_IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                    paramName + " must match [A-Za-z_][A-Za-z0-9_]*, got: " + identifier);
        }
        return identifier;
    }
}
