/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.harness.sandbox.data;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite helpers for the sandbox Data Agent — same tool names and behaviour as {@code
 * io.agentscope.harness.example.SqliteTool} in {@code agentscope-examples/harness-example}.
 *
 * <p>Tools: {@code sql_list_tables}, {@code sql_get_schema}, {@code sql_execute_query}.
 */
public class SqliteTool {

    private static final Logger log = LoggerFactory.getLogger(SqliteTool.class);

    private static final int MAX_ROWS = 50;

    private static final int SAMPLE_ROWS = 3;

    private final String jdbcUrl;

    public SqliteTool(Path dbPath) {
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    @Tool(
            name = "sql_list_tables",
            description =
                    "Lists all tables in the SQLite database. Use this first to discover what data"
                            + " is available before writing queries.")
    public String listTables() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            DatabaseMetaData meta = conn.getMetaData();
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            if (tables.isEmpty()) {
                return "No tables found in the database.";
            }
            return "Tables (" + tables.size() + "):\n" + String.join("\n", tables);
        } catch (SQLException e) {
            log.warn("sql_list_tables failed", e);
            return "Error listing tables: " + e.getMessage();
        }
    }

    @Tool(
            name = "sql_get_schema",
            description =
                    "Returns the schema (column names, types, keys) and sample rows for one or more"
                            + " tables. Pass a comma-separated list of table names to inspect"
                            + " multiple tables at once.")
    public String getSchema(
            @ToolParam(
                            name = "tables",
                            description =
                                    "Comma-separated table names, e.g. \"Artist,Album,Track\"")
                    String tables) {
        StringBuilder sb = new StringBuilder();
        for (String table : tables.split(",")) {
            table = table.strip();
            if (table.isEmpty()) continue;
            sb.append(describeTable(table)).append("\n\n");
        }
        return sb.toString().strip();
    }

    @Tool(
            name = "sql_execute_query",
            description =
                    "Executes a read-only SELECT query against the SQLite database and returns the"
                            + " results as a formatted table. Never use DML statements"
                            + " (INSERT / UPDATE / DELETE / DROP).")
    public String executeQuery(
            @ToolParam(name = "query", description = "A valid SQLite SELECT statement")
                    String query) {
        String trimmed = query.strip();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            return "Error: only SELECT statements are allowed. Received: " + trimmed;
        }
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
                Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(MAX_ROWS);
            try (ResultSet rs = stmt.executeQuery(trimmed)) {
                return formatResultSet(rs);
            }
        } catch (SQLException e) {
            log.warn("sql_execute_query failed for: {}", trimmed, e);
            return "Error executing query: " + e.getMessage();
        }
    }

    private String describeTable(String tableName) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(tableName).append("\n\n");

        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            DatabaseMetaData meta = conn.getMetaData();

            List<String> pks = new ArrayList<>();
            try (ResultSet rs = meta.getPrimaryKeys(null, null, tableName)) {
                while (rs.next()) {
                    pks.add(rs.getString("COLUMN_NAME"));
                }
            }

            List<String> fks = new ArrayList<>();
            try (ResultSet rs = meta.getImportedKeys(null, null, tableName)) {
                while (rs.next()) {
                    fks.add(
                            rs.getString("FKCOLUMN_NAME")
                                    + " → "
                                    + rs.getString("PKTABLE_NAME")
                                    + "."
                                    + rs.getString("PKCOLUMN_NAME"));
                }
            }

            sb.append("### Columns\n");
            try (ResultSet rs = meta.getColumns(null, null, tableName, "%")) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    String type = rs.getString("TYPE_NAME");
                    String nullable = "YES".equals(rs.getString("IS_NULLABLE")) ? "" : " NOT NULL";
                    String pk = pks.contains(col) ? " [PK]" : "";
                    sb.append("- ")
                            .append(col)
                            .append(" (")
                            .append(type)
                            .append(nullable)
                            .append(pk)
                            .append(")\n");
                }
            }

            if (!fks.isEmpty()) {
                sb.append("\n### Foreign Keys\n");
                fks.forEach(fk -> sb.append("- ").append(fk).append("\n"));
            }

            sb.append("\n### Sample Data (").append(SAMPLE_ROWS).append(" rows)\n");
            try (Statement stmt = conn.createStatement()) {
                stmt.setMaxRows(SAMPLE_ROWS);
                try (ResultSet rs =
                        stmt.executeQuery("SELECT * FROM " + tableName + " LIMIT " + SAMPLE_ROWS)) {
                    sb.append(formatResultSet(rs));
                }
            }

        } catch (SQLException e) {
            sb.append("Error describing table '")
                    .append(tableName)
                    .append("': ")
                    .append(e.getMessage());
        }
        return sb.toString();
    }

    private static String formatResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();

        StringJoiner header = new StringJoiner(" | ");
        for (int i = 1; i <= cols; i++) {
            header.add(meta.getColumnName(i));
        }

        String separator = "-".repeat(header.toString().length());

        List<String> rows = new ArrayList<>();
        int count = 0;
        while (rs.next()) {
            StringJoiner row = new StringJoiner(" | ");
            for (int i = 1; i <= cols; i++) {
                Object val = rs.getObject(i);
                row.add(val == null ? "NULL" : val.toString());
            }
            rows.add(row.toString());
            count++;
        }

        if (rows.isEmpty()) {
            return "(no rows returned)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(header).append("\n").append(separator).append("\n");
        rows.forEach(r -> sb.append(r).append("\n"));
        if (count >= MAX_ROWS) {
            sb.append("... (result truncated at ").append(MAX_ROWS).append(" rows)\n");
        }
        return sb.toString();
    }
}
