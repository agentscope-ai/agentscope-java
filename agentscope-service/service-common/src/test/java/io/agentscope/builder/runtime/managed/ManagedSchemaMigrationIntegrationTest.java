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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.web.persistence.ServiceSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Migration owner and schema-validation reader against an exclusively owned temporary database. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedSchemaMigrationIntegrationTest {
    @Test
    void concurrentInitializationAndValidationFromAnotherSearchPathAreReadOnly() throws Exception {
        try (var database = new TemporaryDatabase();
                var owner = database.connect()) {
            CompletableFuture.allOf(migrate(database), migrate(database)).get(45, TimeUnit.SECONDS);
            try (var query = owner.createStatement();
                    var rows =
                            query.executeQuery(
                                    "SELECT to_regclass('ma.subagent_call') IS NOT NULL")) {
                assertTrue(rows.next());
                assertTrue(
                        rows.getBoolean(1),
                        "Production migration did not register child-call storage");
            }

            String role = "ma_reader_" + UUID.randomUUID().toString().replace("-", "");
            try {
                execute(owner, "CREATE ROLE " + role + " NOLOGIN");
                execute(owner, "GRANT USAGE ON SCHEMA ma TO " + role);
                execute(owner, "GRANT SELECT ON ma.builder_schema_migration TO " + role);
                try (var reader = database.connect()) {
                    execute(reader, "SET ROLE " + role);
                    reader.setSchema("public");
                    ServiceSchema.validate(reader, "managed");
                    assertThrows(
                            SQLException.class,
                            () ->
                                    execute(
                                            reader,
                                            "CREATE TABLE ma.startup_must_not_write(id INT)"));
                    assertThrows(
                            SQLException.class,
                            () ->
                                    execute(
                                            reader,
                                            "UPDATE ma.builder_schema_migration SET"
                                                    + " checksum='wrong'"));
                    try (var query = owner.createStatement();
                            var rows =
                                    query.executeQuery(
                                            "SELECT suspension_id,resume_id FROM ma.turn LIMIT"
                                                    + " 0")) {
                        assertEquals(2, rows.getMetaData().getColumnCount());
                    }
                    execute(owner, "UPDATE ma.builder_schema_migration SET checksum='changed'");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "managed"))
                                    .getMessage()
                                    .contains("checksum"));
                    execute(owner, "DELETE FROM ma.builder_schema_migration");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "managed"))
                                    .getMessage()
                                    .contains("Missing"));
                    execute(
                            owner,
                            "INSERT INTO ma.builder_schema_migration(component,version,checksum)"
                                    + " VALUES('managed','future.sql','future')");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "managed"))
                                    .getMessage()
                                    .contains("Unsupported"));
                }
            } finally {
                execute(owner, "DROP OWNED BY " + role);
                execute(owner, "DROP ROLE " + role);
            }
        }
    }

    @Test
    void unversionedTablesAndAlternateSchemaCannotBeSilentlyAdopted() throws Exception {
        try (var database = new TemporaryDatabase();
                var owner = database.connect()) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ServiceSchema.migrate(owner, "managed", "other"));
            execute(owner, "CREATE SCHEMA ma");
            execute(owner, "CREATE TABLE ma.previous_runtime(id INT)");
            execute(owner, "INSERT INTO ma.previous_runtime VALUES(7)");
            assertTrue(
                    assertThrows(
                                    IllegalStateException.class,
                                    () -> ServiceSchema.migrate(owner, "managed", "ma"))
                            .getMessage()
                            .contains("Unversioned"));
            try (var query = owner.createStatement();
                    var rows = query.executeQuery("SELECT id FROM ma.previous_runtime")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
        }
    }

    @Test
    void freshSchemaContainsCurrentTablesAndNoRetiredExecutionState() throws Exception {
        try (var database = new TemporaryDatabase();
                var owner = database.connect()) {
            ServiceSchema.migrate(owner, "managed", "ma");
            ServiceSchema.migrate(owner, "managed", "ma");
            ServiceSchema.validate(owner, "managed");
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(owner, true));
            assertEquals(
                    0,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema='ma'"
                                + " AND table_name IN ('runtime_commit','writer_seal','sandbox',"
                                + "'callback_delivery','callback_completion',"
                                + "'hands_tool_event_conflict')",
                            Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.columns WHERE"
                                    + " table_schema='ma' AND table_name='tool_task' AND"
                                    + " column_name='result_hash'",
                            Integer.class));
            assertEquals(
                    9,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM ma.builder_schema_migration", Integer.class));
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema='ma'"
                                    + " AND table_name='turn_ack_receipt'",
                            Integer.class));
            assertEquals(
                    6,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM information_schema.tables WHERE table_schema='ma'"
                                    + " AND table_name IN ('hands_sandbox','hands_resume_outbox',"
                                    + "'hands_execution_event','tool_event_conflict',"
                                    + "'turn_suspension','tool_command_outbox')",
                            Integer.class));
        }
    }

    @Test
    void publicationLeaseUpgradePreservesPendingAndConfirmedCommands() throws Exception {
        try (var database = new TemporaryDatabase();
                var owner = database.connect()) {
            ServiceSchema.migrate(owner, "managed", "ma");
            execute(owner, "DROP INDEX ma.ma_tool_command_publication_ready");
            execute(
                    owner,
                    "ALTER TABLE ma.tool_command_outbox DROP COLUMN publisher_token,"
                            + " DROP COLUMN publisher_lease_until,DROP COLUMN publish_attempt");
            execute(
                    owner,
                    "DELETE FROM ma.builder_schema_migration WHERE"
                            + " version='managed-tool-publication-lease.sql'");
            execute(
                    owner,
                    "INSERT INTO"
                        + " ma.tool_command_outbox(command_id,scope_key,task_id,kind,payload,broker_id)"
                        + " VALUES('pending','scope','task','execute','immutable-pending',NULL),"
                        + "('confirmed','scope','task','cancel','immutable-confirmed','original-broker')");
            ServiceSchema.migrate(owner, "managed", "ma");
            ServiceSchema.validate(owner, "managed");
            try (var query = owner.createStatement();
                    var rows =
                            query.executeQuery(
                                    "SELECT"
                                        + " command_id,payload,broker_id,publisher_token,publisher_lease_until,publish_attempt"
                                        + " FROM ma.tool_command_outbox ORDER BY command_id")) {
                assertTrue(rows.next());
                assertEquals("confirmed", rows.getString(1));
                assertEquals("immutable-confirmed", rows.getString(2));
                assertEquals("original-broker", rows.getString(3));
                assertEquals(null, rows.getString(4));
                assertEquals(0, rows.getLong(5));
                assertEquals(0, rows.getLong(6));
                assertTrue(rows.next());
                assertEquals("pending", rows.getString(1));
                assertEquals("immutable-pending", rows.getString(2));
                assertEquals(null, rows.getString(3));
                assertEquals(0, rows.getLong(6));
            }
        }
    }

    private static CompletableFuture<Void> migrate(TemporaryDatabase database) {
        return CompletableFuture.runAsync(
                () -> {
                    try (var connection = database.connect()) {
                        ServiceSchema.migrate(connection, "managed", "ma");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static final class TemporaryDatabase implements AutoCloseable {
        private final Connection administrator;
        private final String name =
                "ma_schema_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String url;

        TemporaryDatabase() throws SQLException {
            String base = System.getenv("MANAGED_DATABASE_TEST_URI");
            String[] parts = base.split("\\?", 2);
            url =
                    parts[0].substring(0, parts[0].lastIndexOf('/') + 1)
                            + name
                            + (parts.length == 2 ? "?" + parts[1] : "");
            administrator = DriverManager.getConnection(base, "managed", "managed-local-test");
            execute(administrator, "CREATE DATABASE " + name);
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(url, "managed", "managed-local-test");
        }

        @Override
        public void close() throws SQLException {
            try {
                execute(administrator, "DROP DATABASE " + name);
            } finally {
                administrator.close();
            }
        }
    }
}
