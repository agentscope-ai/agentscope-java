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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.alicloud.openservices.tablestore.SyncClient;
import com.alicloud.openservices.tablestore.model.BatchWriteRowRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.TableStoreSessionProjectionStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;

@EnabledIfEnvironmentVariable(named = "MANAGED_TABLESTORE_TEST_ENDPOINT", matches = ".+")
class ManagedTableStoreProjectionIntegrationTest extends SessionProjectionStoreTest {
    private SyncClient client;

    @BeforeEach
    @Override
    void setup() throws Exception {
        super.setup();
        client =
                new SyncClient(
                        System.getenv("MANAGED_TABLESTORE_TEST_ENDPOINT"),
                        System.getenv("MANAGED_TABLESTORE_TEST_ACCESS_KEY"),
                        System.getenv("MANAGED_TABLESTORE_TEST_SECRET_KEY"),
                        System.getenv("MANAGED_TABLESTORE_TEST_INSTANCE"));
        var backend =
                new TableStoreSessionProjectionStore(client, new ObjectMapper(), "MaContract");
        backend.initialize();
        store = backend;
    }

    @AfterEach
    void close() {
        if (client != null) client.shutdown();
    }

    @Test
    void realBackendSkipsConfirmedCreationAndBatchVerifiesColdReplay() {
        var observed = spy(client);
        var warm = new TableStoreSessionProjectionStore(observed, new ObjectMapper(), "MaContract");
        warm.putBatch(build, List.of(event(1)), List.of(resource(1, false)));
        clearInvocations(observed);
        warm.putBatch(build, List.of(event(2)), List.of(resource(2, false)));
        var written = ArgumentCaptor.forClass(BatchWriteRowRequest.class);
        verify(observed).batchWriteRow(written.capture());
        assertEquals(2, written.getValue().getRowsCount());
        verify(observed, never()).batchGetRow(any());
        clearInvocations(observed);
        var cold = new TableStoreSessionProjectionStore(observed, new ObjectMapper(), "MaContract");
        cold.putBatch(
                build,
                List.of(event(1), event(2)),
                List.of(resource(1, false), resource(2, false)));
        verify(observed).batchGetRow(any());
        verify(observed, never()).getRow(any());
    }
}
