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

import io.agentscope.durable.SessionBackend;
import io.agentscope.durable.TransactionalSessionBackend;
import io.agentscope.durable.contract.UserApiContract;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

public class DurableUserApiTest extends UserApiContract {
    @TempDir protected Path directory;

    private String schema;
    private final String postgresUrl = System.getProperty("durable.test.jdbcUrl");

    @Override
    protected SessionBackend openBackend() {
        if (postgresUrl == null)
            return new TransactionalSessionBackend(
                    JdbcRecordStore.sqlite(directory.resolve("user.db")));
        String created = "durable_user_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(postgresUrl);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + created);
            schema = created;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        return new TransactionalSessionBackend(JdbcRecordStore.postgres(postgresUrl, properties));
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (schema != null) {
            if (api != null) api.close();
            if (backend != null) backend.close();
            try (var connection = DriverManager.getConnection(postgresUrl);
                    var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
