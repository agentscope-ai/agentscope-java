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
package io.agentscope.extensions.mongodb.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Contract tests for optimistic concurrency on {@link AgentStateStore} against a real MongoDB.
 *
 * <p>Mirrors the canonical contract defined in {@code AgentStateStoreVersioningContractTest}
 * (agentscope-core). Skipped automatically when MongoDB is not reachable at {@code
 * localhost:27017}.
 */
@DisplayName("AgentStateStore versioning contract — MongoDB")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongoAgentStateStoreContractTest {

    private static final String USER = "contract-user";
    private static final String SESSION = "contract-session";

    private static MongoClient mongoClient;
    private static String dbName;

    private AgentStateStore store;

    @BeforeAll
    static void connectMongo() {
        try {
            mongoClient = MongoClients.create("mongodb://localhost:27017");
            mongoClient.getDatabase("ping").runCommand(new Document("ping", 1));
        } catch (Exception e) {
            Assumptions.abort("MongoDB not available: " + e.getMessage());
        }
        dbName = "test_state_contract_" + System.currentTimeMillis();
    }

    @AfterAll
    static void disconnectMongo() {
        if (mongoClient != null) {
            mongoClient.getDatabase(dbName).drop();
            mongoClient.close();
        }
    }

    @BeforeEach
    void setUp() {
        store =
                MongoAgentStateStore.builder()
                        .mongoClient(mongoClient)
                        .databaseName(dbName)
                        .collectionName("test_sessions")
                        .build();
    }

    @AfterEach
    void cleanSession() {
        store.delete(USER, SESSION);
    }

    @Test
    @Order(1)
    @DisplayName("supportsVersioning is true")
    void supportsVersioning() {
        assertTrue(store.supportsVersioning());
    }

    @Test
    @Order(2)
    @DisplayName("getVersioned on absent key returns null value and version 0")
    void getVersioned_absent_returnsVersionZero() {
        VersionedState<TestState> versioned =
                store.getVersioned(USER, SESSION, "agent_state", TestState.class);

        assertNull(versioned.value());
        assertEquals(0L, versioned.version());
    }

    @Test
    @Order(3)
    @DisplayName("saveIfVersion with expectedVersion 0 creates if absent")
    void saveIfVersion_createIfAbsent() {
        TestState initial = new TestState("created");

        long version = store.saveIfVersion(USER, SESSION, "agent_state", initial, 0L);
        assertEquals(1L, version);

        VersionedState<TestState> loaded =
                store.getVersioned(USER, SESSION, "agent_state", TestState.class);
        assertEquals("created", loaded.value().value());
        assertEquals(1L, loaded.version());

        long conflict =
                store.saveIfVersion(USER, SESSION, "agent_state", new TestState("lost"), 0L);
        assertEquals(AgentStateStore.UNVERSIONED, conflict);
        assertEquals(
                "created", store.get(USER, SESSION, "agent_state", TestState.class).get().value());
    }

    @Test
    @Order(4)
    @DisplayName("saveIfVersion with UNVERSIONED unconditionally overwrites and bumps version")
    void saveIfVersion_unconditionalOverwrite() {
        store.save(USER, SESSION, "agent_state", new TestState("v1"));
        VersionedState<TestState> afterFirst =
                store.getVersioned(USER, SESSION, "agent_state", TestState.class);
        assertEquals(1L, afterFirst.version());

        long newVersion =
                store.saveIfVersion(
                        USER,
                        SESSION,
                        "agent_state",
                        new TestState("v2"),
                        AgentStateStore.UNVERSIONED);
        assertEquals(2L, newVersion);

        VersionedState<TestState> loaded =
                store.getVersioned(USER, SESSION, "agent_state", TestState.class);
        assertEquals("v2", loaded.value().value());
        assertEquals(2L, loaded.version());
    }

    @Test
    @Order(5)
    @DisplayName("plain save bumps version")
    void plainSave_bumpsVersion() {
        store.save(USER, SESSION, "agent_state", new TestState("one"));
        assertEquals(
                1L, store.getVersioned(USER, SESSION, "agent_state", TestState.class).version());

        store.save(USER, SESSION, "agent_state", new TestState("two"));
        assertEquals(
                2L, store.getVersioned(USER, SESSION, "agent_state", TestState.class).version());
    }

    @Test
    @Order(6)
    @DisplayName("concurrent writers with same expected version: only one succeeds")
    void concurrentWriters_onlyOneSucceeds() throws InterruptedException {
        store.saveIfVersion(USER, SESSION, "agent_state", new TestState("baseline"), 0L);
        long observed = store.getVersioned(USER, SESSION, "agent_state", TestState.class).version();
        assertEquals(1L, observed);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable attempt =
                () -> {
                    ready.countDown();
                    try {
                        start.await();
                        long result =
                                store.saveIfVersion(
                                        USER,
                                        SESSION,
                                        "agent_state",
                                        new TestState("winner"),
                                        observed);
                        if (result != AgentStateStore.UNVERSIONED) {
                            successes.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };

        pool.submit(attempt);
        pool.submit(attempt);
        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, successes.get());
        assertEquals(
                2L, store.getVersioned(USER, SESSION, "agent_state", TestState.class).version());
    }

    record TestState(String value) implements State {}
}
