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
package io.agentscope.extensions.mongodb.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.DistinctIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoAgentStateStoreTest {

    record TestState(String value) implements State {}

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    @SuppressWarnings("rawtypes")
    @Mock
    private DistinctIterable distinctIterable;

    private AutoCloseable mocks;
    private MongoAgentStateStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.limit(org.mockito.ArgumentMatchers.anyInt())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        UpdateResult updateResult = mock(UpdateResult.class);
        when(updateResult.wasAcknowledged()).thenReturn(true);
        when(collection.updateOne(any(Bson.class), any(Bson.class), any()))
                .thenReturn(updateResult);
        when(collection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(updateResult);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.wasAcknowledged()).thenReturn(true);
        when(collection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        when(collection.countDocuments(any(Bson.class))).thenReturn(0L);

        when(collection.distinct(anyString(), any(Bson.class), any(Class.class)))
                .thenReturn(distinctIterable);
        when(distinctIterable.into(any())).thenReturn(new java.util.ArrayList<>());

        store =
                MongoAgentStateStore.builder()
                        .mongoClient(mongoClient)
                        .databaseName("testdb")
                        .collectionName("test_sessions")
                        .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void supportsVersioningReturnsTrue() {
        assertTrue(store.supportsVersioning());
    }

    @Test
    void builderRejectsMissingClientAndConnectionString() {
        assertThrows(IllegalArgumentException.class, () -> MongoAgentStateStore.builder().build());
    }

    @Test
    void builderWithMongoClientCreatesStore() {
        assertNotNull(store);
    }

    @Test
    void builderWithDefaultsCreatesStore() {
        MongoAgentStateStore defaultStore =
                MongoAgentStateStore.builder().mongoClient(mongoClient).build();
        assertNotNull(defaultStore);
    }

    @Test
    void saveSingleState() {
        store.save("user", "session", "key", new TestState("value"));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void getSingleStateReturnsEmptyWhenMissing() {
        Optional<TestState> result = store.get("user", "session", "key", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getSingleStateReturnsValueWhenPresent() {
        String json = "{\"value\":\"found\"}";
        Document doc = new Document("key", Document.parse(json));
        when(findIterable.first()).thenReturn(doc);

        Optional<TestState> result = store.get("user", "session", "key", TestState.class);
        assertTrue(result.isPresent());
        assertEquals("found", result.get().value());
    }

    @Test
    void saveListState() {
        store.save("user", "session", "list", List.of(new TestState("a"), new TestState("b")));
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void getListReturnsEmptyWhenMissing() {
        List<TestState> result = store.getList("user", "session", "list", TestState.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void getListReturnsValuesWhenPresent() {
        List<Document> list =
                List.of(Document.parse("{\"value\":\"a\"}"), Document.parse("{\"value\":\"b\"}"));
        Document doc = new Document("list:list", list);
        when(findIterable.first()).thenReturn(doc);

        List<TestState> result = store.getList("user", "session", "list", TestState.class);
        assertEquals(2, result.size());
        assertEquals("a", result.get(0).value());
        assertEquals("b", result.get(1).value());
    }

    @Test
    void getVersionedReturnsZeroWhenMissing() {
        VersionedState<TestState> result =
                store.getVersioned("user", "session", "key", TestState.class);
        assertNotNull(result);
        assertEquals(0L, result.version());
    }

    @Test
    void getVersionedReturnsValueAndVersion() {
        String json = "{\"value\":\"v1\"}";
        Document doc = new Document("key", Document.parse(json)).append("_version_key", 5L);
        when(findIterable.first()).thenReturn(doc);

        VersionedState<TestState> result =
                store.getVersioned("user", "session", "key", TestState.class);
        assertEquals(5L, result.version());
        assertEquals("v1", result.value().value());
    }

    @Test
    void saveIfVersionWithUnversionedDelegatesToSave() {
        store.saveIfVersion(
                "user", "session", "key", new TestState("v"), AgentStateStore.UNVERSIONED);
        verify(collection).updateOne(any(Bson.class), any(Bson.class), any());
    }

    @Test
    void existsReturnsFalseWhenNoDocument() {
        when(findIterable.first()).thenReturn(null);
        assertFalse(store.exists("user", "session"));
    }

    @Test
    void existsReturnsTrueWhenDocumentExists() {
        when(findIterable.first()).thenReturn(new Document("_id", "__anon__:session"));
        assertTrue(store.exists("user", "session"));
    }

    @Test
    void deleteSession() {
        store.delete("user", "session");
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void deleteKey() {
        store.delete("user", "session", "key");
        verify(collection).updateOne(any(Bson.class), any(Document.class));
    }

    @Test
    void listSessionIdsReturnsEmptyWhenNone() {
        Set<String> ids = store.listSessionIds("user");
        assertTrue(ids.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSessionIdsReturnsIds() {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>(List.of("s1", "s2"));
        when(distinctIterable.into(any())).thenReturn(ids);

        Set<String> result = store.listSessionIds("user");
        assertEquals(2, result.size());
        assertTrue(result.contains("s1"));
        assertTrue(result.contains("s2"));
    }

    @Test
    void rejectsNullSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", null, "key", new TestState("v")));
    }

    @Test
    void rejectsBlankSessionId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "  ", "key", new TestState("v")));
    }

    @Test
    void rejectsKeyWithDot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "bad.key", new TestState("v")));
    }

    @Test
    void rejectsKeyWithDollar() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "$bad", new TestState("v")));
    }

    @Test
    void rejectsBlankKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save("user", "session", "  ", new TestState("v")));
    }

    @Test
    void closeWithExternalClientDoesNotCloseClient() {
        store.close();
        verify(mongoClient, org.mockito.Mockito.never()).close();
    }
}
