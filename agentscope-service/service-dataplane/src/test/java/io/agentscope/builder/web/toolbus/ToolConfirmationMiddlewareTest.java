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
package io.agentscope.builder.web.toolbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.PermissionPolicy;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolDefaultConfig;
import io.agentscope.builder.web.managed.DataSessionService;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.state.AgentState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ToolConfirmationMiddlewareTest {

    @Test
    void mcpServerDefaultDeniesItsNamespacedTools() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        var middleware = middleware(coordinator, sessions);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        "mcp_toolset",
                                        new ToolDefaultConfig(true, PermissionPolicy.of("deny")),
                                        List.of(),
                                        "crm")));
        AgentState state = AgentState.builder().build();
        var events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("call-a", "crm__write", Map.of()))),
                                ignored ->
                                        Flux.error(
                                                new AssertionError("MCP write must not execute")))
                        .collectList()
                        .block();
        assertThat(events).hasSize(3);
        verifyNoInteractions(coordinator);
    }

    @Test
    void defaultAlwaysAskAppliesWithoutPerToolConfigs() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        ToolConfirmationMiddleware middleware = middleware(coordinator, sessions);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        AgentSpecTypes.TOOLSET_AGENT,
                                        new ToolDefaultConfig(
                                                true,
                                                PermissionPolicy.of(
                                                        ToolConfirmationMiddleware
                                                                .POLICY_ALWAYS_ASK)),
                                        List.of(),
                                        null)));
        when(coordinator.awaitDecision("session-a", "call-a", "read_file", Map.of(), null))
                .thenReturn(new ToolConfirmationCoordinator.ConfirmationDecision(false, null));
        AgentState state = AgentState.builder().build();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("call-a", "read_file", Map.of()))),
                                ignored -> Flux.error(new AssertionError("tool must not execute")))
                        .collectList()
                        .block();

        assertThat(events).hasSize(3);
        assertThat(state.getContext()).hasSize(1);
    }

    @Test
    void missingControlPlaneSnapshotFailsClosed() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        ToolConfirmationMiddleware middleware = middleware(coordinator, sessions);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        new SessionResolveResult(
                                session(), null, null, null, null, null, null, null, null, null,
                                null));
        AgentState state = AgentState.builder().build();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("call-a", "read_file", Map.of()))),
                                ignored -> Flux.error(new AssertionError("tool must not execute")))
                        .collectList()
                        .block();

        assertThat(events).hasSize(3);
        verifyNoInteractions(coordinator);
    }

    @Test
    void sessionLoadFailureFailsClosed() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        ToolConfirmationMiddleware middleware = middleware(coordinator, sessions);
        when(sessions.resolve("session-a"))
                .thenThrow(new IllegalStateException("session unavailable"));
        AgentState state = AgentState.builder().build();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("call-a", "read_file", Map.of()))),
                                ignored -> Flux.error(new AssertionError("tool must not execute")))
                        .collectList()
                        .block();

        assertThat(events).hasSize(3);
        verifyNoInteractions(coordinator);
    }

    @Test
    void unknownPermissionPolicyFailsClosed() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        ToolConfirmationMiddleware middleware = middleware(coordinator, sessions);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        AgentSpecTypes.TOOLSET_AGENT,
                                        new ToolDefaultConfig(
                                                true, PermissionPolicy.of("allways_allow")),
                                        List.of(),
                                        null)));
        AgentState state = AgentState.builder().build();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("call-a", "read_file", Map.of()))),
                                ignored -> Flux.error(new AssertionError("tool must not execute")))
                        .collectList()
                        .block();

        assertThat(events).hasSize(3);
        verifyNoInteractions(coordinator);
    }

    @Test
    void rejectedToolWritesDeniedResultAndStillRunsAllowedCalls() {
        ToolConfirmationCoordinator coordinator = mock(ToolConfirmationCoordinator.class);
        DataSessionService sessions = mock(DataSessionService.class);
        ToolConfirmationMiddleware middleware =
                new ToolConfirmationMiddleware(coordinator, sessions);

        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        AgentSpecTypes.TOOLSET_AGENT,
                                        new ToolDefaultConfig(
                                                true,
                                                PermissionPolicy.of(
                                                        ToolConfirmationMiddleware
                                                                .POLICY_ALWAYS_ALLOW)),
                                        List.of(
                                                new ToolConfigEntry(
                                                        "web_search",
                                                        true,
                                                        PermissionPolicy.of(
                                                                ToolConfirmationMiddleware
                                                                        .POLICY_ALWAYS_ASK))),
                                        null)));
        when(coordinator.awaitDecision(
                        "session-a", "call-denied", "web_search", Map.of("query", "q"), null))
                .thenReturn(
                        new ToolConfirmationCoordinator.ConfirmationDecision(
                                false, "blocked by operator"));

        AgentState state = AgentState.builder().replyId("reply-a").build();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent");
        when(agent.getAgentState()).thenReturn(state);
        RuntimeContext context =
                RuntimeContext.builder().sessionId("session-a").agentState(state).build();
        ToolUseBlock denied = call("call-denied", "web_search", Map.of("query", "q"));
        ToolUseBlock allowed = call("call-allowed", "read_file", Map.of("path", "README.md"));
        AtomicReference<ActingInput> forwarded = new AtomicReference<>();

        List<AgentEvent> events =
                middleware
                        .onActing(
                                agent,
                                context,
                                new ActingInput(List.of(denied, allowed)),
                                input -> {
                                    forwarded.set(input);
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();

        assertThat(forwarded.get().toolCalls()).containsExactly(allowed);
        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOf(ToolResultEndEvent.class);
        assertThat(((ToolResultEndEvent) events.get(2)).getState())
                .isEqualTo(ToolResultState.DENIED);
        assertThat(state.getContext()).hasSize(1);
        assertThat(
                        state.getContext()
                                .get(0)
                                .getContentBlocks(ToolResultBlock.class)
                                .get(0)
                                .getState())
                .isEqualTo(ToolResultState.DENIED);
        assertThat(
                        ((TextBlock)
                                        state.getContext()
                                                .get(0)
                                                .getContentBlocks(ToolResultBlock.class)
                                                .get(0)
                                                .getOutput()
                                                .get(0))
                                .getText())
                .isEqualTo("blocked by operator");
    }

    private static ManagedSessionDto session() {
        return new ManagedSessionDto(
                "session-a",
                "owner-a",
                "agent-a",
                "owner-a",
                1,
                "managed",
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                "running",
                null,
                1,
                1,
                null);
    }

    private static ToolConfirmationMiddleware middleware(
            ToolConfirmationCoordinator coordinator, DataSessionService sessions) {
        return new ToolConfirmationMiddleware(coordinator, sessions);
    }

    private static SessionResolveResult resolved(AgentToolset toolset) {
        return new SessionResolveResult(
                session(),
                Map.of("name", "agent", "tools", List.of(toolset)),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static Agent agent(AgentState state) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("agent");
        when(agent.getAgentState()).thenReturn(state);
        return agent;
    }

    private static RuntimeContext context(AgentState state) {
        return RuntimeContext.builder().sessionId("session-a").agentState(state).build();
    }

    private static ToolUseBlock call(String id, String name, Map<String, Object> input) {
        return ToolUseBlock.builder().id(id).name(name).input(input).build();
    }
}
