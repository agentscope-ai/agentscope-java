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
package io.agentscope.extensions.jdbc;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.jdbc.dialect.JdbcDialect;
import io.agentscope.extensions.jdbc.sandbox.JdbcSandboxExecutionGuard;
import io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Multi-database JDBC-backed {@link DistributedStore} with automatic dialect
 * detection.
 *
 * <p>Pass any JDBC {@link DataSource} and the store auto-detects the database via
 * {@link JdbcDialect#from(DataSource)}, then assembles all four components with the
 * correct dialect:
 *
 * <ul>
 *   <li>{@link JdbcAgentStateStore} — agent session state
 *   <li>{@link JdbcStore} — workspace filesystem KV
 *   <li>{@link JdbcSnapshotSpec} — sandbox snapshots as BLOBs
 *   <li>{@link JdbcSandboxExecutionGuard} — distributed lock via the dialect's
 *       {@link io.agentscope.extensions.jdbc.dialect.SandboxLockStrategy}
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * DataSource dataSource = ... // HikariCP, Druid, etc.
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(JdbcDistributedStore.create(dataSource))
 *     .filesystem(new DockerFilesystemSpec()
 *             .image("ubuntu:24.04"))
 *     .build();
 * }</pre>
 *
 * <p>This replaces the deprecated {@code MysqlDistributedStore} and
 * {@code PostgresDistributedStore} facades. The database is detected automatically;
 * no manual dialect selection is required.
 *
 * @author shanhongyu
 */
public class JdbcDistributedStore implements DistributedStore {

    private final DataSource dataSource;
    private final JdbcDialect dialect;

    private JdbcDistributedStore(DataSource dataSource, JdbcDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    /**
     * Creates a JDBC distributed store with auto-detected dialect.
     *
     * @param dataSource the JDBC data source for any supported database
     * @return a new distributed store
     */
    public static JdbcDistributedStore create(DataSource dataSource) {
        return new JdbcDistributedStore(dataSource, JdbcDialect.from(dataSource));
    }

    /**
     * Creates a JDBC distributed store with an explicitly specified dialect.
     *
     * @param dataSource the JDBC data source
     * @param dialect the dialect to use (skips auto-detection)
     * @return a new distributed store
     */
    public static JdbcDistributedStore create(DataSource dataSource, JdbcDialect dialect) {
        return new JdbcDistributedStore(dataSource, dialect);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return new JdbcAgentStateStore(
                dataSource, dialect, JdbcAgentStateStore.DEFAULT_TABLE_NAME, true);
    }

    @Override
    public BaseStore baseStore() {
        return JdbcStore.builder(dataSource).dialect(dialect).initializeSchema(true).build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new JdbcSnapshotSpec(dataSource, dialect);
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dialect.lockStrategy(dataSource)).build();
    }
}
