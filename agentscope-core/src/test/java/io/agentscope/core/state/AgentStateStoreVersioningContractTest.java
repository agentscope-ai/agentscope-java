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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for optimistic concurrency on {@link AgentStateStore} backends.
 *
 * <p>Runs against {@link InMemoryAgentStateStore} in core (no external services). Extension
 * modules with Docker-backed stores should add module-local tests when feasible.
 */
@DisplayName("AgentStateStore versioning contract")
class AgentStateStoreVersioningContractTest {

    private AgentStateStore store;

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    @AfterEach
    void tearDown() {
        cleanup(store);
    }

    @Test
    @DisplayName("supportsVersioning is true for versioning backends")
    void supportsVersioning() {
        assertTrue(store.supportsVersioning());
    }

    @Test
    @DisplayName("getVersioned on absent key returns null value and version 0")
    void getVersioned_absent_returnsVersionZero() {
        VersionedState<TestState> versioned =
                store.getVersioned("user", "session-1", "agent_state", TestState.class);

        assertNull(versioned.value());
        assertEquals(0L, versioned.version());
    }

    @Test
    @DisplayName("saveIfVersion with expectedVersion 0 creates if absent")
    void saveIfVersion_createIfAbsent() {
        TestState initial = new TestState("created");

        long version = store.saveIfVersion("user", "session-1", "agent_state", initial, 0L);
        assertEquals(1L, version);

        VersionedState<TestState> loaded =
                store.getVersioned("user", "session-1", "agent_state", TestState.class);
        assertEquals("created", loaded.value().value());
        assertEquals(1L, loaded.version());

        long conflict =
                store.saveIfVersion("user", "session-1", "agent_state", new TestState("lost"), 0L);
        assertEquals(AgentStateStore.UNVERSIONED, conflict);
        assertEquals(
                "created",
                store.get("user", "session-1", "agent_state", TestState.class).get().value());
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED unconditionally overwrites and bumps version")
    void saveIfVersion_unconditionalOverwrite() {
        store.save("user", "session-1", "agent_state", new TestState("v1"));
        VersionedState<TestState> afterFirst =
                store.getVersioned("user", "session-1", "agent_state", TestState.class);
        assertEquals(1L, afterFirst.version());

        long newVersion =
                store.saveIfVersion(
                        "user",
                        "session-1",
                        "agent_state",
                        new TestState("v2"),
                        AgentStateStore.UNVERSIONED);
        assertEquals(2L, newVersion);

        VersionedState<TestState> loaded =
                store.getVersioned("user", "session-1", "agent_state", TestState.class);
        assertEquals("v2", loaded.value().value());
        assertEquals(2L, loaded.version());
    }

    @Test
    @DisplayName("plain save bumps version")
    void plainSave_bumpsVersion() {
        store.save("user", "session-1", "agent_state", new TestState("one"));
        assertEquals(
                1L,
                store.getVersioned("user", "session-1", "agent_state", TestState.class).version());

        store.save("user", "session-1", "agent_state", new TestState("two"));
        assertEquals(
                2L,
                store.getVersioned("user", "session-1", "agent_state", TestState.class).version());
    }

    @Test
    @DisplayName("concurrent writers with same expected version: only one succeeds")
    void concurrentWriters_onlyOneSucceeds() throws InterruptedException {
        store.saveIfVersion("user", "session-1", "agent_state", new TestState("baseline"), 0L);
        long observed =
                store.getVersioned("user", "session-1", "agent_state", TestState.class).version();
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
                                        "user",
                                        "session-1",
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
                2L,
                store.getVersioned("user", "session-1", "agent_state", TestState.class).version());
    }

    protected AgentStateStore newStore() {
        return new InMemoryAgentStateStore();
    }

    protected void cleanup(AgentStateStore store) {
        if (store instanceof InMemoryAgentStateStore inMemory) {
            inMemory.clearAll();
        }
    }

    /** Simple test state for contract tests. */
    record TestState(String value) implements State {}
}
