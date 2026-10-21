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
package io.agentscope.builder.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DataPlaneConfigTest {
    @Test
    void baseStoreValidatesAndUsesTablesFromTheDataplaneMigration() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        try (var connection = dataSource.getConnection()) {
            try (var migration =
                    getClass()
                            .getResourceAsStream("/db/migration/dataplane/V0001__dataplane.sql")) {
                assertNotNull(migration);
                String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
                try (var statement = connection.createStatement()) {
                    for (String ddl : sql.split(";")) {
                        String trimmed = ddl.strip();
                        if (trimmed.startsWith("CREATE TABLE IF NOT EXISTS agentscope_")
                                || trimmed.startsWith("CREATE INDEX IF NOT EXISTS agentscope_")) {
                            statement.execute(trimmed);
                        }
                    }
                }
            }
            var store = new DataPlaneConfig().baseStore(dataSource, false);
            var namespace = List.of("deployment-regression", "session");
            store.put(namespace, "checkpoint", Map.of("text", "persisted"));
            assertEquals("persisted", store.get(namespace, "checkpoint").value().get("text"));
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT count(*) FROM agentscope_store")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }
}
