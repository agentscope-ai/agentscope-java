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
package io.agentscope.extensions.jdbc.snapshot;

import io.agentscope.extensions.jdbc.dialect.table.SnapshotDialect;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import javax.sql.DataSource;

/**
 * Convenience {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec}
 * for JDBC-backed snapshot storage.
 *
 * @author shanhongyu
 */
public class JdbcSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates a snapshot spec with auto table creation.
     *
     * @param dataSource the JDBC data source
     * @param dialect the snapshot dialect
     */
    public JdbcSnapshotSpec(DataSource dataSource, SnapshotDialect dialect) {
        super(new JdbcRemoteSnapshotClient(dataSource, dialect, true));
    }

    /**
     * Creates a snapshot spec with explicit table creation control.
     *
     * @param dataSource the JDBC data source
     * @param dialect the snapshot dialect
     * @param initializeSchema when true, auto-creates the table
     */
    public JdbcSnapshotSpec(
            DataSource dataSource, SnapshotDialect dialect, boolean initializeSchema) {
        super(new JdbcRemoteSnapshotClient(dataSource, dialect, initializeSchema));
    }
}
