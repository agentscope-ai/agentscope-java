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
package io.agentscope.extensions.cos;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.qcloud.cos.COSClient;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CosDistributedStoreTest {

    private COSClient mockCos;

    @BeforeEach
    void setUp() {
        mockCos = mock(COSClient.class);
    }

    @Test
    void createWithPrefix() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "bucket", "myprefix/");
        assertNotNull(store);
    }

    @Test
    void createWithNullPrefix() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "bucket", null);
        assertNotNull(store);
    }

    @Test
    void createRejectsNullClient() {
        assertThrows(
                NullPointerException.class, () -> CosDistributedStore.create(null, "bucket", "p/"));
    }

    @Test
    void createRejectsNullBucket() {
        assertThrows(
                NullPointerException.class, () -> CosDistributedStore.create(mockCos, null, "p/"));
    }

    @Test
    void agentStateStore_returnsInstance() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "bucket", "p/");
        AgentStateStore stateStore = store.agentStateStore();
        assertNotNull(stateStore);
    }

    @Test
    void baseStore_returnsInstance() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "bucket", "p/");
        BaseStore baseStore = store.baseStore();
        assertNotNull(baseStore);
    }

    @Test
    void sandboxSnapshotSpec_returnsInstance() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "bucket", "p/");
        SandboxSnapshotSpec spec = store.sandboxSnapshotSpec();
        assertNotNull(spec);
    }

    @Test
    void allComponentsUseSameBucket() {
        CosDistributedStore store = CosDistributedStore.create(mockCos, "my-bucket", "ag/");
        assertNotNull(store.agentStateStore());
        assertNotNull(store.baseStore());
        assertNotNull(store.sandboxSnapshotSpec());
    }
}
