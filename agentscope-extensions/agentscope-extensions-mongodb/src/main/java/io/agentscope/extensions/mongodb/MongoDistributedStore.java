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

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;
import io.agentscope.extensions.mongodb.snapshot.MongoSnapshotSpec;
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;
import io.agentscope.extensions.mongodb.store.MongoBaseStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * MongoDB-backed {@link DistributedStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * MongoClient mongoClient = MongoClients.create("mongodb://localhost:27017");
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(MongoDistributedStore.create(mongoClient, "agentscope"))
 *     .build();
 * }</pre>
 *
 * <p>This configures:
 *
 * <ul>
 *   <li>{@link MongoAgentStateStore} — agent session state in MongoDB
 *   <li>{@link MongoBaseStore} — workspace filesystem KV in MongoDB
 *   <li>{@link MongoSandboxExecutionGuard} — sandbox execution locking in MongoDB
 *   <li>{@link MongoSnapshotSpec} — sandbox workspace snapshots in MongoDB
 * </ul>
 *
 * <p>When created via {@link #create(MongoClient)}, the caller owns the {@link MongoClient}
 * lifecycle; {@link #close()} will NOT close the client. When created via {@link
 * #fromConnectionString(String)}, the store owns the client and {@link #close()} will close it.
 */
public class MongoDistributedStore implements DistributedStore, AutoCloseable {

    private static final String DEFAULT_DATABASE = "agentscope";
    private static final String STATE_COLLECTION = "agentscope_sessions";
    private static final String BASE_COLLECTION = "agentscope_base";

    private final MongoClient mongoClient;
    private final boolean ownsClient;
    private final String databaseName;

    private volatile AgentStateStore cachedAgentStateStore;
    private volatile BaseStore cachedBaseStore;
    private volatile SandboxSnapshotSpec cachedSnapshotSpec;
    private volatile SandboxExecutionGuard cachedExecutionGuard;

    private MongoDistributedStore(MongoClient mongoClient, String databaseName) {
        this(mongoClient, databaseName, false);
    }

    private MongoDistributedStore(
            MongoClient mongoClient, String databaseName, boolean ownsClient) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "mongoClient");
        this.ownsClient = ownsClient;
        this.databaseName = databaseName != null ? databaseName : DEFAULT_DATABASE;
    }

    /**
     * Creates a MongoDB distributed store with the default database name ({@code "agentscope"}).
     *
     * @param mongoClient the MongoDB client
     * @return a new MongoDB distributed store
     */
    public static MongoDistributedStore create(MongoClient mongoClient) {
        return new MongoDistributedStore(mongoClient, null);
    }

    /**
     * Creates a MongoDB distributed store.
     *
     * @param mongoClient  the MongoDB client
     * @param databaseName the database name
     * @return a new MongoDB distributed store
     */
    public static MongoDistributedStore create(MongoClient mongoClient, String databaseName) {
        return new MongoDistributedStore(mongoClient, databaseName);
    }

    /**
     * Creates a MongoDB distributed store from a connection string. A new {@link MongoClient} is
     * created internally and owned by the store; {@link #close()} will close it.
     *
     * @param connectionString the MongoDB connection string
     * @return a new MongoDB distributed store
     */
    public static MongoDistributedStore fromConnectionString(String connectionString) {
        MongoClientSettings settings =
                MongoClientSettings.builder()
                        .applyConnectionString(new ConnectionString(connectionString))
                        .build();
        return new MongoDistributedStore(MongoClients.create(settings), null, true);
    }

    @Override
    public AgentStateStore agentStateStore() {
        AgentStateStore result = cachedAgentStateStore;
        if (result == null) {
            synchronized (this) {
                result = cachedAgentStateStore;
                if (result == null) {
                    result =
                            MongoAgentStateStore.builder()
                                    .mongoClient(mongoClient)
                                    .databaseName(databaseName)
                                    .collectionName(STATE_COLLECTION)
                                    .build();
                    cachedAgentStateStore = result;
                }
            }
        }
        return result;
    }

    @Override
    public BaseStore baseStore() {
        BaseStore result = cachedBaseStore;
        if (result == null) {
            synchronized (this) {
                result = cachedBaseStore;
                if (result == null) {
                    MongoDatabase db = mongoClient.getDatabase(databaseName);
                    result = new MongoBaseStore(db, BASE_COLLECTION);
                    cachedBaseStore = result;
                }
            }
        }
        return result;
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        SandboxSnapshotSpec result = cachedSnapshotSpec;
        if (result == null) {
            synchronized (this) {
                result = cachedSnapshotSpec;
                if (result == null) {
                    result = new MongoSnapshotSpec(mongoClient, databaseName);
                    cachedSnapshotSpec = result;
                }
            }
        }
        return result;
    }

    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        SandboxExecutionGuard result = cachedExecutionGuard;
        if (result == null) {
            synchronized (this) {
                result = cachedExecutionGuard;
                if (result == null) {
                    result =
                            MongoSandboxExecutionGuard.builder(mongoClient)
                                    .databaseName(databaseName)
                                    .build();
                    cachedExecutionGuard = result;
                }
            }
        }
        return result;
    }

    @Override
    public void close() {
        if (ownsClient) {
            mongoClient.close();
        }
    }
}
