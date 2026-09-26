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
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assemble-time table schema validator: compares the columns a dialect's {@code CREATE
 * TABLE} DDL declares against the columns the database actually has.
 *
 * <p>The probe {@code SELECT * FROM <table> WHERE 1=0} is vendor-neutral and works with
 * DML-only permissions. Comparison is by column name only, case-insensitive (H2 stores
 * unquoted identifiers uppercase); extra columns and type differences are not errors — they
 * belong to the user's own migrations. Expected columns come from {@link
 * CreateTableDdlParser}; unparseable DDL skips validation with a debug log, because
 * validation is a safety net, not a gate.
 *
 * <p>Failures are self-contained and report everything at once: table name, missing columns,
 * actual column set, reference DDL, and a note that the framework never alters existing
 * tables.
 *
 * @author shanhongyu
 */
public final class TableSchemaValidator {

    private static final Logger LOG = LoggerFactory.getLogger(TableSchemaValidator.class);

    /** SQLSTATE values meaning "undefined table"; see {@link #isTableMissing} for vendors. */
    private static final Set<String> TABLE_MISSING_SQL_STATES =
            Set.of("42S02", "42P01", "42704", "42X05", "S0002");

    /**
     * Vendor error codes meaning "undefined table" — used where SQLSTATE is missing or
     * non-standard; see {@link #isTableMissing} for vendors.
     */
    private static final Set<Integer> TABLE_MISSING_ERROR_CODES =
            Set.of(1146, 942, 208, -204, -206, -5501, -2106, 42102, 42104);

    private TableSchemaValidator() {}

