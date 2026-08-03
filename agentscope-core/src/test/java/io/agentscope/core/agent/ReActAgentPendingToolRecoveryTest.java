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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.hook.PendingToolRecoveryHook;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the shared-builder concurrency bug (issue #2539).
 *
 * <p>Before the fix, {@code Builder.build()} mutated the shared builder's {@code hooks} set
 * ({@code this.hooks.add(new PendingToolRecoveryHook())}) when {@code enablePendingToolRecovery}
 * was enabled. When the same builder was reused across concurrent builds — as the A2A server does
 * with a shared builder bean — this raced on the non-thread-safe set, failing agent construction
 * with {@code ArrayIndexOutOfBoundsException} / {@code NullPointerException}, and also leaked one
 * hook instance into the shared set per build. After the fix, the hook is added per agent instance
 * in the constructor and {@code build()} is read-only on the builder's state.
 */
@DisplayName("ReActAgent PendingToolRecoveryHook shared-builder regression (issue #2539)")
class ReActAgentPendingToolRecoveryTest {

    private static ReActAgent.Builder sharedBuilderWithRecovery() {
        return ReActAgent.builder()
                .name(TestConstants.TEST_REACT_AGENT_NAME)
                .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                .model(new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE))
                .enablePendingToolRecovery(true);
    }

    @Test
    @DisplayName("Each agent built from a shared builder gets exactly one PendingToolRecoveryHook")
    void perAgentHookNotAccumulatedOnSharedBuilder() {
        ReActAgent.Builder shared = sharedBuilderWithRecovery();

        // Reuse one shared builder like the A2A server does. Before the fix, each build added a
        // hook to the shared set, so later agents carried 2/3 recovery hooks; now each gets one.
        for (int i = 0; i < 3; i++) {
            ReActAgent agent = shared.build();
            assertEquals(
                    1,
                    countPendingToolRecoveryHooks(agent),
                    "each agent must have exactly one PendingToolRecoveryHook");
        }
    }

    @Test
    @DisplayName("Concurrent build() on a shared builder is safe (no race, no hook leak)")
    void concurrentBuildOnSharedBuilderIsSafe() throws Exception {
        ReActAgent.Builder shared = sharedBuilderWithRecovery();

        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    for (int i = 0; i < perThread; i++) {
                                        // Before the fix, this could throw AIOOBE/NPE (racing on
                                        // the shared hooks set) or yield agents with growing hooks.
                                        ReActAgent agent = shared.build();
                                        assertEquals(1, countPendingToolRecoveryHooks(agent));
                                    }
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
    }

    private static long countPendingToolRecoveryHooks(ReActAgent agent) {
        return agent.getSortedHooks().stream()
                .filter(h -> h instanceof PendingToolRecoveryHook)
                .count();
    }
}
