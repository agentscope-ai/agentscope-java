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
package io.agentscope.extensions.postgresql;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.postgresql.sandbox.PostgresSandboxExecutionGuard;
import io.agentscope.extensions.postgresql.snapshot.PostgresSnapshotSpec;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.extensions.postgresql.store.PostgresBaseStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * PostgreSQL-backed {@link DistributedStore}.
 *
 * <p>Usage:
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP, Druid, etc.
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(PostgresDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>This configures:
 * <ul>
 *   <li>{@link PostgresAgentStateStore} — agent session state in PostgreSQL</li>
 *   <li>{@link PostgresBaseStore} — workspace filesystem KV in PostgreSQL</li>
 *   <li>{@link PostgresSnapshotSpec} — sandbox snapshots as BYTEAs in PostgreSQL</li>
 *   <li>{@link PostgresSandboxExecutionGuard} — distributed lock via PostgreSQL advisory locks</li>
 * </ul>
 *
 * <p>The target database must already exist; schema and tables are created automatically by
 * default.
 */
public class PostgresDistributedStore implements DistributedStore {

    private final DataSource dataSource;

    private PostgresDistributedStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates a PostgreSQL distributed store.
     *
     * @param dataSource JDBC data source for PostgreSQL
     * @return a new PostgreSQL distributed store
     */
    public static PostgresDistributedStore create(DataSource dataSource) {
        return new PostgresDistributedStore(dataSource);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new PostgresAgentStateStore(dataSource, true);
    }

    @Override
    public BaseStore baseStore() {
        return PostgresBaseStore.builder(dataSource).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new PostgresSnapshotSpec(dataSource);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return PostgresSandboxExecutionGuard.builder(dataSource).build();
    }
}