    /**
     * Validates one table's columns on an existing connection — the builder reuses its
     * assembly connection for all three tables.
     *
     * @param connection an open connection; not closed by this method
     * @param tableName the table to validate
     * @param createTableDdls the dialect's DDL statements for this table; the first parseable
     *     {@code CREATE TABLE} provides the expected columns
     * @throws IllegalStateException when the table is missing or lacks expected columns
     */
    public static void validate(
            Connection connection, String tableName, List<String> createTableDdls) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(createTableDdls, "createTableDdls");
        Set<String> expected = expectedColumns(createTableDdls);
        if (expected.isEmpty()) {
            LOG.debug(
                    "Skipping schema validation for '{}': no parseable CREATE TABLE DDL",
                    tableName);
            return;
        }
        Set<String> actual = actualColumns(connection, tableName, createTableDdls);

        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Table '"
                            + tableName
                            + "' is missing column(s) "
                            + missing
                            + " (actual columns: "
                            + actual
                            + "). The table already exists, so CREATE TABLE IF NOT EXISTS"
                            + " cannot add the missing columns — typically a legacy table"
                            + " adopted under the same name. The framework never alters"
                            + " existing tables; add the missing columns manually (e.g."
                            + " ALTER TABLE ... ADD COLUMN) based on the reference DDL:\n"
                            + referenceDdl(createTableDdls));
        }
    }

    /**
     * The column set of the first parseable {@code CREATE TABLE} in the list.
     *
     * @param createTableDdls the dialect's DDL statements for the table
     * @return expected column names, lower-cased; empty when no DDL parses
     */
    private static Set<String> expectedColumns(List<String> createTableDdls) {
        for (String ddl : createTableDdls) {
            Set<String> columns = CreateTableDdlParser.parseColumns(ddl);
            if (!columns.isEmpty()) {
                return columns;
            }
        }
        return Set.of();
    }

    /**
     * The table's actual columns from the probe's {@code ResultSetMetaData}, lower-cased.
     *
     * @param connection an open connection
     * @param tableName the table to probe
     * @param createTableDdls used only for the error path's reference DDL
     * @return actual column names, lower-cased
     */
    private static Set<String> actualColumns(
            Connection connection, String tableName, List<String> createTableDdls) {
        String probe = "SELECT * FROM " + tableName + " WHERE 1=0";
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(probe)) {
            ResultSetMetaData metaData = rs.getMetaData();
            Set<String> actual = new TreeSet<>();
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                String label = metaData.getColumnLabel(i);
                if (label != null) {
                    actual.add(label.toLowerCase(Locale.ROOT));
                }
            }
            return actual;
        } catch (SQLException e) {
            if (isTableMissing(e)) {
                throw new IllegalStateException(
                        "Table '"
                                + tableName
                                + "' does not exist. The framework only creates tables"
                                + " through AbstractJdbcDialect.from(dataSource).build()"
                                + " and never alters existing schemas — create the table"
                                + " manually using this reference DDL:\n"
                                + referenceDdl(createTableDdls));
            }
            throw new IllegalStateException(
                    "Failed to read columns of table '"
                            + tableName
                            + "' during schema"
                            + " validation",
                    e);
        }
    }

    /**
     * The DDL list joined into a copy-paste-ready reference.
     *
     * @param createTableDdls the dialect's DDL statements for the table
     * @return the statements joined with semicolons and newlines
     */
    private static String referenceDdl(List<String> createTableDdls) {
        return String.join(";\n", createTableDdls);
    }

    /**
     * Table-not-found detection, one database provider per line (codes verified against
     * each vendor's official error reference):
     *
     * <ul>
     *   <li>MySQL — SQLState 42S02, error code 1146 (ER_NO_SUCH_TABLE)</li>
     *   <li>MariaDB — MySQL lineage: 42S02 / 1146</li>
     *   <li>TiDB — MySQL-compatible: 42S02 / 1146</li>
     *   <li>OceanBase (MySQL mode) — MySQL-compatible: 42S02 / 1146</li>
     *   <li>OceanBase (Oracle mode) — mirrors Oracle: 942</li>
     *   <li>PostgreSQL — SQLState 42P01 (undefined_table); driver reports errorCode 0</li>
     *   <li>KingbaseES (人大金仓) — PG lineage: 42P01</li>
     *   <li>openGauss / GaussDB — PG lineage: 42P01</li>
     *   <li>PolarDB (PG-compatible) — PG lineage: 42P01</li>
     *   <li>Oracle — error code 942 (ORA-00942); SQLState is not standard</li>
     *   <li>SQL Server — SQLState S0002, error code 208 (invalid object name)</li>
     *   <li>DB2 (LUW / zOS / IBM i) — SQLState 42704, SQLCODE -204 (undefined name)</li>
     *   <li>H2 — error code 42104 (2.x) / 42102 (1.x); SQLState is not standard</li>
     *   <li>HSQLDB — error code -5501 (user lacks privilege or object not found)</li>
     *   <li>Derby / JavaDB — SQLState 42X05</li>
     *   <li>Firebird (3.0+) — SQLState 42S02</li>
     *   <li>DM (达梦) — error code -2106 (无效的表或视图名)</li>
     *   <li>Informix / GBase 8s — error code -206 (specified table not in database)</li>
     *   <li>SQLite (xerial) — null SQLState, message {@code "no such table: <name>"}</li>
     * </ul>
     *
     * <p>Deliberately not {@code instanceof SQLSyntaxErrorException}: that type covers the
     * whole SQLState class 42 — syntax and permission errors included, which must not be
     * misreported as a missing table — and pgjdbc never throws the JDBC 4 subtypes anyway.
     *
     * @param e the exception thrown by the probe
     * @return true when the error means the table does not exist
     */
    private static boolean isTableMissing(SQLException e) {
        if (TABLE_MISSING_SQL_STATES.contains(e.getSQLState())) {
            return true;
        }
        if (TABLE_MISSING_ERROR_CODES.contains(e.getErrorCode())) {
            return true;
        }
        return e.getSQLState() == null
                && e.getMessage() != null
                && e.getMessage().toLowerCase(Locale.ROOT).contains("no such table");
    }

    /**
     * Extracts the column-name set from a {@code CREATE TABLE} DDL — the DDL is the single
     * source of truth for expected columns, so no hand-maintained column list can drift from it.
     *
     * <p>Small by design: column names are all that is needed, and a few dozen lines of tokenizer
     * cover every in-tree vendor shape. The column list is split on depth-zero commas (commas in
     * {@code DECIMAL(10,2)} stay put); each segment's leading identifier is the column name,
     * except segments starting with a constraint keyword, which describe table constraints.
     * Quoted identifiers ({@code "col"}, {@code `col`}, {@code [col]}) are unquoted. Anything
     * not recognizable as {@code CREATE TABLE}, or any unidentifiable segment, yields an empty
     * set — validation is then skipped, because it is a safety net, not a gate, and must not
     * block unparseable third-party DDL.
     *
     * <p>Nested on purpose: the validator is its only production caller and the use has no
     * generalization in sight; package-private rather than private so the unit test can call
     * it directly.
     */
    static final class CreateTableDdlParser {

        /** Segment-leading keywords that mark a table constraint rather than a column. */
        private static final Set<String> CONSTRAINT_KEYWORDS =
                Set.of(
                        "PRIMARY",
                        "FOREIGN",
                        "UNIQUE",
                        "KEY",
                        "INDEX",
                        "CONSTRAINT",
                        "CHECK",
                        "EXCLUDE",
                        "FULLTEXT",
                        "SPATIAL");

        /**
         * {@code CREATE [TEMPORARY|TEMP|GLOBAL|LOCAL|UNLOGGED] TABLE ...} prefix. Modifiers between
         * {@code CREATE} and {@code TABLE} are optional so third-party dialects using them still
         * parse.
         */
        private static final Pattern CREATE_TABLE_PREFIX =
                Pattern.compile(
                        "\\s*CREATE\\s+((TEMPORARY|TEMP|GLOBAL|LOCAL|UNLOGGED)\\s+)*TABLE\\b",
                        Pattern.CASE_INSENSITIVE);

        /** Unquoted identifier: starts with a letter/underscore, continues with word characters. */
        private static final Pattern LEADING_IDENTIFIER =
                Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

        private CreateTableDdlParser() {}

        /**
         * Parses the column names declared by a {@code CREATE TABLE} DDL.
         *
         * @param ddl the DDL statement to parse
         * @return the lower-cased column names; empty when the statement is not a recognizable
         *     {@code CREATE TABLE} or cannot be parsed safely — callers skip validation then
         */
        static Set<String> parseColumns(String ddl) {
            if (ddl == null || ddl.isBlank()) {
                return Set.of();
            }
            String sql = stripComments(ddl);
            if (!CREATE_TABLE_PREFIX.matcher(sql).find()) {
                return Set.of();
            }
            int open = indexOfTopLevelParen(sql);
            if (open < 0) {
                return Set.of();
            }
            int close = indexOfMatchingParen(sql, open);
            if (close < 0) {
                return Set.of();
            }

            Set<String> columns = new LinkedHashSet<>();
            for (String segment : splitTopLevelCommas(sql, open + 1, close)) {
                String trimmed = segment.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Identifier name = leadingIdentifier(trimmed);
                if (name == null) {
                    // A segment we cannot identify means the DDL shape is outside our rules.
                    // Refuse to guess rather than report a wrong column set.
                    return Set.of();
                }
                if (name.quoted()
                        || !CONSTRAINT_KEYWORDS.contains(name.text().toUpperCase(Locale.ROOT))) {
                    columns.add(name.text().toLowerCase(Locale.ROOT));
                }
            }
            return Set.copyOf(columns);
        }

        /**
         * The leading identifier of a segment: either a quoted identifier or a bare word.
         *
         * @param segment a trimmed column or constraint segment
         * @return the identifier, or null when the segment starts with neither
         */
        private static Identifier leadingIdentifier(String segment) {
            char first = segment.charAt(0);
            if (first == '"' || first == '`' || first == '[') {
                char closing = first == '[' ? ']' : first;
                int end = segment.indexOf(closing, 1);
                if (end <= 1) {
                    return null;
                }
                return new Identifier(segment.substring(1, end), true);
            }
            Matcher matcher = LEADING_IDENTIFIER.matcher(segment);
            if (!matcher.find() || matcher.start() != 0) {
                return null;
            }
            return new Identifier(matcher.group(), false);
        }

        /** A segment's leading identifier; {@code quoted} marks a delimited identifier. */
        private record Identifier(String text, boolean quoted) {}

        // ------------------------------------------------------------------
        //  Scanner helpers — quote- and comment-aware
        // ------------------------------------------------------------------

        /**
         * Removes {@code --} line comments and {@code /* *}\/ block comments, preserving quotes.
         *
         * @param sql the raw DDL
         * @return the DDL with comments replaced by whitespace
         */
        private static String stripComments(String sql) {
            StringBuilder sb = new StringBuilder(sql.length());
            int i = 0;
            char quote = 0;
            while (i < sql.length()) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    sb.append(c);
                    if (c == quote) {
                        quote = 0;
                    }
                    i++;
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    sb.append(c);
                    i++;
                } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    while (i < sql.length() && sql.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                    i += 2;
                    while (i + 1 < sql.length()
                            && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                        i++;
                    }
                    i = Math.min(i + 2, sql.length());
                    sb.append(' ');
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        /**
         * First {@code (} outside quotes and string literals — the column-list opener.
         *
         * @param sql the comment-stripped DDL
         * @return the index of the opening parenthesis, or -1 when there is none
         */
        private static int indexOfTopLevelParen(String sql) {
            char quote = 0;
            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                } else if (c == '(') {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Index of the {@code )} matching the {@code (} at {@code open}, or -1 when unterminated.
         *
         * @param sql the comment-stripped DDL
         * @param open the index of the opening parenthesis
         * @return the index of the matching closing parenthesis, or -1
         */
        private static int indexOfMatchingParen(String sql, int open) {
            int depth = 0;
            char quote = 0;
            for (int i = open; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Splits {@code sql[from, to)} on commas at parenthesis depth zero outside quotes, keeping
         * commas nested in types like {@code DECIMAL(10,2)} intact inside their segment.
         *
         * @param sql the comment-stripped DDL
         * @param from the index just past the opening parenthesis
         * @param to the index of the closing parenthesis
         * @return the raw segments, in order
         */
        private static List<String> splitTopLevelCommas(String sql, int from, int to) {
            List<String> segments = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int depth = 0;
            char quote = 0;
            for (int i = from; i < to; i++) {
                char c = sql.charAt(i);
                if (quote != 0) {
                    current.append(c);
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '\'' || c == '"' || c == '`') {
                    quote = c;
                    current.append(c);
                } else if (c == '(') {
                    depth++;
                    current.append(c);
                } else if (c == ')') {
                    depth--;
                    current.append(c);
                } else if (c == ',' && depth == 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
            segments.add(current.toString());
            return segments;
        }
    }
}
