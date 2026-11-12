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

package io.agentscope.extensions.durable.jdbc;

import io.agentscope.durable.contract.SessionBackendContract;
import io.agentscope.durable.store.RecordStore;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;

/** The same contract is inherited by the HTTP backend tests. */
public class DurableBackendTest extends SessionBackendContract {
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");
    private String schema;

    @Override
    protected RecordStore openStore() {
        if (postgresUrl == null) return JdbcRecordStore.sqlite(directory.resolve("durable.db"));
        if (schema == null) {
            schema = "durable_test_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        return JdbcRecordStore.postgres(postgresUrl, properties);
    }

    @AfterEach
    protected void cleanup() throws Exception {
        if (schema != null)
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
    }
}
