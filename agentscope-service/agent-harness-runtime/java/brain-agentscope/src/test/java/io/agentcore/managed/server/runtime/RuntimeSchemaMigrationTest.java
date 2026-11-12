/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.server.web.persistence.ServiceSchema;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MANAGED_AGENTS_TEST_POSTGRES_URL", matches = ".+")
class RuntimeSchemaMigrationTest {
    @Test
    void migrationIncludesExecutionContractAndUniqueBorrowedSandboxIdentity() throws Exception {
        String schema = "dp_schema_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection =
                DriverManager.getConnection(
                        System.getenv("MANAGED_AGENTS_TEST_POSTGRES_URL"),
                        System.getenv("MANAGED_AGENTS_TEST_POSTGRES_USER"),
                        System.getenv("MANAGED_AGENTS_TEST_POSTGRES_PASSWORD"))) {
            try {
                ServiceSchema.migrate(connection, "dataplane", schema);
                ServiceSchema.validate(connection, "dataplane");
                try (var query = connection.createStatement();
                        var rows =
                                query.executeQuery(
                                        "SELECT count(*) FROM pg_tables WHERE"
                                            + " schemaname=current_schema() AND tablename IN"
                                            + " ('builder_session_event_outbox','builder_session_execution_scope','builder_session_event_fence')")) {
                    assertTrue(rows.next());
                    assertEquals(
                            0,
                            rows.getInt(1),
                            "retired mirror tables survived the production migration entrypoint");
                }

                try (var query = connection.createStatement();
                        var rows =
                                query.executeQuery(
                                        "SELECT execution_contract_version"
                                                + " FROM builder_runtime_configuration LIMIT 0")) {
                    assertEquals("int4", rows.getMetaData().getColumnTypeName(1));
                }
                try (var query =
                        connection.prepareStatement(
                                "SELECT indexdef FROM pg_indexes WHERE schemaname=?"
                                        + " AND indexname='ix_runtime_borrowed_identity'")) {
                    query.setString(1, schema);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertTrue(rows.getString(1).startsWith("CREATE UNIQUE INDEX"));
                    }
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        }
    }
}
