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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoRemoteSnapshotClientTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    private AutoCloseable mocks;
    private MongoRemoteSnapshotClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);

        UpdateResult replaceResult = org.mockito.Mockito.mock(UpdateResult.class);
        when(replaceResult.wasAcknowledged()).thenReturn(true);
        when(collection.replaceOne(any(Bson.class), any(Document.class), any()))
                .thenReturn(replaceResult);

        client = new MongoRemoteSnapshotClient(mongoClient, "testdb", null, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void constructorWithDefaultsCreatesClient() {
        MongoRemoteSnapshotClient defaultClient =
                new MongoRemoteSnapshotClient(mongoClient, null, null, false);
        assertNotNull(defaultClient);
    }

    @Test
    void constructorWithInitializeSchemaCreatesIndexes() {
        MongoRemoteSnapshotClient schemaClient =
                new MongoRemoteSnapshotClient(mongoClient, "testdb", "custom_snapshots", true);
        assertNotNull(schemaClient);
        verify(mongoDatabase).getCollection("custom_snapshots");
    }

    @Test
    void constructorRejectsNullMongoClient() {
        assertThrows(
                NullPointerException.class,
                () -> new MongoRemoteSnapshotClient(null, "testdb", null, false));
    }

    @Test
    void uploadStoresSnapshotData() throws Exception {
        byte[] data = "snapshot-content".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(data);

        client.upload("snap-1", in);

        verify(collection).replaceOne(any(Bson.class), any(Document.class), any());
    }

    @Test
    void uploadRejectsNullSnapshotId() {
        assertThrows(
                NullPointerException.class,
                () -> client.upload(null, new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void uploadRejectsNullData() {
        assertThrows(NullPointerException.class, () -> client.upload("snap-1", null));
    }

    @Test
    void uploadRejectsOversizedData() {
        // Create a stream that exceeds MAX_SNAPSHOT_BYTES (15 MB)
        InputStream oversized =
                new InputStream() {
                    private int totalRead = 0;
                    private final int maxBytes = 15 * 1024 * 1024 + 1;

                    @Override
                    public int read() {
                        if (totalRead >= maxBytes) {
                            return -1;
                        }
                        totalRead++;
                        return 'x';
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        if (totalRead >= maxBytes) {
                            return -1;
                        }
                        int toRead = Math.min(len, maxBytes - totalRead);
                        totalRead += toRead;
                        Arrays.fill(b, off, off + toRead, (byte) 'x');
                        return toRead;
                    }
                };

        assertThrows(IOException.class, () -> client.upload("snap-1", oversized));
    }

    @Test
    void downloadReturnsSnapshotData() throws Exception {
        byte[] expected = "hello-snapshot".getBytes(StandardCharsets.UTF_8);
        Document doc = new Document("data", new Binary(expected));
        when(findIterable.first()).thenReturn(doc);

        InputStream result = client.download("snap-1");

        assertNotNull(result);
        byte[] actual = result.readAllBytes();
        assertArrayEquals(expected, actual);
    }

    @Test
    void downloadThrowsWhenSnapshotNotFound() {
        when(findIterable.first()).thenReturn(null);

        assertThrows(FileNotFoundException.class, () -> client.download("missing-snap"));
    }

    @Test
    void downloadThrowsFileNotFoundWhenDataFieldMissing() {
        // Document exists but carries no data field — must surface as FileNotFoundException
        // (same as a missing snapshot), not as an NPE.
        Document docWithoutData = new Document("_id", "snap-1");
        when(findIterable.first()).thenReturn(docWithoutData);

        assertThrows(FileNotFoundException.class, () -> client.download("snap-1"));
    }

    @Test
    void initSchemaMigratesConflictingTtlIndex() {
        // Simulate an existing collection whose createdAt TTL index still has the old 7-day
        // expireAfterSeconds: createIndex then fails with IndexOptionsConflict (error 85) and
        // must trigger drop + recreate instead of silently keeping the stale TTL.
        MongoCommandException conflict = mock(MongoCommandException.class);
        when(conflict.getErrorCode()).thenReturn(85);
        when(collection.createIndex(any(Bson.class), any(IndexOptions.class)))
                .thenThrow(conflict)
                .thenReturn("createdAt_1");

        new MongoRemoteSnapshotClient(mongoClient, "testdb", "snap_migrate", true);

        verify(collection).dropIndex("createdAt_1");
        verify(collection, times(2)).createIndex(any(Bson.class), any(IndexOptions.class));
    }

    @Test
    void downloadRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.download(null));
    }

    @Test
    void existsReturnsTrueWhenSnapshotFound() throws Exception {
        when(findIterable.first()).thenReturn(new Document("_id", "snap-1"));

        assertTrue(client.exists("snap-1"));
    }

    @Test
    void existsReturnsFalseWhenSnapshotNotFound() throws Exception {
        when(findIterable.first()).thenReturn(null);

        assertFalse(client.exists("snap-1"));
    }

    @Test
    void existsRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.exists(null));
    }

    @Test
    void deleteReturnsTrueWhenSnapshotDeleted() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        assertTrue(client.delete("snap-1"));
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void deleteReturnsFalseWhenSnapshotNotFound() throws Exception {
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(0L);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        assertFalse(client.delete("missing-snap"));
    }

    @Test
    void deleteRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.delete(null));
    }
}
