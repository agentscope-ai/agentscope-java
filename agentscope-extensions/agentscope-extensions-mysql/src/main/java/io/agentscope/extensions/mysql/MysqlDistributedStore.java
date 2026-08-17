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
package io.agentscope.extensions.mysql;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.mysql.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.extensions.mysql.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * MySQL/JDBC-backed {@link DistributedStore}.
 *
 * <p>Usage:
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP, Druid, etc.
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MysqlDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>This configures:
 * <ul>
 *   <li>{@link MysqlAgentStateStore} — agent session state in MySQL</li>
 *   <li>{@link JdbcStore} — workspace filesystem KV in MySQL</li>
 *   <li>{@link JdbcSnapshotSpec} — sandbox snapshots as BLOBs in MySQL</li>
 *   <li>{@link JdbcSandboxExecutionGuard} — distributed lock via MySQL {@code GET_LOCK()}</li>
 * </ul>
 */
public class MysqlDistributedStore implements DistributedStore {

    private final DataSource dataSource;

    private MysqlDistributedStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * Creates a MySQL distributed store.
     *
     * @param dataSource JDBC data source for MySQL
     * @return a new MySQL distributed store
     */
    public static MysqlDistributedStore create(DataSource dataSource) {
        return new MysqlDistributedStore(dataSource);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new MysqlAgentStateStore(dataSource, true);
    }

    @Override
    public BaseStore baseStore() {
        return JdbcStore.builder(dataSource).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new JdbcSnapshotSpec(dataSource);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dataSource).build();
    }
}
