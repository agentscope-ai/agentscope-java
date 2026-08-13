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
package io.agentscope.extensions.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;
import io.agentscope.extensions.mongodb.snapshot.MongoRemoteSnapshotClient;
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;
import io.agentscope.extensions.mongodb.store.MongoBaseStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Verifies that all MongoDB collections created by the extension have the correct indexes with
 * expected parameters (TTL values, sparse flags, compound keys).
 *
 * <p>This covers a class of bugs invisible to unit and contract tests: wrong index parameters that
 * cause silent data loss (TTL=0) or startup failures on upgrade (IndexOptionsConflict).
 *
 * <p>Requires a local MongoDB at {@code localhost:27017}. Skipped in CI.
 */
@DisplayName("Index lifecycle — MongoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoIndexLifecycleContractTest {

    private static final long THIRTY_DAYS_SECONDS = 30L * 24 * 3600;
    private static final long SEVEN_DAYS_SECONDS = 7L * 24 * 3600;

    private static MongoClient client;
    private static String dbName;

    @BeforeAll
    static void connect() {
        try {
            client = MongoClients.create("mongodb://localhost:27017");
            client.getDatabase("ping").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            Assumptions.abort("MongoDB not available: " + e.getMessage());
        }
        dbName = "test_idx_lifecycle_" + System.currentTimeMillis();
    }

    @AfterAll
    static void disconnect() {
        if (client != null) {
            client.getDatabase(dbName).drop();
            client.close();
        }
    }

    // ────────────────── AgentStateStore indexes ──────────────────

    @Test
    @Order(1)
    @DisplayName("AgentStateStore: compound index on (user_id, session_id) exists")
    void agentStateStore_compoundIndex() {
        MongoAgentStateStore.builder()
                .mongoClient(client)
                .databaseName(dbName)
                .collectionName("idx_sessions")
                .build();

        Map<String, Document> indexes = indexMap(dbName, "idx_sessions");

        Document compound =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d
                                            && d.containsKey("user_id")
                                            && d.containsKey("session_id");
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(compound, "Compound index (user_id, session_id) must exist");
    }

    @Test
    @Order(2)
    @DisplayName("AgentStateStore: TTL index on _updated_at with 30-day expiry and sparse")
    void agentStateStore_ttlIndex_30days() {
        Map<String, Document> indexes = indexMap(dbName, "idx_sessions");

        Document ttlIndex = indexes.get("_updated_at_1");
        assertNotNull(ttlIndex, "TTL index '_updated_at_1' must exist");
        assertEquals(
                THIRTY_DAYS_SECONDS,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "TTL must be 30 days (2592000s), not 0");
        assertEquals(true, ttlIndex.getBoolean("sparse"), "TTL index must be sparse");
    }

    @Test
    @Order(3)
    @DisplayName("AgentStateStore: upgrade from old TTL=0 index does not throw")
    void agentStateStore_ttlUpgrade_fromZero() {
        String upgradeDb = "test_idx_upgrade_" + System.currentTimeMillis();
        String collName = "upgrade_sessions";

        // Phase 1: simulate old code — create TTL index with expireAfterSeconds=0
        MongoDatabase upgradeDbRef = client.getDatabase(upgradeDb);
        upgradeDbRef
                .getCollection(collName)
                .createIndex(
                        new org.bson.Document("_updated_at", 1),
                        new com.mongodb.client.model.IndexOptions()
                                .expireAfter(0L, java.util.concurrent.TimeUnit.SECONDS)
                                .sparse(true));

        // Phase 2: new code constructor runs ensureIndexes() — must not throw error 85
        MongoAgentStateStore.builder()
                .mongoClient(client)
                .databaseName(upgradeDb)
                .collectionName(collName)
                .build();

        // Phase 3: verify index was upgraded to 30 days
        Map<String, Document> indexes = indexMap(upgradeDb, collName);
        Document ttlIndex = indexes.get("_updated_at_1");
        assertNotNull(ttlIndex);
        assertEquals(
                THIRTY_DAYS_SECONDS,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "After upgrade, TTL must be 30 days");

        // Cleanup
        upgradeDbRef.drop();
    }

    // ────────────────── BaseStore indexes ──────────────────

    @Test
    @Order(4)
    @DisplayName("BaseStore: index on namespace exists")
    void baseStore_namespaceIndex() {
        new MongoBaseStore(client.getDatabase(dbName), "idx_base");

        Map<String, Document> indexes = indexMap(dbName, "idx_base");

        Document nsIndex =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d
                                            && d.containsKey("namespace")
                                            && d.size() == 1;
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(nsIndex, "Index on 'namespace' must exist");
    }

    @Test
    @Order(5)
    @DisplayName("BaseStore: compound index on (namespace, key) exists")
    void baseStore_compoundIndex() {
        Map<String, Document> indexes = indexMap(dbName, "idx_base");

        Document compound =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d
                                            && d.containsKey("namespace")
                                            && d.containsKey("key")
                                            && d.size() == 2;
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(compound, "Compound index (namespace, key) must exist");
    }

    // ────────────────── SandboxExecutionGuard indexes ──────────────────

    @Test
    @Order(6)
    @DisplayName("SandboxExecutionGuard: TTL index on expiresAt with immediate expiry (0s)")
    void sandboxGuard_ttlIndex_immediate() {
        MongoSandboxExecutionGuard.builder(client)
                .databaseName(dbName)
                .collectionName("idx_locks")
                .build();

        Map<String, Document> indexes = indexMap(dbName, "idx_locks");

        Document ttlIndex =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d && d.containsKey("expiresAt");
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(ttlIndex, "TTL index on 'expiresAt' must exist");
        assertEquals(
                0L,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "Lock TTL must be 0 (immediate expiry after expiresAt)");
    }

    // ────────────────── RemoteSnapshotClient indexes ──────────────────

    @Test
    @Order(7)
    @DisplayName("RemoteSnapshotClient: TTL index on createdAt with 7-day expiry")
    void snapshotClient_ttlIndex_7days() {
        new MongoRemoteSnapshotClient(client, dbName, "idx_snapshots", true);

        Map<String, Document> indexes = indexMap(dbName, "idx_snapshots");

        Document ttlIndex =
                indexes.values().stream()
                        .filter(
                                i -> {
                                    Object key = i.get("key");
                                    return key instanceof Document d && d.containsKey("createdAt");
                                })
                        .findFirst()
                        .orElse(null);

        assertNotNull(ttlIndex, "TTL index on 'createdAt' must exist");
        assertEquals(
                SEVEN_DAYS_SECONDS,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "Snapshot TTL must be 7 days (604800s)");
    }

    // ────────────────── Helpers ──────────────────

    private static Map<String, Document> indexMap(String database, String collection) {
        MongoCollection<Document> coll = client.getDatabase(database).getCollection(collection);
        List<Document> idxDocs = new ArrayList<>();
        coll.listIndexes().into(idxDocs);
        return idxDocs.stream().collect(Collectors.toMap(d -> d.getString("name"), d -> d));
    }
}
