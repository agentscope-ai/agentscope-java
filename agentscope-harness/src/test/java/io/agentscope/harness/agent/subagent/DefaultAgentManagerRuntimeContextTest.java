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
package io.agentscope.harness.agent.subagent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Phase B-0 — verify {@link DefaultAgentManager#createAgentIfPresent(String, RuntimeContext)} and
 * {@link DefaultAgentManager#createAgent(String, RuntimeContext)} forward the parent
 * {@link RuntimeContext} to the registered {@link SubagentFactory} unchanged.
 */
class DefaultAgentManagerRuntimeContextTest {

    private static SubagentDeclaration plainDecl(String name) {
        return SubagentDeclaration.builder()
                .name(name)
                .description("test")
                .inlineAgentsBody("body")
                .build();
    }

    @Test
    void createAgentIfPresent_forwardsParentRuntimeContext() {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        Agent stub = org.mockito.Mockito.mock(Agent.class);
        SubagentFactory factory =
                rc -> {
                    seen.set(rc);
                    return stub;
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "desc", factory, plainDecl("worker"))),
                        null);

        RuntimeContext rc = RuntimeContext.builder().sessionId("s1").userId("alice").build();
        mgr.createAgentIfPresent("worker", rc);
        assertSame(rc, seen.get(), "factory must receive the exact RuntimeContext passed in");
    }

    @Test
    void createAgent_forwardsParentRuntimeContext() {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        Agent stub = org.mockito.Mockito.mock(Agent.class);
        SubagentFactory factory =
                rc -> {
                    seen.set(rc);
                    return stub;
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "desc", factory, plainDecl("worker"))),
                        null);

        RuntimeContext rc = RuntimeContext.builder().userId("bob").build();
        mgr.createAgent("worker", rc);
        assertSame(rc, seen.get());
    }

    @Test
    void createAgentIfPresent_nullRuntimeContext_substitutesEmpty() {
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        Agent stub = org.mockito.Mockito.mock(Agent.class);
        SubagentFactory factory =
                rc -> {
                    seen.set(rc);
                    return stub;
                };
        DefaultAgentManager mgr =
                new DefaultAgentManager(
                        List.of(new SubagentEntry("worker", "desc", factory, plainDecl("worker"))),
                        null);

        mgr.createAgentIfPresent("worker", null);
        assertNotNull(seen.get(), "factory must receive a non-null RuntimeContext (empty)");
        // Behave like RuntimeContext.empty(): no sessionId / userId
        assertNotNull(seen.get());
        // We don't assert .equals() here — empty() may return a fresh instance — only that the
        // factory never sees null.
    }

    @Test
    void materializationCustomizerRunsForEveryPublicCreationPath() {
        Agent stub = org.mockito.Mockito.mock(Agent.class);
        DefaultAgentManager manager =
                new DefaultAgentManager(
                        List.of(
                                new SubagentEntry(
                                        "worker", "desc", ignored -> stub, plainDecl("worker"))),
                        null);
        AtomicReference<Agent> customized = new AtomicReference<>();
        manager.setMaterializationCustomizer(customized::set);

        assertSame(
                stub, manager.createAgentIfPresent("worker", RuntimeContext.empty()).orElseThrow());
        assertSame(stub, customized.get());
        customized.set(null);
        assertSame(stub, manager.createAgent("worker", RuntimeContext.empty()));
        assertSame(stub, customized.get());
    }
}
