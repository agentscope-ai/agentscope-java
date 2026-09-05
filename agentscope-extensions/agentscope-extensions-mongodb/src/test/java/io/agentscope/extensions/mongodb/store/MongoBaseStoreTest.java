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
package io.agentscope.extensions.mongodb.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoBaseStoreTest {

    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    private AutoCloseable mocks;
    private MongoBaseStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.limit(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
        when(findIterable.into(any())).thenReturn(new ArrayList<>());

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.wasAcknowledged()).thenReturn(true);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenReturn(updateResult);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.wasAcknowledged()).thenReturn(true);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        store = new MongoBaseStore(mongoDatabase, "test_base");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void constructorCreatesStore() {
        assertNotNull(store);
        verify(mongoDatabase).getCollection("test_base");
    }

    @Test
    void getReturnsNullWhenNotFound() {
        StoreItem item = store.get(List.of("ns"), "key");
        assertNull(item);
    }

    @Test
    void getReturnsItemWhenFound() {
        Document doc =
                new Document()
                        .append("key", "mykey")
                        .append("value", new Document("data", "hello"))
                        .append("version", 3L);
        when(findIterable.first()).thenReturn(doc);

        StoreItem item = store.get(List.of("ns"), "mykey");
        assertNotNull(item);
        assertEquals("mykey", item.key());
        assertEquals(3L, item.version());
        assertEquals("hello", item.value().get("data"));
    }

    @Test
    void putStoresItem() {
        store.put(List.of("ns"), "key", Map.of("data", "value"));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void putIfVersionReturnsFalseWhenVersionMismatch() {
        // findOneAndUpdate returns null when version filter doesn't match
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 5L);

        assertFalse(result);
    }

    @Test
    void putIfVersionZeroCreatesWhenAbsent() {
        // insertOne succeeds -> new document created
        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L);

        assertTrue(result);
        verify(collection).insertOne(any(Document.class));
    }

    @Test
    void putIfVersionZeroReturnsFalseWhenAlreadyExists() {
        // insertOne throws E11000 duplicate key -> document already exists
        WriteError writeError =
                new WriteError(11000, "E11000 duplicate key error", new BsonDocument());
        doThrow(new MongoWriteException(writeError, new ServerAddress()))
                .when(collection)
                .insertOne(any(Document.class));

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L);

        assertFalse(result);
    }

    @Test
    void putIfVersionZeroPropagatesNonDuplicateKeyError() {
        // insertOne throws a non-duplicate-key error -> should propagate
        WriteError writeError = new WriteError(12345, "some other error", new BsonDocument());
        doThrow(new MongoWriteException(writeError, new ServerAddress()))
                .when(collection)
                .insertOne(any(Document.class));

        assertThrows(
                MongoWriteException.class,
                () -> store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 0L));
    }

    @Test
    void putIfVersionSuccessWhenVersionMatches() {
        // findOneAndUpdate returns doc with new version = expectedVersion + 1
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(new Document("_id", "ns\0key").append("version", 3L));

        boolean result = store.putIfVersion(List.of("ns"), "key", Map.of("data", "v"), 2L);

        assertTrue(result);
    }

    @Test
    void searchReturnsEmptyList() {
        List<StoreItem> items = store.search(List.of("ns"), 10, 0);
        assertTrue(items.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchReturnsItems() {
        Document doc1 =
                new Document()
                        .append("key", "a")
                        .append("value", new Document("x", "1"))
                        .append("version", 1L);
        Document doc2 =
                new Document()
                        .append("key", "b")
                        .append("value", new Document("x", "2"))
                        .append("version", 2L);
        ArrayList<Document> docs = new ArrayList<>(List.of(doc1, doc2));
        when(findIterable.into(any())).thenReturn(docs);

        List<StoreItem> items = store.search(List.of("ns"), 10, 0);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).key());
        assertEquals("b", items.get(1).key());
    }

    @Test
    void deleteRemovesItem() {
        store.delete(List.of("ns"), "key");
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void rejectsNullMongoDatabase() {
        assertThrows(NullPointerException.class, () -> new MongoBaseStore(null, "test"));
    }
}
