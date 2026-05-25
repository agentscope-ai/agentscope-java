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
package io.agentscope.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class InMemoryStorageTest {

    @Test
    void loadReturnsEmptyForUnknownKey() {
        InMemoryStorage storage = new InMemoryStorage();
        StepVerifier.create(storage.loadAgentState("s1", "a1")).verifyComplete();
    }

    @Test
    void saveThenLoadRoundTripReturnsSameState() {
        InMemoryStorage storage = new InMemoryStorage();
        AgentState state =
                AgentState.builder()
                        .sessionId("session-1")
                        .summary("hello world")
                        .addMessage(Msg.builder().role(MsgRole.USER).textContent("hi").build())
                        .build();

        StepVerifier.create(storage.saveAgentState("session-1", "agent-1", state)).verifyComplete();

        StepVerifier.create(storage.loadAgentState("session-1", "agent-1"))
                .assertNext(
                        loaded -> {
                            assertEquals("session-1", loaded.getSessionId());
                            assertEquals("hello world", loaded.getSummary());
                            assertEquals(1, loaded.getContext().size());
                        })
                .verifyComplete();

        assertEquals(1, storage.size());
    }

    @Test
    void saveOverwritesPreviousEntryForSameKey() {
        InMemoryStorage storage = new InMemoryStorage();
        AgentState first = AgentState.builder().summary("first").build();
        AgentState second = AgentState.builder().summary("second").build();

        storage.saveAgentState("s", "a", first).block();
        storage.saveAgentState("s", "a", second).block();

        AgentState loaded = storage.loadAgentState("s", "a").block();
        assertNotNull(loaded);
        assertEquals("second", loaded.getSummary());
        assertEquals(1, storage.size());
    }

    @Test
    void differentSessionAndAgentKeysDoNotCollide() {
        InMemoryStorage storage = new InMemoryStorage();
        AgentState a = AgentState.builder().summary("A").build();
        AgentState b = AgentState.builder().summary("B").build();
        AgentState c = AgentState.builder().summary("C").build();

        storage.saveAgentState("s1", "a1", a).block();
        storage.saveAgentState("s1", "a2", b).block();
        storage.saveAgentState("s2", "a1", c).block();

        assertEquals(3, storage.size());
        assertEquals("A", storage.loadAgentState("s1", "a1").block().getSummary());
        assertEquals("B", storage.loadAgentState("s1", "a2").block().getSummary());
        assertEquals("C", storage.loadAgentState("s2", "a1").block().getSummary());
    }

    @Test
    void saveRejectsNullState() {
        InMemoryStorage storage = new InMemoryStorage();
        assertThrows(
                NullPointerException.class, () -> storage.saveAgentState("s", "a", null).block());
    }

    @Test
    void nullSessionIdOrAgentIdRejected() {
        InMemoryStorage storage = new InMemoryStorage();
        AgentState state = AgentState.builder().build();

        assertThrows(
                NullPointerException.class, () -> storage.saveAgentState(null, "a", state).block());
        assertThrows(
                NullPointerException.class, () -> storage.saveAgentState("s", null, state).block());
        assertThrows(NullPointerException.class, () -> storage.loadAgentState(null, "a").block());
        assertThrows(NullPointerException.class, () -> storage.loadAgentState("s", null).block());
    }

    @Test
    void clearRemovesAllEntries() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.saveAgentState("s", "a", AgentState.builder().build()).block();
        storage.saveAgentState("s", "b", AgentState.builder().build()).block();
        assertEquals(2, storage.size());
        storage.clear();
        assertEquals(0, storage.size());
    }

    @Test
    void concurrentSavesAndLoadsAreThreadSafe() throws InterruptedException {
        InMemoryStorage storage = new InMemoryStorage();
        int writers = 16;
        int writesPerWriter = 50;
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        Flux.range(0, writers)
                .flatMap(
                        i ->
                                Flux.range(0, writesPerWriter)
                                        .flatMap(
                                                j ->
                                                        storage.saveAgentState(
                                                                "session-" + i,
                                                                "agent-" + j,
                                                                AgentState.builder()
                                                                        .summary("w" + i + "j" + j)
                                                                        .build()))
                                        .subscribeOn(Schedulers.parallel()))
                .doOnError(e -> errors.incrementAndGet())
                .doFinally(sig -> done.countDown())
                .subscribe();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertEquals(writers * writesPerWriter, storage.size());

        AgentState loaded = storage.loadAgentState("session-3", "agent-7").block();
        assertNotNull(loaded);
        assertEquals("w3j7", loaded.getSummary());
    }
}
