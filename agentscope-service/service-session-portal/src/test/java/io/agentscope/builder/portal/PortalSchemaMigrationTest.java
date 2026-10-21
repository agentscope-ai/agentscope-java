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
package io.agentscope.builder.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.web.persistence.ServiceSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "AGENTSCOPE_TEST_POSTGRES_URL", matches = ".+")
class PortalSchemaMigrationTest {
    private final String url = System.getenv("AGENTSCOPE_TEST_POSTGRES_URL");
    private final String user = System.getenv("AGENTSCOPE_TEST_POSTGRES_USER");
    private final String password = System.getenv("AGENTSCOPE_TEST_POSTGRES_PASSWORD");

    @Test
    void concurrentMigrationAndReadOnlyValidation() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "portal_schema_" + suffix;
        String role = "portal_reader_" + suffix;
        try (Connection owner = DriverManager.getConnection(url, user, password)) {
            try {
                CompletableFuture<Void> one = migrate(schema);
                CompletableFuture<Void> two = migrate(schema);
                CompletableFuture.allOf(one, two).get(45, TimeUnit.SECONDS);
                owner.setSchema(schema);
                try (Statement query = owner.createStatement();
                        ResultSet rows =
                                query.executeQuery(
                                        "SELECT title, metadata_json, creation_request_digest"
                                                + " FROM builder_portal_session LIMIT 0")) {
                    assertEquals(3, rows.getMetaData().getColumnCount());
                    assertEquals(64, rows.getMetaData().getPrecision(3));
                }
                for (String select :
                        new String[] {
                            "SELECT pending_event FROM builder_portal_webhook LIMIT 0",
                            "SELECT event_id FROM builder_portal_webhook_delivery LIMIT 0"
                        }) {
                    try (Statement query = owner.createStatement();
                            ResultSet rows = query.executeQuery(select)) {
                        assertEquals(256, rows.getMetaData().getPrecision(1));
                    }
                }
                execute(owner, "CREATE ROLE " + role + " NOLOGIN");
                execute(owner, "GRANT USAGE ON SCHEMA " + schema + " TO " + role);
                execute(owner, "GRANT SELECT ON builder_schema_migration TO " + role);
                try (Connection reader = DriverManager.getConnection(url, user, password)) {
                    execute(reader, "SET ROLE " + role);
                    reader.setSchema(schema);
                    try (Statement query = reader.createStatement();
                            ResultSet rows = query.executeQuery("SELECT current_user")) {
                        assertTrue(rows.next());
                        assertEquals(role, rows.getString(1));
                    }
                    ServiceSchema.validate(reader, "portal");
                    assertThrows(
                            SQLException.class,
                            () -> execute(reader, "CREATE TABLE startup_must_not_write(id INT)"));
                    execute(owner, "UPDATE builder_schema_migration SET checksum='changed'");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "portal"))
                                    .getMessage()
                                    .contains("checksum"));
                    execute(owner, "DELETE FROM builder_schema_migration");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "portal"))
                                    .getMessage()
                                    .contains("Missing"));
                    execute(
                            owner,
                            "INSERT INTO builder_schema_migration(component,version,checksum)"
                                    + " VALUES('portal','V9999_future.sql','future')");
                    assertTrue(
                            assertThrows(
                                            IllegalStateException.class,
                                            () -> ServiceSchema.validate(reader, "portal"))
                                    .getMessage()
                                    .contains("Unsupported"));
                }
            } finally {
                execute(owner, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                execute(owner, "DROP ROLE IF EXISTS " + role);
            }
        }
    }

    private CompletableFuture<Void> migrate(String schema) {
        return CompletableFuture.runAsync(
                () -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        ServiceSchema.migrate(connection, "portal", schema);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
