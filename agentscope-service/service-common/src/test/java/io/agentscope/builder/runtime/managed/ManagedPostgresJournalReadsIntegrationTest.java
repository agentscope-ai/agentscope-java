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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "MANAGED_DATABASE_TEST_URI", matches = ".+")
class ManagedPostgresJournalReadsIntegrationTest extends ManagedJournalReadsTest {
    private JdbcTemplate objects;

    @Override
    protected DataSource database() {
        return new DriverManagerDataSource(
                System.getenv("MANAGED_DATABASE_TEST_URI"), "managed", "managed-local-test");
    }

    @Override
    protected ManagedCheckpointObjects objectStore(
            DataSource source, ObjectMapper mapper, Path root) {
        objects = new JdbcTemplate(source);
        return new ManagedCheckpointObjects(mapper, new ManagedPostgresObjects(source));
    }

    @Override
    protected void deleteObject(ManagedCheckpointObjects.Reference reference) {
        objects.update(
                "DELETE FROM ma.private_object WHERE scope_key=? AND object_key=?",
                reference.identity().scope().messageGroup(),
                reference.identity().checkpointId());
    }

    @Override
    protected void corruptObject(ManagedCheckpointObjects.Reference reference) {
        objects.update(
                "UPDATE ma.private_object SET content_hash=? WHERE scope_key=? AND object_key=?",
                "0".repeat(64),
                reference.identity().scope().messageGroup(),
                reference.identity().checkpointId());
    }
}
