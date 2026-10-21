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

import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Each test owns its database so publication scans cannot consume another fixture's commands. */
@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresToolTransportIntegrationTest extends ManagedToolTransportTest {
    ManagedSchemaMigrationIntegrationTest.TemporaryDatabase database;

    @Override
    protected DataSource database() throws Exception {
        database = new ManagedSchemaMigrationIntegrationTest.TemporaryDatabase();
        try (var connection = database.connect()) {
            return new DriverManagerDataSource(
                    connection.getMetaData().getURL(), "managed", "managed-local-test");
        }
    }

    @Override
    protected void closeDatabase() throws Exception {
        if (database != null) database.close();
    }
}
