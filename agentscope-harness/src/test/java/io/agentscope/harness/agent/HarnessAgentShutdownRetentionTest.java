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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@HarnessQuiescence
class HarnessAgentShutdownRetentionTest {

    private static final int AGENT_COUNT = 32;

    @TempDir Path workspace;

    @Test
    void transientAgentsWithSameNamespaceAreCollectibleWithoutClose() throws Exception {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("retention-test-model");

        List<AgentReferences> references = new ArrayList<>(AGENT_COUNT);
        try {
            for (int i = 0; i < AGENT_COUNT; i++) {
                references.add(buildTransientAgent(model));
            }
            assertTrue(
                    awaitCollection(references),
                    "HarnessAgent and its underlying ReActAgent should be collectible even when"
                            + " callers omit close()");
        } finally {
            // Intentionally do not close before the assertion: doing so would bypass the production
            // path under regression. Close only survivors so a failed test does not pollute others.
            references.forEach(AgentReferences::closeSurvivor);
        }
    }

    @Test
    void closingOneAgentDoesNotRemoveSameNamespacePeersShutdownSaver() {
        InMemoryAgentStateStore firstStore = new InMemoryAgentStateStore();
        InMemoryAgentStateStore secondStore = new InMemoryAgentStateStore();
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        String requestId = null;

        try (HarnessAgent first = buildAgent(mockModel(), firstStore);
                HarnessAgent second = buildAgent(mockModel(), secondStore)) {
            assertNotEquals(
                    first.getAgentId(),
                    second.getAgentId(),
                    "the fixed Harness namespace must not replace the per-instance runtime id");

            first.close();

            AgentState secondState = second.getDelegate().getAgentState("user-2", "session-2");
            secondState.setSummary("second agent is still active");
            requestId = manager.registerRequest(second.getDelegate());
            manager.bindRequestState(requestId, secondState);

            manager.saveOnInterruptObserved(requestId);

            AgentState saved =
                    secondStore
                            .get("user-2", "session-2", "agent_state", AgentState.class)
                            .orElseThrow();
            assertEquals("second agent is still active", saved.getSummary());
            assertTrue(saved.isShutdownInterrupted());
        } finally {
            manager.unregisterRequest(requestId);
        }
    }

    private AgentReferences buildTransientAgent(Model model) {
        HarnessAgent agent = buildAgent(model, new InMemoryAgentStateStore());
        return new AgentReferences(
                new WeakReference<>(agent), new WeakReference<>(agent.getDelegate()));
    }

    private HarnessAgent buildAgent(Model model, InMemoryAgentStateStore stateStore) {
        return HarnessAgent.builder()
                .name("retention-test")
                .agentId("default")
                .model(model)
                .stateStore(stateStore)
                .workspace(workspace)
                .disableSubagents()
                .disableMemoryHooks()
                .disableMemoryTools()
                .disableTranscript()
                .disableCompaction()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableToolsConfig()
                .disableDynamicSkills()
                .build();
    }

    private static Model mockModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("retention-test-model");
        return model;
    }

    private static boolean awaitCollection(List<AgentReferences> references)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline && !allCollected(references)) {
            System.gc();
            Thread.sleep(20);
        }
        return allCollected(references);
    }

    private static boolean allCollected(List<AgentReferences> references) {
        return references.stream()
                .allMatch(refs -> refs.harness().refersTo(null) && refs.delegate().refersTo(null));
    }

    private record AgentReferences(
            WeakReference<HarnessAgent> harness, WeakReference<ReActAgent> delegate) {

        void closeSurvivor() {
            HarnessAgent harnessAgent = harness.get();
            if (harnessAgent != null) {
                harnessAgent.close();
                return;
            }
            ReActAgent reactAgent = delegate.get();
            if (reactAgent != null) {
                reactAgent.close();
            }
        }
    }
}
