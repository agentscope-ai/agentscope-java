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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ConcurrentSessionModificationException;
import io.agentscope.core.state.ConflictPolicy;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.State;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Boundary tests for persisting {@code agent_state} when the expected version is unknown
 * ({@link AgentStateStore#UNVERSIONED}): a competing writer's version must never be cached
 * for the next CAS, and ordinary slots always persist with known versions.
 */
class ReActAgentUnknownVersionPersistTest {
    private static ReActAgent agent(AgentStateStore store) {
        return ReActAgent.builder()
                .name("unconditional-persist")
                .model(
                        new ChatModelBase() {
                            @Override
                            public String getModelName() {
                                return "unused";
                            }

                            @Override
                            protected Flux<ChatResponse> doStream(
                                    List<Msg> messages,
                                    List<ToolSchema> tools,
                                    GenerateOptions options) {
                                return Flux.error(new AssertionError("No model call expected"));
                            }
                        })
                .stateStore(store)
                .conflictPolicy(ConflictPolicy.FAIL)
                .build();
    }

    /**
     * Invokes the private persistence helper with an unknown version. Reflection is deliberate:
     * ordinary public flows never produce an unknown version (see
     * {@link #ordinaryFreshAndReloadedSlotsAlwaysHaveVersions()}), so this recreates the
     * version-unknown window (e.g. a restart whose version cache is empty).
     */
    private static long persistUnknownVersion(ReActAgent agent, AgentState state) throws Exception {
        Method method =
                ReActAgent.class.getDeclaredMethod(
                        "persistAgentStateCas",
                        String.class,
                        String.class,
                        String.class,
                        AgentState.class,
                        long.class,
                        int.class);
        method.setAccessible(true);
        return (long) method.invoke(agent, "u", "s", "u/s", state, AgentStateStore.UNVERSIONED, 0);
    }

    @Test
    @DisplayName("an unknown-version persist must not poison the next public save under FAIL")
    void unknownVersionMustNotPoisonNextPublicSave() throws Exception {
        InterleavingStore store = new InterleavingStore();
        ReActAgent agent = agent(store);
        AgentState mine = agent.getAgentState("u", "s");
        store.injectOtherWrite = true;
        persistUnknownVersion(agent, mine);
        assertThrows(
                ConcurrentSessionModificationException.class, () -> agent.saveAgentState("u", "s"));
        assertEquals(1, agent.getStateConflictCount());
        assertSame(
                store.other, store.getVersioned("u", "s", "agent_state", AgentState.class).value());
    }

    @Test
    @DisplayName("ordinary fresh and reloaded slots always persist with known versions")
    void ordinaryFreshAndReloadedSlotsAlreadyHaveVersions() {
        InterleavingStore store = new InterleavingStore();
        ReActAgent first = agent(store);
        first.getAgentState("u", "s");
        first.saveAgentState("u", "s");
        ReActAgent restarted = agent(store);
        restarted.getAgentState("u", "s");
        restarted.saveAgentState("u", "s");
        assertEquals(List.of(0L, 1L), store.expectedVersions);
        assertEquals(0, store.plainSaves);
    }

    @Test
    @DisplayName("non-versioning backends keep the plain-save path")
    void nonVersioningBackendKeepsPlainSave() {
        InterleavingStore store =
                new InterleavingStore() {
                    @Override
                    public boolean supportsVersioning() {
                        return false;
                    }
                };
        ReActAgent agent = agent(store);
        AgentState mine = agent.getAgentState("u", "s");
        agent.saveAgentState("u", "s");
        assertEquals(1, store.plainSaves);
        assertEquals(List.of(), store.expectedVersions);
        assertSame(mine, store.getVersioned("u", "s", "agent_state", AgentState.class).value());
    }

    /**
     * Injects a legal competing commit after a successful write and captures the assigned version
     * atomically inside the versioned API, so the fixture is independent of store-level
     * UNVERSIONED handling. Deterministic interleaving injection, not a thread stress test.
     */
    private static class InterleavingStore extends InMemoryAgentStateStore {
        final AgentState other = AgentState.builder().userId("u").sessionId("s").build();
        final List<Long> expectedVersions = new ArrayList<>();
        boolean injectOtherWrite;
        int plainSaves;

        @Override
        public synchronized void save(String u, String s, String k, State value) {
            plainSaves++;
            super.save(u, s, k, value);
            inject(u, s, k);
        }

        @Override
        public synchronized long saveIfVersion(
                String u, String s, String k, State value, long expected) {
            expectedVersions.add(expected);
            long compare =
                    expected == UNVERSIONED
                            ? super.getVersioned(u, s, k, State.class).version()
                            : expected;
            long assigned = super.saveIfVersion(u, s, k, value, compare);
            if (assigned != UNVERSIONED) {
                inject(u, s, k);
            }
            return assigned;
        }

        private void inject(String u, String s, String k) {
            if (injectOtherWrite) {
                injectOtherWrite = false;
                super.save(u, s, k, other);
            }
        }
    }
}
