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
package io.agentscope.core.shutdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShutdownStateSaverRetentionTest {

    private final GracefulShutdownManager manager = GracefulShutdownManager.getInstance();

    @BeforeEach
    void setUp() {
        manager.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        manager.resetForTesting();
    }

    @Test
    void discardedAgentsAndStoresAreCollectedAndRegistrationsAreExpunged() throws Exception {
        List<WeakReference<ReActAgent>> agents = new ArrayList<>();
        List<WeakReference<InMemoryAgentStateStore>> stores = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                // Intentionally omit close: a forgotten lifecycle call must not leave the
                // entire agent graph rooted in the process-wide shutdown manager.
                createDiscardedAgent(agents, stores);
            }
            awaitCollected(agents);
            awaitCollected(stores);
            // Normal registry traffic must also reclaim the dead UUID/registration entries.
            try (ReActAgent live = newAgent(new InMemoryAgentStateStore())) {
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                do {
                    String requestId = manager.registerRequest(live);
                    manager.unregisterRequest(requestId);
                    if (registrationCount() == 1) break;
                    Thread.sleep(20);
                } while (System.nanoTime() < deadline);
                assertEquals(1, registrationCount());
            }
            assertEquals(0, registrationCount());
        } finally {
            for (WeakReference<ReActAgent> reference : agents) {
                ReActAgent agent = reference.get();
                if (agent != null) agent.close();
            }
        }
    }

    @Test
    void activeRequestRetainsAgentAndSavesItsBoundSessionUntilUnregistered() throws Exception {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        AtomicReference<String> requestId = new AtomicReference<>();
        WeakReference<ReActAgent> reference = createActiveAgent(store, requestId);
        try {
            System.gc();
            assertNotNull(reference.get(), "An in-flight request must keep its agent alive");
            manager.saveOnInterruptObserved(requestId.get());
            AgentState saved =
                    store.get("user", "active", "agent_state", AgentState.class).orElseThrow();
            assertTrue(saved.isShutdownInterrupted());
            assertTrue(store.get("user", "other", "agent_state", AgentState.class).isEmpty());
            // A second save verifies that the callback still updates the agent's CAS version.
            manager.saveOnInterruptObserved(requestId.get());
            assertEquals(
                    2,
                    store.getVersioned("user", "active", "agent_state", AgentState.class)
                            .version());
            manager.unregisterRequest(requestId.get());
            awaitCollected(List.of(reference));
        } finally {
            manager.unregisterRequest(requestId.get());
            ReActAgent agent = reference.get();
            if (agent != null) agent.close();
        }
    }

    @Test
    void liveAgentKeepsCustomSaverAndActiveRequestKeepsSnapshotAfterUnbind() {
        try (ReActAgent agent = newAgent(new InMemoryAgentStateStore())) {
            AtomicReference<AgentState> saved = new AtomicReference<>();
            WeakReference<ShutdownStateSaver> saver = bindCustomSaver(agent, saved);
            System.gc();
            assertNotNull(saver.get(), "A live agent's inline callback must not disappear on GC");
            String requestId = manager.registerRequest(agent);
            try {
                AgentState state = agent.getAgentState("user", "session");
                manager.bindRequestState(requestId, state);
                manager.unbindStateSaver(agent);
                manager.saveOnInterruptObserved(requestId);
                assertSame(state, saved.get());
            } finally {
                manager.unregisterRequest(requestId);
            }
            Reference.reachabilityFence(agent);
        }
    }

    private void createDiscardedAgent(
            List<WeakReference<ReActAgent>> agents,
            List<WeakReference<InMemoryAgentStateStore>> stores) {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        ReActAgent agent = newAgent(store);
        agent.getAgentState("user", "session");
        agents.add(new WeakReference<>(agent));
        stores.add(new WeakReference<>(store));
    }

    private WeakReference<ReActAgent> createActiveAgent(
            InMemoryAgentStateStore store, AtomicReference<String> requestId) {
        ReActAgent agent = newAgent(store);
        AgentState active = agent.getAgentState("user", "active");
        agent.getAgentState("user", "other");
        requestId.set(manager.registerRequest(agent));
        manager.bindRequestState(requestId.get(), active);
        return new WeakReference<>(agent);
    }

    private WeakReference<ShutdownStateSaver> bindCustomSaver(
            ReActAgent agent, AtomicReference<AgentState> saved) {
        ShutdownStateSaver saver = saved::set;
        manager.bindStateSaver(agent, saver);
        return new WeakReference<>(saver);
    }

    private static ReActAgent newAgent(InMemoryAgentStateStore store) {
        return ReActAgent.builder()
                .name("default")
                .model(mock(Model.class))
                .stateStore(store)
                .build();
    }

    private int registrationCount() throws Exception {
        Field field = GracefulShutdownManager.class.getDeclaredField("stateSavers");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(manager)).size();
    }

    private static void awaitCollected(List<? extends WeakReference<?>> references)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            System.gc();
            if (references.stream().allMatch(reference -> reference.refersTo(null))) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertTrue(
                references.stream().allMatch(reference -> reference.refersTo(null)),
                "The shutdown registry must not retain discarded agents or their state stores");
    }
}
