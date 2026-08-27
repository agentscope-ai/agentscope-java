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
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DefaultAgentResolver}. */
@Tag("unit")
@DisplayName("DefaultAgentResolver Unit Tests")
class DefaultAgentResolverTest {

    @Test
    void resolveAgentLooksUpRegistryWhenServerSideMemoryIsDisabled() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        Agent registered = mock(Agent.class);
        registry.register("agent-a", registered);
        DefaultAgentResolver resolver = new DefaultAgentResolver(registry);

        assertSame(registered, resolver.resolveAgent("agent-a", "thread-1"));
        assertSame(registered, resolver.resolveAgent("agent-a", "thread-1", "user-1"));
        assertFalse(
                resolver.hasMemory(
                        RuntimeContext.builder().userId("user-1").sessionId("thread-1").build()));
        assertThrows(
                AguiException.AgentNotFoundException.class,
                () -> resolver.resolveAgent("missing", "thread-1", "user-1"));
    }

    @Test
    void resolveAgentAndHasMemoryUseSessionManagerWhenServerSideMemoryIsEnabled() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        ThreadSessionManager sessionManager = mock(ThreadSessionManager.class);
        Agent sessionAgent = mock(Agent.class);
        RuntimeContext context =
                RuntimeContext.builder().userId("user-1").sessionId("thread-1").build();
        when(sessionManager.getOrCreateAgent(eq("user-1"), eq("thread-1"), eq("agent-a"), any()))
                .thenReturn(sessionAgent);
        when(sessionManager.getOrCreateAgent(isNull(), eq("thread-1"), eq("agent-a"), any()))
                .thenReturn(sessionAgent);
        when(sessionManager.hasMemory(context)).thenReturn(true);

        DefaultAgentResolver resolver =
                DefaultAgentResolver.builder()
                        .registry(registry)
                        .sessionManager(sessionManager)
                        .serverSideMemory(true)
                        .build();

        assertSame(sessionAgent, resolver.resolveAgent("agent-a", "thread-1", "user-1"));
        assertSame(sessionAgent, resolver.resolveAgent("agent-a", "thread-1"));
        assertTrue(resolver.hasMemory(context));
        verify(sessionManager).getOrCreateAgent(eq("user-1"), eq("thread-1"), eq("agent-a"), any());
        verify(sessionManager).getOrCreateAgent(isNull(), eq("thread-1"), eq("agent-a"), any());
        verify(sessionManager).hasMemory(context);
    }

    @Test
    void serverSideMemoryWithoutSessionManagerFallsBackToRegistry() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        Agent registered = mock(Agent.class);
        registry.register("agent-a", registered);
        DefaultAgentResolver resolver =
                DefaultAgentResolver.builder().registry(registry).serverSideMemory(true).build();

        assertSame(registered, resolver.resolveAgent("agent-a", "thread-1", "user-1"));
        assertFalse(resolver.hasMemory(RuntimeContext.builder().sessionId("thread-1").build()));
    }
}
