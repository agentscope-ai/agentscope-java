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
package io.agentcore.managed.server.runtime.managed;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The same ACK/claim/crash boundaries verified against real PostgreSQL transactions. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresResumeHandoffIntegrationTest extends ManagedResumeHandoffTest {
    private ManagedSchemaMigrationIntegrationTest.TemporaryDatabase isolated;

    @Override
    protected DataSource database() {
        try {
            isolated = new ManagedSchemaMigrationIntegrationTest.TemporaryDatabase();
            try (var connection = isolated.connect()) {
                return new DriverManagerDataSource(
                        connection.getMetaData().getURL(), "managed", "managed-local-test");
            }
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Cannot create isolated Resume test database", unavailable);
        }
    }

    @Override
    @AfterEach
    void cleanup() throws Exception {
        try {
            super.cleanup();
        } finally {
            if (isolated != null) isolated.close();
        }
    }
}
