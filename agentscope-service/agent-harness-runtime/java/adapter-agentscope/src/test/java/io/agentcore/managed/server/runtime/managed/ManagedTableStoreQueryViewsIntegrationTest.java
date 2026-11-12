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

import com.alicloud.openservices.tablestore.SyncClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.runtime.managed.projection.SessionProjectionStore;
import io.agentcore.managed.server.runtime.managed.projection.TableStoreSessionProjectionStore;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MANAGED_TABLESTORE_TEST_ENDPOINT", matches = ".+")
class ManagedTableStoreQueryViewsIntegrationTest extends ManagedQueryViewsTest {
    private SyncClient client;

    @Override
    protected SessionProjectionStore backend(DataSource source) {
        client =
                new SyncClient(
                        System.getenv("MANAGED_TABLESTORE_TEST_ENDPOINT"),
                        System.getenv("MANAGED_TABLESTORE_TEST_ACCESS_KEY"),
                        System.getenv("MANAGED_TABLESTORE_TEST_SECRET_KEY"),
                        System.getenv("MANAGED_TABLESTORE_TEST_INSTANCE"));
        var store = new TableStoreSessionProjectionStore(client, new ObjectMapper(), "MaContract");
        store.initialize();
        return store;
    }

    @AfterEach
    void closeClient() {
        if (client != null) client.shutdown();
    }
}
