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

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** SQL metadata and temporary object roots must have the same per-test lifetime. */
final class IsolatedManagedTestDatabase implements AfterEachCallback {
    private ManagedSchemaMigrationIntegrationTest.TemporaryDatabase database;
    private DriverManagerDataSource source;

    DriverManagerDataSource source() {
        if (source != null) return source;
        try {
            database = new ManagedSchemaMigrationIntegrationTest.TemporaryDatabase();
            try (var connection = database.connect()) {
                source =
                        new DriverManagerDataSource(
                                connection.getMetaData().getURL(), "managed", "managed-local-test");
            }
            return source;
        } catch (Exception unavailable) {
            throw new IllegalStateException("Cannot create isolated test database", unavailable);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (database != null) database.close();
        source = null;
        database = null;
    }
}
