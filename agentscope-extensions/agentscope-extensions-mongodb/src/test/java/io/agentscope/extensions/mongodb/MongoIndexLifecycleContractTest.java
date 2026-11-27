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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.model.Filters;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.extensions.mongodb.sandbox.MongoSandboxExecutionGuard;
import io.agentscope.extensions.mongodb.snapshot.MongoRemoteSnapshotClient;
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore;
import io.agentscope.extensions.mongodb.store.MongoBaseStore;
import io.agentscope.extensions.mongodb.testutil.RequireDocker;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that all MongoDB collections created by the extension have the correct indexes with
 * expected parameters (TTL values, sparse flags, compound keys).
 *
 * <p>This covers a class of bugs invisible to unit and contract tests: wrong index parameters that
 * cause silent data loss (TTL=0) or startup failures on upgrade (IndexOptionsConflict).
 *
 * <p>Uses Testcontainers to spin up a real MongoDB instance, making the tests runnable in CI.
 */
@Testcontainers
@RequireDocker
@DisplayName("Index lifecycle — MongoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoIndexLifecycleContractTest {

    private static final long THIRTY_DAYS_SECONDS = 30L * 24 * 3600;

    @Container static final MongoDBContainer mongoContainer = new MongoDBContainer("mongo:7");

    private static MongoClient client;
    private static String dbName;

    @BeforeAll
    static void connect() {
        dbName = "test_idx_lifecycle_" + System.currentTimeMillis();
        client = MongoClients.create(mongoContainer.getConnectionString());
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
    @DisplayName("AgentStateStore: no TTL index by default (aligned with Postgres/JDBC/Redis)")
    void agentStateStore_noTtlByDefault() {
        Map<String, Document> indexes = indexMap(dbName, "idx_sessions");

        Document ttlIndex = indexes.get("_updated_at_1");
        assertEquals(
                null,
                ttlIndex,
                "TTL index must NOT exist by default — sessions are retained indefinitely,"
                        + " aligned with Postgres/JDBC/Redis");
    }

    @Test
    @Order(3)
    @DisplayName("AgentStateStore: opt-in TTL creates index with specified days")
    void agentStateStore_optInTtl() {
        String ttlDb = "test_idx_ttl_optin_" + System.currentTimeMillis();
        String collName = "ttl_sessions";

        MongoAgentStateStore.builder()
                .mongoClient(client)
                .databaseName(ttlDb)
                .collectionName(collName)
                .ttlDays(30)
                .build();

        Map<String, Document> indexes = indexMap(ttlDb, collName);
        Document ttlIndex = indexes.get("_updated_at_1");
        assertNotNull(ttlIndex, "TTL index must exist when ttlDays=30");
        assertEquals(
                THIRTY_DAYS_SECONDS,
                ((Number) ttlIndex.get("expireAfterSeconds")).longValue(),
                "TTL must be 30 days (2592000s)");
        assertEquals(true, ttlIndex.getBoolean("sparse"), "TTL index must be sparse");

        // Cleanup
        client.getDatabase(ttlDb).drop();
    }

    // ────────────────── BaseStore indexes ──────────────────

    @Test
    @Order(4)
    @DisplayName("BaseStore: compound index on (namespace, key) exists")
    void baseStore_compoundIndex() {
        new MongoBaseStore(client.getDatabase(dbName), "idx_base");

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
    @Order(5)
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
    @Order(6)
    @DisplayName("RemoteSnapshotClient: no TTL index on snapshots (aligned with siblings)")
    void snapshotClient_noTtlIndex() throws Exception {
        MongoRemoteSnapshotClient snapshotClient =
                new MongoRemoteSnapshotClient(client, dbName, "idx_snapshots");
        // Materialize the GridFS collections so their indexes can be inspected.
        snapshotClient.upload(
                "idx-snap", new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        // GridFS stores snapshot metadata in <bucket>.files — the snapshot payload lives there,
        // so this is the collection that must stay free of any TTL index.
        assertFalse(
                hasTtlIndex(dbName, "idx_snapshots.files"),
                "GridFS files collection must NOT have any TTL index — aligned with"
                        + " Postgres/JDBC/Redis which have no independent snapshot expiry.");
        assertFalse(
                hasTtlIndex(dbName, "idx_snapshots"),
                "Legacy snapshot collection must NOT have any TTL index either.");
    }

    private static boolean hasTtlIndex(String database, String collection) {
        return indexMap(database, collection).values().stream()
                .anyMatch(i -> i.containsKey("expireAfterSeconds"));
    }

    @Test
    @Order(7)
    @DisplayName("Cascade: session delete removes the sandbox snapshot")
    void snapshot_cascadeDeletedWithSession() throws Exception {
        String cascadeDb = "test_cascade_" + System.currentTimeMillis();
        String snapshotId = "snap-" + System.currentTimeMillis();
        // An unrelated snapshot belonging to a different session — must survive cascade.
        String unrelatedSnapshotId = "snap-other-" + System.currentTimeMillis();

        MongoDistributedStore store = MongoDistributedStore.create(client, cascadeDb);
        try {
            AgentStateStore stateStore = store.agentStateStore();

            // Persist the sandbox state under a SESSION-scoped synthetic sessionId, mirroring
            // how SessionSandboxStateStore stores the serialized SandboxState JSON.
            String sandboxJson =
                    "{\"type\":\"docker\",\"sessionId\":\""
                            + snapshotId
                            + "\",\"snapshot\":{\"type\":\"remote\",\"id\":\""
                            + snapshotId
                            + "\"}}";
            stateStore.save(
                    null,
                    "sandbox/session/sess1",
                    "_sandbox_state",
                    new SandboxSlotState(sandboxJson));

            // Upload the workspace snapshot via the 2-arg interface method, as production does.
            MongoRemoteSnapshotClient snapshotClient =
                    new MongoRemoteSnapshotClient(
                            client, cascadeDb, MongoConstants.SNAPSHOTS_COLLECTION);
            byte[] data = "workspace-tar".getBytes(StandardCharsets.UTF_8);
            snapshotClient.upload(snapshotId, new ByteArrayInputStream(data));

            // Upload an unrelated snapshot (not linked to any session's sandbox state).
            snapshotClient.upload(
                    unrelatedSnapshotId,
                    new ByteArrayInputStream("other".getBytes(StandardCharsets.UTF_8)));

            assertTrue(snapshotClient.exists(snapshotId), "Snapshot must exist after upload");
            assertTrue(
                    snapshotClient.exists(unrelatedSnapshotId),
                    "Unrelated snapshot must exist after upload");

            // Delete the session — cascade cleanup must remove the snapshot.
            stateStore.delete("user1", "sess1");

            assertFalse(
                    snapshotClient.exists(snapshotId),
                    "Snapshot must be cascade-deleted when its owning session is removed");
            assertTrue(
                    snapshotClient.exists(unrelatedSnapshotId),
                    "Unrelated snapshot must NOT be affected by cascade delete");
        } finally {
            store.close();
            client.getDatabase(cascadeDb).drop();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Legacy fallback: download reads old single-document snapshot format")
    void downloadFallsBackToLegacyFormat() throws Exception {
        String legacyDb = "test_legacy_" + System.currentTimeMillis();
        String snapshotId = "legacy-snap-" + System.currentTimeMillis();
        byte[] payload = "legacy-workspace-tar".getBytes(StandardCharsets.UTF_8);

        try {
            // Insert a legacy-format snapshot document directly (single BSON document
            // with {_id, data: Binary}), bypassing the GridFS upload path.
            MongoCollection<Document> legacyColl =
                    client.getDatabase(legacyDb).getCollection(MongoConstants.SNAPSHOTS_COLLECTION);
            legacyColl.insertOne(
                    new Document("_id", snapshotId).append("data", new Binary(payload)));

            MongoRemoteSnapshotClient snapshotClient =
                    new MongoRemoteSnapshotClient(
                            client, legacyDb, MongoConstants.SNAPSHOTS_COLLECTION);

            assertTrue(
                    snapshotClient.exists(snapshotId),
                    "exists() must find legacy snapshot via fallback");
            assertTrue(
                    snapshotClient.exists("nonexistent-" + System.currentTimeMillis()) == false,
                    "exists() must return false when snapshot is absent everywhere");

            byte[] downloaded = snapshotClient.download(snapshotId).readAllBytes();
            assertEquals(
                    payload.length,
                    downloaded.length,
                    "download() must read legacy snapshot via fallback path");
        } finally {
            client.getDatabase(legacyDb).drop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 300_000})
    @Order(9)
    void failedSnapshotReplacementPreservesReadableArchive(int bytesBeforeFailure)
            throws Exception {
        String collection = "replacement_snapshots";
        String snapshotId = "existing-snapshot";
        byte[] original = "previous complete workspace".getBytes(StandardCharsets.UTF_8);
        MongoRemoteSnapshotClient snapshots =
                new MongoRemoteSnapshotClient(client, dbName, collection);
        snapshots.upload(snapshotId, new ByteArrayInputStream(original));

        try (InputStream failing =
                new InputStream() {
                    private int remaining = bytesBeforeFailure;

                    @Override
                    public int read() throws IOException {
                        if (remaining-- > 0) {
                            return 1;
                        }
                        throw new IOException("fixture archive failure");
                    }
                }) {
            assertThrows(Exception.class, () -> snapshots.upload(snapshotId, failing));
        }

        assertTrue(snapshots.exists(snapshotId));
        try (InputStream restored = snapshots.download(snapshotId)) {
            assertArrayEquals(original, restored.readAllBytes());
        }
        byte[] replacement = "new complete workspace".getBytes(StandardCharsets.UTF_8);
        snapshots.upload(snapshotId, new ByteArrayInputStream(replacement));
        try (InputStream restored = snapshots.download(snapshotId)) {
            assertArrayEquals(replacement, restored.readAllBytes());
        }
        assertEquals(
                1,
                client.getDatabase(dbName)
                        .getCollection(collection + ".files")
                        .countDocuments(Filters.eq("filename", snapshotId)));
        assertTrue(snapshots.delete(snapshotId));
        assertFalse(snapshots.exists(snapshotId));
    }

    @Test
    @Order(10)
    void retainedRevisionsReadNewestAndDeleteTogether() throws Exception {
        String collection = "retained_snapshots";
        String snapshotId = "retained-snapshot";
        GridFSBucket bucket = GridFSBuckets.create(client.getDatabase(dbName), collection);
        byte[] original = "old complete archive".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "new complete archive".getBytes(StandardCharsets.UTF_8);
        var first = bucket.uploadFromStream(snapshotId, new ByteArrayInputStream(original));
        var second = bucket.uploadFromStream(snapshotId, new ByteArrayInputStream(replacement));
        // Model the same-millisecond upload case: _id provides deterministic tie breaking.
        client.getDatabase(dbName)
                .getCollection(collection + ".files")
                .updateMany(
                        Filters.eq("filename", snapshotId),
                        new Document("$set", new Document("uploadDate", new java.util.Date(1))));
        bucket.uploadFromStream("other-snapshot", new ByteArrayInputStream(original));
        client.getDatabase(dbName)
                .getCollection(collection)
                .insertOne(new Document("_id", snapshotId).append("data", new Binary(original)));
        MongoRemoteSnapshotClient snapshots =
                new MongoRemoteSnapshotClient(client, dbName, collection);

        assertTrue(second.compareTo(first) > 0);
        try (InputStream restored = snapshots.download(snapshotId)) {
            assertArrayEquals(replacement, restored.readAllBytes());
        }
        assertTrue(snapshots.delete(snapshotId));
        assertFalse(snapshots.exists(snapshotId));
        assertFalse(snapshots.delete(snapshotId));
        assertTrue(snapshots.exists("other-snapshot"));
        assertEquals(
                0,
                client.getDatabase(dbName)
                        .getCollection(collection + ".files")
                        .countDocuments(Filters.eq("filename", snapshotId)));
        assertEquals(
                0,
                client.getDatabase(dbName)
                        .getCollection(collection + ".chunks")
                        .countDocuments(Filters.in("files_id", first, second)));
        assertEquals(
                0,
                client.getDatabase(dbName)
                        .getCollection(collection)
                        .countDocuments(Filters.eq("_id", snapshotId)));
    }

    /** State slot shape mirroring {@code SessionSandboxStateStore.SandboxStateSlot}. */
    private static class SandboxSlotState implements State {
        private String json;
        private boolean deleted;

        @SuppressWarnings("unused")
        SandboxSlotState() {}

        SandboxSlotState(String json) {
            this.json = json;
        }

        public String getJson() {
            return json;
        }

        public boolean getDeleted() {
            return deleted;
        }
    }

    // ────────────────── Helpers ──────────────────

    private static Map<String, Document> indexMap(String database, String collection) {
        MongoCollection<Document> coll = client.getDatabase(database).getCollection(collection);
        List<Document> idxDocs = new ArrayList<>();
        coll.listIndexes().into(idxDocs);
        return idxDocs.stream().collect(Collectors.toMap(d -> d.getString("name"), d -> d));
    }
}
