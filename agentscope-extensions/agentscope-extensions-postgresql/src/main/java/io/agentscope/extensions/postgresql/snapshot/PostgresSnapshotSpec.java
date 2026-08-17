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
package io.agentscope.extensions.postgresql.snapshot;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import javax.sql.DataSource;

/**
 * Convenience {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}
 * for PostgreSQL-backed snapshot storage.
 *
 * <p>Stores sandbox workspace tar archives as BYTEAs in a database table.
 */
public class PostgresSnapshotSpec extends RemoteSnapshotSpec {

    public PostgresSnapshotSpec(DataSource dataSource) {
        super(new PostgresRemoteSnapshotClient(dataSource, true));
    }

    public PostgresSnapshotSpec(DataSource dataSource, String tableName) {
        super(new PostgresRemoteSnapshotClient(dataSource, tableName, true));
    }
}
