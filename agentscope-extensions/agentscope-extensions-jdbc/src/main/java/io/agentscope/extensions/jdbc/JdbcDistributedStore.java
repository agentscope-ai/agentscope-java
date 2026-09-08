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
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
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
 * Multi-database JDBC-backed {@link DistributedStore} with automatic dialect detection.
 *
 * <p>Pass any JDBC {@link DataSource} and the store auto-detects the database via
 * {@link AbstractJdbcDialect#from(DataSource)}, then assembles all four components:
 *
 * <ul>
 *   <li>{@link JdbcAgentStateStore} — agent session state
 *   <li>{@link JdbcStore} — workspace filesystem KV
 *   <li>{@link JdbcSnapshotSpec} — sandbox snapshots as BLOBs
 *   <li>{@link JdbcSandboxExecutionGuard} — distributed lock via the dialect's lock strategy
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
 * @author shanhongyu
 */
public class JdbcDistributedStore implements DistributedStore {

    private final DataSource dataSource;
    private final AbstractJdbcDialect dialect;

    // Lazily cached components. The DistributedStore contract does not bound the number of
    // calls to the accessor methods; building each component eagerly on every call would
    // re-run CREATE TABLE IF NOT EXISTS DDL and allocate throwaway instances. The first
    // call creates the component (with schema init) and subsequent calls reuse it. The
    // volatile write makes the publish safe across threads.
    private volatile AgentStateStore agentStateStore;
    private volatile BaseStore baseStore;
    private volatile SandboxSnapshotSpec sandboxSnapshotSpec;

    private JdbcDistributedStore(DataSource dataSource, AbstractJdbcDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.dialect.bindDataSource(dataSource);
    }

    /**
     * Creates a JDBC distributed store with auto-detected dialect and table creation.
     *
     * @param dataSource the JDBC data source for any supported database
     * @return a new distributed store
     */
    public static JdbcDistributedStore create(DataSource dataSource) {
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(dataSource).build();
        return new JdbcDistributedStore(dataSource, dialect);
    }

    /**
     * Creates a JDBC distributed store with an explicitly provided dialect.
     *
     * @param dataSource the JDBC data source
     * @param dialect the pre-built dialect (skips auto-detection)
     * @return a new distributed store
     */
    public static JdbcDistributedStore create(DataSource dataSource, AbstractJdbcDialect dialect) {
        return new JdbcDistributedStore(dataSource, dialect);
    }

    @Override
    public AgentStateStore agentStateStore() {
        AgentStateStore store = this.agentStateStore;
        if (store == null) {
            synchronized (this) {
                store = this.agentStateStore;
                if (store == null) {
                    store = new JdbcAgentStateStore(dataSource, dialect, true);
                    this.agentStateStore = store;
                }
            }
        }
        return store;
    }

    @Override
    public BaseStore baseStore() {
        BaseStore store = this.baseStore;
        if (store == null) {
            synchronized (this) {
                store = this.baseStore;
                if (store == null) {
                    store =
                            JdbcStore.builder(dataSource)
                                    .dialect(dialect)
                                    .initializeSchema(true)
                                    .build();
                    this.baseStore = store;
                }
            }
        }
        return store;
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        SandboxSnapshotSpec spec = this.sandboxSnapshotSpec;
        if (spec == null) {
            synchronized (this) {
                spec = this.sandboxSnapshotSpec;
                if (spec == null) {
                    spec = new JdbcSnapshotSpec(dataSource, dialect, true);
                    this.sandboxSnapshotSpec = spec;
                }
            }
        }
        return spec;
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return JdbcSandboxExecutionGuard.builder(dialect).build();
    }
}
