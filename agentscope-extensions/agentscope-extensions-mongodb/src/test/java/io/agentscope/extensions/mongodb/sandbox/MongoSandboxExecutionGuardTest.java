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
package io.agentscope.extensions.mongodb.sandbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import java.time.Duration;
import java.util.Date;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class MongoSandboxExecutionGuardTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    @SuppressWarnings("rawtypes")
    @Mock
    private FindIterable findIterable;

    private AutoCloseable mocks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(mongoClient.getDatabase(anyString())).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(collection);

        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any())).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    private SandboxIsolationKey key() {
        return SandboxIsolationKey.resolve(
                        IsolationScope.SESSION,
                        new io.agentscope.core.agent.RuntimeContext.Builder()
                                .sessionId("session-1")
                                .build(),
                        "agent")
                .orElseThrow();
    }

    @Test
    void builderRejectsNullMongoClient() {
        assertThrows(NullPointerException.class, () -> MongoSandboxExecutionGuard.builder(null));
    }

    @Test
    void builderWithDefaultsCreatesGuard() {
        MongoSandboxExecutionGuard guard = MongoSandboxExecutionGuard.builder(mongoClient).build();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomDatabaseName() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient).databaseName("custom_db").build();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomCollectionName() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .collectionName("custom_locks")
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderWithCustomTimeout() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderRejectsNonPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MongoSandboxExecutionGuard.builder(mongoClient)
                                .lockTimeout(Duration.ZERO)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MongoSandboxExecutionGuard.builder(mongoClient)
                                .lockTimeout(Duration.ofSeconds(-1))
                                .build());
    }

    @Test
    void builderWithCustomRetryInterval() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .retryInterval(Duration.ofMillis(200))
                        .build();
        assertNotNull(guard);
    }

    @Test
    void builderRejectsNonPositiveRetryInterval() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MongoSandboxExecutionGuard.builder(mongoClient)
                                .retryInterval(Duration.ZERO)
                                .build());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MongoSandboxExecutionGuard.builder(mongoClient)
                                .retryInterval(Duration.ofMillis(-1))
                                .build());
    }

    @Test
    void builderWithCustomOwner() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient).owner("custom-owner").build();
        assertNotNull(guard);
    }

    @Test
    void tryEnterAcquiresLockViaInsert() throws Exception {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        // insertOne succeeds — no duplicate key → lock acquired immediately
        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        verify(collection).insertOne(any(Document.class));
    }

    @Test
    void tryEnterReclaimsExpiredLock() throws Exception {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        // Step 1: insertOne fails with duplicate key → lock doc exists
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // Step 2: findOneAndUpdate succeeds → lock was expired, we reclaimed it
        Document reclaimed =
                new Document("_id", "lock:abc")
                        .append("owner", "old-owner")
                        .append("expiresAt", new Date(0L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(reclaimed);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        verify(collection).insertOne(any(Document.class));
        verify(collection)
                .findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class));
    }

    @Test
    void tryEnterPollsWhenLockHeldThenAcquires() throws Exception {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(10))
                        .build();
        // insertOne always fails with duplicate key
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // First findOneAndUpdate returns null (lock not expired), second returns reclaimed doc
        Document reclaimed =
                new Document("_id", "lock:abc")
                        .append("owner", "old-owner")
                        .append("expiresAt", new Date(0L));
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null)
                .thenReturn(reclaimed);

        SandboxLease lease = guard.tryEnter(key());

        assertNotNull(lease);
        // insertOne called twice (once per poll iteration)
        verify(collection, times(2)).insertOne(any(Document.class));
    }

    @Test
    void tryEnterTimesOutWhenLockNeverAcquired() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofMillis(200))
                        .build();
        // insertOne always fails with duplicate key
        WriteError dupError = new WriteError(11000, "duplicate key", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(dupError, new ServerAddress()));
        // findOneAndUpdate always returns null (lock never expires)
        when(collection.findOneAndUpdate(
                        any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        assertThrows(InterruptedException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterPropagatesNonDuplicateKeyWriteException() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        // Non-duplicate-key write error from insertOne should propagate
        WriteError otherError = new WriteError(99999, "disk full", new BsonDocument());
        when(collection.insertOne(any(Document.class)))
                .thenThrow(new MongoWriteException(otherError, new ServerAddress()));

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void tryEnterPropagatesNonWriteException() {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        RuntimeException unexpected = new RuntimeException("connection lost");
        when(collection.insertOne(any(Document.class))).thenThrow(unexpected);

        assertThrows(RuntimeException.class, () -> guard.tryEnter(key()));
    }

    @Test
    void leaseCloseReleasesLockWithOwnerCheck() throws Exception {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        SandboxLease lease = guard.tryEnter(key());
        lease.close();

        // deleteOne should filter by both _id AND owner
        verify(collection).deleteOne(any(Bson.class));
    }

    @Test
    void leaseCloseHandlesReleaseFailure() throws Exception {
        MongoSandboxExecutionGuard guard =
                MongoSandboxExecutionGuard.builder(mongoClient)
                        .lockTimeout(Duration.ofSeconds(5))
                        .build();
        SandboxLease lease = guard.tryEnter(key());
        doThrow(new RuntimeException("network error")).when(collection).deleteOne(any(Bson.class));

        // Should not throw — close() swallows errors
        lease.close();
    }
}
