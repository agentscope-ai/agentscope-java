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

import io.agentscope.extensions.jdbc.H2TestSupport;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * H2 tests for the advisory column-width check of {@link TableSchemaValidator}.
 *
 * <p>Background: {@code agentscope_store.item_key} was declared {@code VARCHAR(255)} while the
 * keys written through {@code JdbcStore} (session journal paths built from base64-encoded ids)
 * reach roughly 260 characters. {@code CREATE TABLE IF NOT EXISTS} cannot widen a column, so a
 * deployment created before the DDL fix keeps the narrow column and every first write fails with a
 * vendor length error. The width check turns that into one start-up warning.
 *
 * @author john
 */
@DisplayName("TableSchemaValidator column-width advisories (H2)")
class TableSchemaValidatorNarrowColumnTest {

    private static final String STORE_DDL_WIDE =
            "CREATE TABLE agentscope_store ("
                    + "  namespace_path VARCHAR(2048) NOT NULL,"
                    + "  item_key       VARCHAR(2048) NOT NULL,"
                    + "  value_json     CLOB          NOT NULL,"
                    + "  version        BIGINT        NOT NULL,"
                    + "  updated_at     BIGINT        NOT NULL,"
                    + "  PRIMARY KEY (namespace_path, item_key)"
                    + ")";

    @Test
    @DisplayName("a legacy VARCHAR(255) item_key produces one actionable warning")
    void narrowItemKeyIsReported() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("narrow_item_key");
        createTable(
                ds,
                "CREATE TABLE agentscope_store ("
                        + "  namespace_path VARCHAR(2048) NOT NULL,"
                        + "  item_key       VARCHAR(255)  NOT NULL,"
                        + "  value_json     CLOB          NOT NULL,"
                        + "  version        BIGINT        NOT NULL,"
                        + "  updated_at     BIGINT        NOT NULL,"
                        + "  PRIMARY KEY (namespace_path, item_key)"
                        + ")");

        try (Connection conn = ds.getConnection()) {
            List<String> warnings =
                    TableSchemaValidator.narrowColumnWarnings(
                            conn, "agentscope_store", List.of(STORE_DDL_WIDE));

            assertEquals(1, warnings.size(), () -> "expected one warning, got " + warnings);
            String warning = warnings.get(0);
            assertTrue(warning.contains("item_key"), warning);
            assertTrue(warning.contains("VARCHAR(255)"), warning);
            assertTrue(warning.contains("VARCHAR(2048)"), warning);
            assertTrue(warning.contains("ALTER TABLE agentscope_store"), warning);
        }
    }

    @Test
    @DisplayName("a table that already matches the declared width produces no warning")
    void matchingWidthIsSilent() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("wide_item_key");
        createTable(ds, STORE_DDL_WIDE);

        try (Connection conn = ds.getConnection()) {
            assertTrue(
                    TableSchemaValidator.narrowColumnWarnings(
                                    conn, "agentscope_store", List.of(STORE_DDL_WIDE))
                            .isEmpty());
        }
    }

    @Test
    @DisplayName("columns without a declared character width are ignored")
    void unboundedColumnsAreIgnored() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("unbounded_columns");
        createTable(ds, "CREATE TABLE logs (id BIGINT NOT NULL, body CLOB NOT NULL)");

        try (Connection conn = ds.getConnection()) {
            assertTrue(
                    TableSchemaValidator.narrowColumnWarnings(
                                    conn,
                                    "logs",
                                    List.of(
                                            "CREATE TABLE logs (id BIGINT NOT NULL, body CLOB NOT"
                                                    + " NULL)"))
                            .isEmpty());
        }
    }

    @Test
    @DisplayName("a missing table does not fail the advisory check")
    void missingTableIsSkipped() throws Exception {
        DataSource ds = H2TestSupport.createDataSource("missing_table");

        try (Connection conn = ds.getConnection()) {
            assertTrue(
                    TableSchemaValidator.narrowColumnWarnings(
                                    conn, "agentscope_store", List.of(STORE_DDL_WIDE))
                            .isEmpty());
        }
    }

    private static void createTable(DataSource ds, String ddl) throws Exception {
        try (Connection conn = ds.getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }
}
