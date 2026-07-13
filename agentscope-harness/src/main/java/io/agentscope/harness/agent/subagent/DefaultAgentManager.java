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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventSource;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.middleware.SubagentEntry;
import io.agentscope.harness.agent.subagent.task.TaskRunOutcome;
import io.agentscope.harness.agent.subagent.task.TaskSuspension;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Pure agent factory and invoker — knows how to create agents from registered factories and invoke
 * them with a prompt.
 *
 * <p>This is the <em>agent-internal</em> layer. It has <strong>no</strong> session registry, no lane
 * management, no run tracking. The
 * agent-internal {@link AgentSpawnTool} uses this directly for
 * lightweight subagent invocation.
 */
public final class DefaultAgentManager {

    private volatile Map<String, SubagentFactory> agentFactories;
    private volatile Map<String, SubagentDeclaration> declarations;
    private final WorkspaceManager workspaceManager;
    private volatile Consumer<Agent> materializationCustomizer = ignored -> {};

    /**
     * Builds a manager from subagent entries (factories plus optional {@link SubagentDeclaration}
     * metadata for remote configuration).
     */
    public DefaultAgentManager(List<SubagentEntry> entries, WorkspaceManager workspaceManager) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
        this.workspaceManager = workspaceManager;
    }

    /**
     * Replaces the current set of entries with a new snapshot. Called per-call from
     * {@link io.agentscope.harness.agent.middleware.SubagentsMiddleware} to reflect per-user subagent
     * configurations.
     */
    public void refreshEntries(List<SubagentEntry> entries) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        Map<String, SubagentDeclaration> decls = new HashMap<>();
        for (SubagentEntry e : entries) {
            factories.put(e.name(), e.factory());
            if (e.declaration() != null) {
                decls.put(e.name(), e.declaration());
            }
        }
        this.agentFactories = Map.copyOf(factories);
        this.declarations = Map.copyOf(decls);
    }

    /**
     * Atomic alias of {@link #refreshEntries(List)} used by
     * {@link io.agentscope.harness.agent.middleware.DynamicSubagentsMiddleware} to swap the registered
     * subagent set on every reasoning step. The two volatile reference assignments below ensure
     * any concurrent reader observes either the previous snapshot or the new one fully — never a
     * partial state.
     */
    public void replaceAgents(List<SubagentEntry> entries) {
        refreshEntries(entries);
    }

    /**
     * Race-safe lookup-and-create. Returns {@link Optional#empty()} when no factory is registered
     * for {@code agentId} at the moment of the volatile read, when the registered declaration is
     * {@link SubagentDeclaration.Mode#PRIMARY}-only (cannot be spawned as a subagent), otherwise
     * returns a freshly created agent. Preferred over the two-step {@link #hasAgent(String)} +
     * {@link #createAgent(String, RuntimeContext)} pair when the registry may be replaced
     * concurrently (e.g. dynamic reload between calls).
     *
     * <p>{@code parentRc} is forwarded to {@link SubagentFactory#create(RuntimeContext)} so child
     * agents can bucket their persisted state by parent identity. Pass
     * {@link RuntimeContext#empty()} when no parent context is available.
     */
    public Optional<Agent> createAgentIfPresent(String agentId, RuntimeContext parentRc) {
        if (agentId == null) {
            return Optional.empty();
        }
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            return Optional.empty();
        }
        SubagentDeclaration decl = declarations.get(agentId);
        if (decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY) {
            return Optional.empty();
        }
        return Optional.of(customize(factory.create(effectiveContext(parentRc))));
    }

    /**
     * Returns {@code true} when {@code agentId} is registered with a {@code PRIMARY}-only
     * declaration. Lets {@link io.agentscope.harness.agent.tool.AgentSpawnTool} produce a more
     * helpful error message ("PRIMARY-only, cannot be spawned") instead of the generic
     * "Unknown agent_id" when {@link #createAgentIfPresent} returns empty.
     */
    public boolean isPrimaryOnly(String agentId) {
        if (agentId == null) return false;
        SubagentDeclaration decl = declarations.get(agentId);
        return decl != null && decl.getMode() == SubagentDeclaration.Mode.PRIMARY;
    }

    /** Whether a factory is registered for the given agent id. */
    public boolean hasAgent(String agentId) {
        return agentId != null && agentFactories.containsKey(agentId);
    }

    /** Immutable view of registered subagent factories keyed by {@code agent_id}. */
    public Map<String, SubagentFactory> getAgentFactories() {
        return agentFactories;
    }

    /** Optional declaration metadata for the given {@code agent_id} (e.g. remote URL). */
    public Optional<SubagentDeclaration> getDeclaration(String agentId) {
        return Optional.ofNullable(declarations.get(agentId));
    }

    /**
     * Creates a new agent instance from the registered factory.
     *
     * @throws IllegalArgumentException if no factory is registered for the given id
     */
    public Agent createAgent(String agentId, RuntimeContext parentRc) {
        SubagentFactory factory = agentFactories.get(agentId);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown agent_id: " + agentId);
        }
        return customize(factory.create(effectiveContext(parentRc)));
    }

    /** Applies a host customization after every native child materialization. */
    public void setMaterializationCustomizer(Consumer<Agent> customizer) {
        materializationCustomizer = customizer != null ? customizer : ignored -> {};
    }

    private Agent customize(Agent agent) {
        materializationCustomizer.accept(agent);
        return agent;
    }

    private static RuntimeContext effectiveContext(RuntimeContext context) {
        return context != null ? context : RuntimeContext.empty();
    }

    /**
     * Invokes an agent with a user prompt. Handles both plain {@link Agent} and {@link
     * HarnessAgent} (injects {@link RuntimeContext} for the latter).
     *
     * <p>For {@link HarnessAgent} children, {@code userId} is propagated so that isolation-key
     * resolution (e.g. {@code USER}-scoped sandbox slots) works correctly. A fresh {@code
     * sessionId} is always assigned independently of the parent session.
     *
     * @param agent the agent to invoke
     * @param sessionId a new, child-specific session id
     * @param userId the parent's user-id (may be {@code null})
     * @param prompt the user message to send
     */
    public Mono<Msg> invokeAgent(Agent agent, String sessionId, String userId, String prompt) {
        return invokeAgent(agent, sessionId, userId, prompt, null);
    }

    public Mono<Msg> invokeAgent(
            Agent agent, String sessionId, String userId, String prompt, RuntimeContext parentRc) {
        RuntimeContext ctx =
                parentRc != null
                        ? RuntimeContext.builder(parentRc)
                                .sessionId(sessionId)
                                .userId(userId)
                                .build()
                        : RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        if (agent instanceof ReActAgent react) {
            return react.call(List.of(userMessage(prompt)), ctx);
        }
        if (agent instanceof HarnessAgent harness) {
            return harness.call(userMessage(prompt), ctx);
        }
        return agent.call(List.of(userMessage(prompt)));
    }

    /**
     * Invokes a local child through typed {@link AgentEvent}s and returns a task lifecycle outcome.
     * Permission pauses retain the exact reply and tool identities needed for durable resume.
     */
    public TaskRunOutcome invokeAgentTask(
            Agent agent,
            String sessionId,
            String userId,
            List<Msg> messages,
            RuntimeContext parentRc) {
        Objects.requireNonNull(agent, "agent must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        RuntimeContext childContext = childRuntimeContext(sessionId, userId, parentRc);
        List<Msg> input = messages == null ? List.of() : List.copyOf(messages);
        List<AgentEvent> events;
        if (agent instanceof ReActAgent react) {
            events = react.streamEvents(input, childContext).collectList().block();
        } else if (agent instanceof HarnessAgent harness) {
            events = harness.streamEvents(input, childContext).collectList().block();
        } else {
            Msg result = agent.call(input).block();
            if (result != null && result.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                throw new IllegalStateException(
                        "Permission-suspended task requires an AgentScope typed event stream");
            }
            return new TaskRunOutcome.Completed(result != null ? result.getTextContent() : "");
        }
        List<AgentEvent> captured = events != null ? events : List.of();
        Msg result =
                captured.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .map(AgentResultEvent::getResult)
                        .reduce((first, last) -> last)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Child event stream has no result"));
        if (result.getGenerateReason() != GenerateReason.PERMISSION_ASKING) {
            return new TaskRunOutcome.Completed(result.getTextContent());
        }
        RequireUserConfirmEvent approval =
                captured.stream()
                        .filter(RequireUserConfirmEvent.class::isInstance)
                        .map(RequireUserConfirmEvent.class::cast)
                        .reduce((first, last) -> last)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Permission pause has no confirmation event"));
        String parentSessionId = parentRc != null ? parentRc.getSessionId() : null;
        TaskSuspension suspension =
                new TaskSuspension(
                        userId,
                        Objects.requireNonNull(
                                parentSessionId,
                                "parent RuntimeContext sessionId must not be null"),
                        sessionId,
                        approval.getReplyId(),
                        approval.getToolCalls().stream()
                                .map(
                                        toolCall ->
                                                new TaskSuspension.PendingToolCall(
                                                        toolCall.getId(), toolCall.getName()))
                                .toList());
        return new TaskRunOutcome.WaitingForApproval(suspension);
    }

    public TaskRunOutcome invokeAgentTask(
            Agent agent, String sessionId, String userId, String prompt, RuntimeContext parentRc) {
        return invokeAgentTask(agent, sessionId, userId, List.of(userMessage(prompt)), parentRc);
    }

    private static RuntimeContext childRuntimeContext(
            String sessionId, String userId, RuntimeContext parentRc) {
        return parentRc != null
                ? RuntimeContext.builder(parentRc).sessionId(sessionId).userId(userId).build()
                : RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
    }

    /**
     * Invokes an agent and returns its execution as a tagged {@link Flux} of {@link Event}s.
     *
     * <p>Every event in the returned flux carries an {@link EventSource} built from {@code source}
     * combined with the child's {@code agentId}/{@code sessionId}. This allows parent consumers
     * to identify which subagent emitted each event without out-of-band metadata.
     *
     * <p>The {@code parentSource} argument should be the {@link EventSource} already stored in the
     * parent's Reactor Context (if any). When the parent itself is a subagent, its path is used as
     * the prefix so the full call-hierarchy path is preserved across multiple nesting levels.
     *
     * @param agent the agent to invoke
     * @param sessionId a new, child-specific session id
     * @param userId the parent's user-id (may be {@code null})
     * @param prompt the user message to send
     * @param source the {@link EventSource} that will be stamped onto every emitted event
     * @param options stream configuration passed to the child agent
     * @return {@link Flux} of tagged events; never null
     */
    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options) {
        return invokeAgentStream(agent, sessionId, userId, prompt, source, options, null);
    }

    public Flux<Event> invokeAgentStream(
            Agent agent,
            String sessionId,
            String userId,
            String prompt,
            EventSource source,
            StreamOptions options,
            RuntimeContext parentRc) {
        Flux<Event> childFlux;
        StreamOptions effective = options != null ? options : StreamOptions.defaults();
        RuntimeContext ctx =
                parentRc != null
                        ? RuntimeContext.builder(parentRc)
                                .sessionId(sessionId)
                                .userId(userId)
                                .build()
                        : RuntimeContext.builder().sessionId(sessionId).userId(userId).build();
        if (agent instanceof ReActAgent react) {
            childFlux = react.stream(List.of(userMessage(prompt)), effective, ctx);
        } else if (agent instanceof HarnessAgent harness) {
            childFlux = harness.stream(List.of(userMessage(prompt)), effective, ctx);
        } else {
            childFlux = agent.stream(List.of(userMessage(prompt)), effective);
        }
        return childFlux.map(event -> event.withSource(source));
    }

    public WorkspaceManager getWorkspaceManager() {
        return workspaceManager;
    }

    private static Msg userMessage(String prompt) {
        return Msg.builder().role(MsgRole.USER).textContent(prompt).build();
    }
}
