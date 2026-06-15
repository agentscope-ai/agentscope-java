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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Phase B-0 — verify {@link DefaultAgentManager#createAgentIfPresent(String, RuntimeContext)} and
 * {@link DefaultAgentManager#createAgent(String, RuntimeContext)} forward the parent
 * {@link RuntimeContext} to the registered {@link SubagentFactory} unchanged.
 */
class DefaultAgentManagerRuntimeContextTest {

    private record TypedMarker(String value) {}

    private record ToolMarker(String value) {}

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
    void invokeAgent_preservesParentRuntimeContextMetadata() {
        HarnessAgent child = mock(HarnessAgent.class);
        when(child.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(reply("ok")));

        RuntimeContext parent = parentContext();
        DefaultAgentManager mgr = new DefaultAgentManager(List.of(), null);

        mgr.invokeAgent(child, parent, "child-session", parent.getUserId(), "hello").block();

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).call(any(Msg.class), captor.capture());

        RuntimeContext childCtx = captor.getValue();
        assertEquals("child-session", childCtx.getSessionId());
        assertEquals(parent.getUserId(), childCtx.getUserId());
        assertEquals("trace-123", childCtx.get("traceId"));
        assertEquals(
                OutboundAddress.direct("chatui", "chatui:123"),
                childCtx.get("outboundAddress", OutboundAddress.class));
        assertEquals(new TypedMarker("typed-1"), childCtx.get(TypedMarker.class));
        assertSame(parent.getToolExecutionContext(), childCtx.getToolExecutionContext());
        assertNull(childCtx.getAgentState());
    }

    @Test
    void invokeAgentStream_preservesParentRuntimeContextMetadata() {
        HarnessAgent child = mock(HarnessAgent.class);
        when(child.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        RuntimeContext parent = parentContext();
        DefaultAgentManager mgr = new DefaultAgentManager(List.of(), null);

        mgr.invokeAgentStream(
                child,
                parent,
                "child-stream-session",
                parent.getUserId(),
                "hello",
                null,
                StreamOptions.defaults());

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).stream(anyList(), any(StreamOptions.class), captor.capture());

        RuntimeContext childCtx = captor.getValue();
        assertEquals("child-stream-session", childCtx.getSessionId());
        assertEquals(parent.getUserId(), childCtx.getUserId());
        assertEquals("trace-123", childCtx.get("traceId"));
        assertEquals(
                OutboundAddress.direct("chatui", "chatui:123"),
                childCtx.get("outboundAddress", OutboundAddress.class));
        assertEquals(new TypedMarker("typed-1"), childCtx.get(TypedMarker.class));
        assertSame(parent.getToolExecutionContext(), childCtx.getToolExecutionContext());
        assertNull(childCtx.getAgentState());
    }

    @Test
    void invokeAgent_withoutParentRuntimeContext_usesProvidedIdentityOnly() {
        HarnessAgent child = mock(HarnessAgent.class);
        when(child.call(any(Msg.class), any(RuntimeContext.class)))
                .thenReturn(Mono.just(reply("ok")));

        DefaultAgentManager mgr = new DefaultAgentManager(List.of(), null);
        mgr.invokeAgent(child, "child-session", "solo-user", "hello").block();

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).call(any(Msg.class), captor.capture());

        RuntimeContext childCtx = captor.getValue();
        assertEquals("child-session", childCtx.getSessionId());
        assertEquals("solo-user", childCtx.getUserId());
        assertNull(childCtx.get("traceId"));
        assertNull(childCtx.get("outboundAddress", OutboundAddress.class));
        assertNull(childCtx.get(TypedMarker.class));
        assertNull(childCtx.getToolExecutionContext());
        assertNull(childCtx.getAgentState());
    }

    @Test
    void invokeAgentStream_withoutParentRuntimeContext_usesProvidedIdentityOnly() {
        HarnessAgent child = mock(HarnessAgent.class);
        when(child.stream(anyList(), any(StreamOptions.class), any(RuntimeContext.class)))
                .thenReturn(Flux.empty());

        DefaultAgentManager mgr = new DefaultAgentManager(List.of(), null);
        mgr.invokeAgentStream(
                child,
                "child-stream-session",
                "solo-user",
                "hello",
                null,
                StreamOptions.defaults());

        ArgumentCaptor<RuntimeContext> captor = ArgumentCaptor.forClass(RuntimeContext.class);
        verify(child).stream(anyList(), any(StreamOptions.class), captor.capture());

        RuntimeContext childCtx = captor.getValue();
        assertEquals("child-stream-session", childCtx.getSessionId());
        assertEquals("solo-user", childCtx.getUserId());
        assertNull(childCtx.get("traceId"));
        assertNull(childCtx.get("outboundAddress", OutboundAddress.class));
        assertNull(childCtx.get(TypedMarker.class));
        assertNull(childCtx.getToolExecutionContext());
        assertNull(childCtx.getAgentState());
    }

    private static RuntimeContext parentContext() {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("parent-session")
                        .userId("alice")
                        .put("traceId", "trace-123")
                        .put(TypedMarker.class, new TypedMarker("typed-1"))
                        .toolExecutionContext(
                                ToolExecutionContext.builder()
                                        .register(new ToolMarker("tool-di"))
                                        .build())
                        .build();
        ctx.put(
                "outboundAddress",
                OutboundAddress.class,
                OutboundAddress.direct("chatui", "chatui:123"));
        return ctx;
    }

    private static Msg reply(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
