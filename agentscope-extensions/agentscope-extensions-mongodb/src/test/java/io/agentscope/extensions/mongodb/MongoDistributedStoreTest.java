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
package io.agentscope.extensions.mongodb;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoDistributedStoreTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void createWithMongoClient() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        assertNotNull(store);
    }

    @Test
    void createWithMongoClientAndDatabaseName() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient, "mydb");
        assertNotNull(store);
    }

    @Test
    void agentStateStoreReturnsNonNull() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        AgentStateStore stateStore = store.agentStateStore();
        assertNotNull(stateStore);
    }

    @Test
    void baseStoreReturnsNonNull() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        BaseStore baseStore = store.baseStore();
        assertNotNull(baseStore);
    }

    @Test
    void createWithNullMongoClientThrows() {
        assertThrows(NullPointerException.class, () -> MongoDistributedStore.create(null));
    }

    @Test
    void sandboxExecutionGuardReturnsNonNull() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        SandboxExecutionGuard guard = store.sandboxExecutionGuard();
        assertNotNull(guard);
    }

    @Test
    void sandboxSnapshotSpecReturnsNonNull() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        assertNotNull(store.sandboxSnapshotSpec());
    }

    @Test
    void fromConnectionStringCreatesStore() {
        // MongoClient creation is lazy — no actual connection until an operation is performed
        MongoDistributedStore store =
                MongoDistributedStore.fromConnectionString("mongodb://localhost:27017");
        assertNotNull(store);
    }

    @Test
    void agentStateStoreReturnsCachedInstance() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        AgentStateStore first = store.agentStateStore();
        AgentStateStore second = store.agentStateStore();
        assertSame(first, second);
    }

    @Test
    void baseStoreReturnsCachedInstance() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        BaseStore first = store.baseStore();
        BaseStore second = store.baseStore();
        assertSame(first, second);
    }

    @Test
    void sandboxSnapshotSpecReturnsCachedInstance() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        SandboxSnapshotSpec first = store.sandboxSnapshotSpec();
        SandboxSnapshotSpec second = store.sandboxSnapshotSpec();
        assertSame(first, second);
    }

    @Test
    void sandboxExecutionGuardReturnsCachedInstance() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        SandboxExecutionGuard first = store.sandboxExecutionGuard();
        SandboxExecutionGuard second = store.sandboxExecutionGuard();
        assertSame(first, second);
    }

    @Test
    void closeWithExternalClientDoesNotCloseClient() {
        MongoDistributedStore store = MongoDistributedStore.create(mongoClient);
        store.close();
        verify(mongoClient, never()).close();
    }

    @Test
    void closeFromConnectionStringDoesNotThrow() {
        MongoDistributedStore store =
                MongoDistributedStore.fromConnectionString("mongodb://localhost:27017");
        store.close();
    }
}
