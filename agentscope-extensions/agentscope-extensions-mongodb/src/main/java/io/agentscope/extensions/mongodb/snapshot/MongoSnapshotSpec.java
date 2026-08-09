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
package io.agentscope.extensions.mongodb.snapshot;

import com.mongodb.client.MongoClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;

/**
 * Convenience {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec} for
 * MongoDB-backed snapshot storage.
 *
 * <p>Stores sandbox workspace tar archives as BSON Binary in a MongoDB collection.
 */
public class MongoSnapshotSpec extends RemoteSnapshotSpec {

    public MongoSnapshotSpec(MongoClient mongoClient, String databaseName) {
        super(new MongoRemoteSnapshotClient(mongoClient, databaseName, null, true));
    }

    public MongoSnapshotSpec(MongoClient mongoClient, String databaseName, String collectionName) {
        super(new MongoRemoteSnapshotClient(mongoClient, databaseName, collectionName, true));
    }
}
