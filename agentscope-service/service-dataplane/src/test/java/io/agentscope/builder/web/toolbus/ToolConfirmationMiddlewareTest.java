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
import static org.mockito.Mockito.when;

import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.ManagedCatalog;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.PermissionPolicy;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolDefaultConfig;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionInteractions;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionProjection;
import io.agentscope.core.session.SessionRecorder;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.MessageUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ToolConfirmationMiddlewareTest {

    @Test
    void taskActionsAreIndependentOfDisabledBuiltinsButExplicitPoliciesRemain() {
        var sessions = mock(ManagedCatalog.class);
        var middleware = middleware(sessions);
        var toolset =
                new AgentToolset(
                        AgentSpecTypes.TOOLSET_AGENT,
                        new ToolDefaultConfig(false, null),
                        List.of(
                                new ToolConfigEntry(
                                        "task.fail", true, PermissionPolicy.of("deny"))),
                        null);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        new SessionResolveResult(
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
                                Map.of(
                                        "taskContext",
                                        Map.of(
                                                "taskToken",
                                                "private-fixture-credential",
                                                "availableActions",
                                                List.of("task.get", "task.fail")))));
        var state = AgentState.builder().build();
        var executed = new AtomicReference<List<String>>();
        var events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(
                                        List.of(
                                                call("read-task", "task.get", Map.of()),
                                                call("explicit-denial", "task.fail", Map.of()),
                                                call("builtin-denial", "execute", Map.of()))),
                                input -> {
                                    executed.set(
                                            input.toolCalls().stream()
                                                    .map(ToolUseBlock::getName)
                                                    .toList());
                                    return Flux.empty();
                                })
                        .collectList()
                        .block();
        assertThat(executed.get()).containsExactly("task.get");
        assertThat(events).hasSize(6);
    }

    @Test
    void actionNamesWithoutPrivateTaskCredentialDoNotGrantTools() {
        var sessions = mock(ManagedCatalog.class);
        var middleware = middleware(sessions);
        var toolset =
                new AgentToolset(
                        AgentSpecTypes.TOOLSET_AGENT,
                        new ToolDefaultConfig(false, null),
                        List.of(),
                        null);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        new SessionResolveResult(
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
                                Map.of(
                                        "taskContext",
                                        Map.of("availableActions", List.of("task.get")))));
        var state = AgentState.builder().build();
        var events =
                middleware
                        .onActing(
                                agent(state),
                                context(state),
                                new ActingInput(List.of(call("read-task", "task.get", Map.of()))),
                                input ->
                                        Flux.error(
                                                new AssertionError("unbound task tool executed")))
                        .collectList()
                        .block();
        assertThat(events).hasSize(3);
    }

    @Test
    void mcpServerDefaultDeniesItsNamespacedTools() {
        ManagedCatalog sessions = mock(ManagedCatalog.class);
        var middleware = middleware(sessions);
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
    }

    @Test
    void defaultAlwaysAskAppliesWithoutPerToolConfigs() {
        var sessions = mock(ManagedCatalog.class);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        AgentSpecTypes.TOOLSET_AGENT,
                                        new ToolDefaultConfig(
                                                true, PermissionPolicy.of("always_ask")),
                                        List.of(),
                                        null)));
        var state = AgentState.builder().build();
        var call = call("call-a", "read_file", Map.of());
        var other = call("call-b", "write_file", Map.of());
        state.contextMutable().add(new AssistantMessage(call).withContent(List.of(call, other)));
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "session-a"), null);
        var recorder = new SessionRecorder(log, "turn", "native", null);
        recorder.start(List.of(), state).block();
        try {
            var context =
                    RuntimeContext.builder()
                            .sessionId("session-a")
                            .agentState(state)
                            .put(SessionRecorder.CONTEXT_KEY, recorder)
                            .build();
            var events =
                    middleware(sessions)
                            .onActing(
                                    agent(state),
                                    context,
                                    new ActingInput(List.of(call, other)),
                                    ignored ->
                                            Flux.error(
                                                    new AssertionError(
                                                            "tool must not execute before"
                                                                    + " confirmation")))
                            .collectList()
                            .block();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(RequireUserConfirmEvent.class);
            assertThat(SessionInteractions.pending(log)).containsKeys("call-a", "call-b");
            assertThat(state.getContext().get(0).getContentBlocks(ToolResultBlock.class)).isEmpty();
            assertThat(
                            SessionProjection.read(log)
                                    .restore()
                                    .getContext()
                                    .get(0)
                                    .getContentBlocks(ToolUseBlock.class))
                    .allMatch(tool -> tool.getState() == ToolCallState.ASKING);
        } finally {
            recorder.finish(state, "suspended").block();
        }
    }

    @Test
    void missingControlPlaneSnapshotFailsClosed() {
        ManagedCatalog sessions = mock(ManagedCatalog.class);
        ToolConfirmationMiddleware middleware = middleware(sessions);
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
    }

    @Test
    void sessionLoadFailureFailsClosed() {
        ManagedCatalog sessions = mock(ManagedCatalog.class);
        ToolConfirmationMiddleware middleware = middleware(sessions);
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
    }

    @Test
    void unknownPermissionPolicyFailsClosed() {
        ManagedCatalog sessions = mock(ManagedCatalog.class);
        ToolConfirmationMiddleware middleware = middleware(sessions);
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
    }

    @Test
    void deniedToolWritesDeniedResultAndStillRunsAllowedCalls() {
        ManagedCatalog sessions = mock(ManagedCatalog.class);
        ToolConfirmationMiddleware middleware = new ToolConfirmationMiddleware(sessions);

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
                                                                        .POLICY_DENY))),
                                        null)));

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
                .isEqualTo("Tool execution denied by the agent's configured permission policy.");
    }

    @Test
    void portalApprovalPausesNativeStateAndConfirmedContinuationDoesNotAskAgain() {
        var sessions = mock(ManagedCatalog.class);
        when(sessions.resolve("session-a"))
                .thenReturn(
                        resolved(
                                new AgentToolset(
                                        AgentSpecTypes.TOOLSET_AGENT,
                                        new ToolDefaultConfig(
                                                true, PermissionPolicy.of("always_ask")),
                                        List.of(),
                                        null)));
        var middleware = middleware(sessions);
        var pendingCall = call("call-a", "read_file", Map.of("path", "fixture"));
        var state = AgentState.builder().sessionId("session-a").build();
        state.contextMutable().add(new AssistantMessage(pendingCall));
        var log =
                new InMemorySessionLogStore()
                        .open(new SessionKey("user", "agent", "session-a"), null);
        var recorder = new SessionRecorder(log, "turn", "native", null);
        recorder.start(List.of(), state).block();
        var context =
                RuntimeContext.builder()
                        .sessionId("session-a")
                        .agentState(state)
                        .put(SessionRecorder.CONTEXT_KEY, recorder)
                        .build();
        try {
            var events =
                    middleware
                            .onActing(
                                    agent(state),
                                    context,
                                    new ActingInput(List.of(pendingCall)),
                                    ignored ->
                                            Flux.error(
                                                    new AssertionError(
                                                            "Approval must pause before tool"
                                                                    + " execution")))
                            .collectList()
                            .block();
            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOf(RequireUserConfirmEvent.class);
            assertThat(events.get(1)).isInstanceOf(RequestStopEvent.class);
            assertThat(SessionInteractions.pending(log)).containsKey("call-a");
            assertThat(
                            SessionProjection.read(log)
                                    .restore()
                                    .getContext()
                                    .get(0)
                                    .getContentBlocks(ToolUseBlock.class)
                                    .get(0)
                                    .getState())
                    .isEqualTo(ToolCallState.ASKING);
            assertThat(
                            state.getContext()
                                    .get(0)
                                    .getContentBlocks(ToolUseBlock.class)
                                    .get(0)
                                    .getState())
                    .isEqualTo(ToolCallState.ASKING);
            var approved = pendingCall.withState(ToolCallState.ALLOWED);
            MessageUtils.replaceToolUseBlocks(state.contextMutable(), Map.of("call-a", approved));
            var executed = new AtomicBoolean();
            middleware
                    .onActing(
                            agent(state),
                            context,
                            new ActingInput(List.of(approved)),
                            ignored -> {
                                executed.set(true);
                                return Flux.empty();
                            })
                    .blockLast();
            assertThat(executed).isTrue();
        } finally {
            recorder.finish(state, "suspended").block();
        }
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

    private static ToolConfirmationMiddleware middleware(ManagedCatalog sessions) {
        return new ToolConfirmationMiddleware(sessions);
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
