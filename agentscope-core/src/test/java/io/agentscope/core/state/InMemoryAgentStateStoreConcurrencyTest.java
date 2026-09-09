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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class InMemoryAgentStateStoreConcurrencyTest {

    @Test
    @Timeout(15)
    void unconditionalWritersReturnTheirOwnVersions() throws Exception {
        CyclicBarrier bothWritesCompleted = new CyclicBarrier(2);
        InMemoryAgentStateStore store =
                new InMemoryAgentStateStore() {
                    @Override
                    public void save(String userId, String sessionId, String key, State value) {
                        super.save(userId, sessionId, key, value);
                        // Force both writes before either caller can read the version back.
                        // An atomic write-and-return does not need to call this method.
                        try {
                            bothWritesCompleted.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first =
                    pool.submit(
                            () ->
                                    store.saveIfVersion(
                                            "user",
                                            "session",
                                            "key",
                                            new TestState("first"),
                                            AgentStateStore.UNVERSIONED));
            Future<Long> second =
                    pool.submit(
                            () ->
                                    store.saveIfVersion(
                                            "user",
                                            "session",
                                            "key",
                                            new TestState("second"),
                                            AgentStateStore.UNVERSIONED));
            long firstVersion = first.get(10, TimeUnit.SECONDS);
            long secondVersion = second.get(10, TimeUnit.SECONDS);

            assertNotEquals(firstVersion, secondVersion);
            assertEquals(1L, Math.min(firstVersion, secondVersion));
            assertEquals(2L, Math.max(firstVersion, secondVersion));
            VersionedState<TestState> latest =
                    store.getVersioned("user", "session", "key", TestState.class);
            assertEquals(2L, latest.version());
            assertEquals(firstVersion > secondVersion ? "first" : "second", latest.value().value());

            assertEquals(
                    AgentStateStore.UNVERSIONED,
                    store.saveIfVersion(
                            "user",
                            "session",
                            "key",
                            new TestState("stale"),
                            Math.min(firstVersion, secondVersion)));
            assertEquals(latest, store.getVersioned("user", "session", "key", TestState.class));
        } finally {
            pool.shutdownNow();
        }
    }

    record TestState(String value) implements State {}
}
