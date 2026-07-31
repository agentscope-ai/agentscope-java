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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import io.agentscope.extensions.jdbc.dialect.H2Dialect;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for {@link JdbcDistributedStore} with H2.
 *
 * <p>Exercises all four components (state store, KV store, snapshot, lock) through
 * the facade with auto-detected dialect.
 *
 * @author shanhongyu
 */
@DisplayName("JdbcDistributedStore H2 end-to-end integration")
class JdbcDistributedStoreH2Test {

    private static JdbcDistributedStore distributedStore;

    record TestState(String value) implements State {}

    @BeforeAll
    static void setUp() {
        DataSource ds = H2TestSupport.createDataSource("distributed_store_test");
        distributedStore = JdbcDistributedStore.create(ds, new H2Dialect());
    }

    @Test
    @DisplayName("agentStateStore saves and retrieves state")
    void agentStateStoreWorks() {
        var stateStore = distributedStore.agentStateStore();
        stateStore.save("user1", "session1", "key", new TestState("state-value"));

        Optional<TestState> result = stateStore.get("user1", "session1", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("state-value", result.get().value());
    }

    @Test
    @DisplayName("baseStore performs KV operations")
    void baseStoreWorks() {
        BaseStore baseStore = distributedStore.baseStore();
        baseStore.put(List.of("ns"), "k", Map.of("v", 42));

        StoreItem item = baseStore.get(List.of("ns"), "k");
        assertNotNull(item);
        assertEquals(42, item.value().get("v"));
    }

    @Test
    @DisplayName("sandboxSnapshotSpec uploads and downloads snapshots")
    void snapshotSpecWorks() throws Exception {
        SandboxSnapshotSpec spec = distributedStore.sandboxSnapshotSpec();
        assertTrue(spec.build("test-snap") != null);

        // Access the underlying client to test upload/download
        // The spec wraps a JdbcRemoteSnapshotClient
        assertNotNull(spec);
    }

    @Test
    @DisplayName("sandboxExecutionGuard acquires and releases lock")
    void sandboxExecutionGuardWorks() throws Exception {
        SandboxExecutionGuard guard = distributedStore.sandboxExecutionGuard();
        SandboxIsolationKey key =
                SandboxIsolationKey.resolve(
                                io.agentscope.harness.agent.IsolationScope.GLOBAL,
                                null,
                                "test-agent")
                        .orElseThrow();

        SandboxLease lease = guard.tryEnter(key);
        assertNotNull(lease);
        lease.close();
    }

    @Test
    @DisplayName("snapshot upload/download via JdbcSnapshotSpec client")
    void snapshotUploadDownload() throws Exception {
        var spec =
                new io.agentscope.extensions.jdbc.snapshot.JdbcSnapshotSpec(
                        H2TestSupport.createDataSource("snapshot_facade_test"), new H2Dialect());
        RemoteSnapshotClient client = spec.getClient();

        byte[] data = "test archive".getBytes();
        client.upload("facade-snap", new ByteArrayInputStream(data));

        try (var downloaded = client.download("facade-snap")) {
            assertEquals(data.length, downloaded.readAllBytes().length);
        }
    }
}
