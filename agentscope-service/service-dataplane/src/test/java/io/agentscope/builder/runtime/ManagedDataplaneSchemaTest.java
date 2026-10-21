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
package io.agentscope.builder.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.web.persistence.ServiceSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real PostgreSQL initializer must not recreate the retired Managed execution database. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
@Timeout(45)
class ManagedDataplaneSchemaTest {
    @Test
    void freshDataplaneHasNoLegacyExecutionTablesAndRejectsTheOldMigrationSet() throws Exception {
        String original = System.getenv("MANAGED_DATABASE_TEST_URI");
        String database = "managed_dp_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = original.substring(0, original.lastIndexOf('/') + 1);
        String user = System.getenv().getOrDefault("MANAGED_DATABASE_TEST_USER", "managed");
        String password =
                System.getenv()
                        .getOrDefault("MANAGED_DATABASE_TEST_PASSWORD", "managed-local-test");
        try (var admin = DriverManager.getConnection(prefix + "postgres", user, password)) {
            execute(admin, "CREATE DATABASE " + database);
            try (var fresh = DriverManager.getConnection(prefix + database, user, password)) {
                ServiceSchema.migrate(fresh, "dataplane", "dp");
                ServiceSchema.validate(fresh, "dataplane");
                try (var query = fresh.createStatement();
                        var rows =
                                query.executeQuery(
                                        "SELECT count(*) FROM information_schema.tables WHERE"
                                            + " table_schema='dp' AND (table_name LIKE"
                                            + " 'builder_runtime_%' OR table_name LIKE"
                                            + " 'builder_coord_%' OR table_name IN"
                                            + " ('builder_managed_runtime_session',"
                                            + "'builder_session_turn_command','builder_session_action_command','builder_session_export_source',"
                                            + "'builder_session_event_outbox','builder_session_execution_scope','builder_session_event_fence'))")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
                try (var query = fresh.createStatement();
                        var rows =
                                query.executeQuery(
                                        "SELECT to_regclass('dp.builder_agent_state') IS NOT NULL,"
                                                + " to_regclass('dp.builder_session_event') IS NOT"
                                                + " NULL")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getBoolean(1));
                    assertTrue(rows.getBoolean(2));
                }
                execute(
                        fresh,
                        "INSERT INTO dp.builder_schema_migration(component,version,checksum) VALUES"
                                + " ('dataplane','V0002__session_budgets.sql','old-runtime')");
                assertThrows(
                        IllegalStateException.class,
                        () -> ServiceSchema.validate(fresh, "dataplane"));
            } finally {
                execute(admin, "DROP DATABASE " + database + " WITH (FORCE)");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
