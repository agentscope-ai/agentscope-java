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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoGridFSException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSDownloadStream;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.result.DeleteResult;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.bson.BsonObjectId;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoRemoteSnapshotClientTest {

    @Mock private GridFSBucket gridFSBucket;
    @Mock private MongoCollection<Document> legacyCollection;

    @SuppressWarnings("rawtypes")
    @Mock
    private GridFSFindIterable gridFSFindIterable;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable legacyFindIterable;

    private AutoCloseable mocks;
    private MongoRemoteSnapshotClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        when(gridFSBucket.find(any(Bson.class))).thenReturn(gridFSFindIterable);
        when(gridFSFindIterable.first()).thenReturn(null);

        when(legacyCollection.find(any(Bson.class))).thenReturn(legacyFindIterable);
        when(legacyFindIterable.projection(any())).thenReturn(legacyFindIterable);
        when(legacyFindIterable.first()).thenReturn(null);

        // Mock legacy deleteOne for deleteLegacy()
        DeleteResult legacyDeleteResult = mock(DeleteResult.class);
        when(legacyDeleteResult.getDeletedCount()).thenReturn(0L);
        when(legacyCollection.deleteOne(any(Bson.class))).thenReturn(legacyDeleteResult);

        client = new MongoRemoteSnapshotClient(gridFSBucket, legacyCollection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void constructorWithPublicApiCreatesClient() {
        MongoClient mongoClient = mock(MongoClient.class);
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> filesColl = mock(MongoCollection.class);
        @SuppressWarnings("unchecked")
        MongoCollection<Document> chunksColl = mock(MongoCollection.class);
        when(mongoDatabase.getCollection(anyString())).thenReturn(filesColl);
        when(mongoDatabase.getCollection(anyString(), any(Class.class)))
                .thenReturn(filesColl, chunksColl);
        when(filesColl.withCodecRegistry(any())).thenReturn(filesColl);
        when(chunksColl.withCodecRegistry(any())).thenReturn(chunksColl);

        MongoRemoteSnapshotClient publicClient =
                new MongoRemoteSnapshotClient(mongoClient, "testdb", null);
        assertNotNull(publicClient);
    }

    @Test
    void constructorRejectsNullMongoClient() {
        assertThrows(
                NullPointerException.class,
                () -> new MongoRemoteSnapshotClient(null, "testdb", null));
    }

    @Test
    void uploadStoresSnapshotData() throws Exception {
        byte[] data = "snapshot-content".getBytes(StandardCharsets.UTF_8);
        InputStream in = new ByteArrayInputStream(data);

        client.upload("snap-1", in);

        verify(gridFSBucket).uploadFromStream(eq("snap-1"), any(InputStream.class), any());
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
    void downloadReturnsGridFSStream() throws Exception {
        GridFSDownloadStream downloadStream = mock(GridFSDownloadStream.class);
        when(downloadStream.read(any(byte[].class), any(int.class), any(int.class)))
                .thenReturn(5)
                .thenReturn(-1);
        when(gridFSBucket.openDownloadStream("snap-1")).thenReturn(downloadStream);

        InputStream result = client.download("snap-1");
        assertNotNull(result);
    }

    @Test
    void downloadFallsBackToLegacy() throws Exception {
        when(gridFSBucket.openDownloadStream("snap-1"))
                .thenThrow(new MongoGridFSException("File not found"));

        byte[] expected = "legacy-data".getBytes(StandardCharsets.UTF_8);
        Document legacyDoc = new Document("data", new Binary(expected));
        when(legacyFindIterable.first()).thenReturn(legacyDoc);

        InputStream result = client.download("snap-1");
        assertNotNull(result);
        assertEquals(expected.length, result.readAllBytes().length);
    }

    @Test
    void downloadThrowsWhenNotFoundAnywhere() {
        when(gridFSBucket.openDownloadStream("missing"))
                .thenThrow(new MongoGridFSException("File not found"));

        assertThrows(FileNotFoundException.class, () -> client.download("missing"));
    }

    @Test
    void downloadRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.download(null));
    }

    @Test
    void existsReturnsTrueWhenInGridFS() throws Exception {
        GridFSFile mockFile = mock(GridFSFile.class);
        when(gridFSFindIterable.first()).thenReturn(mockFile);

        assertTrue(client.exists("snap-1"));
    }

    @Test
    void existsReturnsTrueWhenInLegacy() throws Exception {
        when(gridFSFindIterable.first()).thenReturn(null);
        when(legacyFindIterable.first()).thenReturn(new Document("_id", "snap-1"));

        assertTrue(client.exists("snap-1"));
    }

    @Test
    void existsReturnsFalseWhenNotFoundAnywhere() throws Exception {
        when(gridFSFindIterable.first()).thenReturn(null);
        when(legacyFindIterable.first()).thenReturn(null);

        assertFalse(client.exists("snap-1"));
    }

    @Test
    void existsRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.exists(null));
    }

    @Test
    void deleteReturnsTrueWhenDeletedFromGridFS() throws Exception {
        ObjectId fileId = new ObjectId();
        GridFSFile mockFile = mock(GridFSFile.class);
        when(mockFile.getId()).thenReturn(new BsonObjectId(fileId));
        when(gridFSFindIterable.first()).thenReturn(mockFile);

        assertTrue(client.delete("snap-1"));
        verify(gridFSBucket).delete((BsonValue) any());
    }

    @Test
    void deleteReturnsFalseWhenNotFoundAnywhere() throws Exception {
        when(gridFSFindIterable.first()).thenReturn(null);
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(0L);
        when(legacyCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        assertFalse(client.delete("missing-snap"));
    }

    @Test
    void deleteRejectsNullSnapshotId() {
        assertThrows(NullPointerException.class, () -> client.delete(null));
    }
}
