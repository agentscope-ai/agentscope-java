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

import io.agentscope.core.message.Msg;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;

/**
 * Complete agent interface combining all capabilities.
 *
 * <p>This interface defines the core contract for agents, combining:
 * <ul>
 *   <li>{@link CallableAgent} - Process messages and generate responses</li>
 *   <li>{@link StreamableAgent} - Stream events during execution</li>
 *   <li>{@link ObservableAgent} - Observe messages without responding</li>
 * </ul>
 *
 * <p>Design Philosophy:
 * <ul>
 *   <li>Memory management is NOT part of the core Agent interface - it's the responsibility
 *       of specific agent implementations (e.g., ReActAgent)</li>
 *   <li>Structured output is a specialized capability provided by specific agents</li>
 *   <li>Observe pattern allows agents to receive messages without generating a reply,
 *       enabling multi-agent collaboration</li>
 * </ul>
 *
 * <p>All agents in the AgentScope framework should implement this interface.
 *
 * <p><b>Reply contract:</b> a single {@code call(...)} invocation produces exactly one
 * terminal {@link Msg}. Streaming variants (see {@link StreamableAgent}) may emit
 * many events but resolve to a single terminal Msg. This is enforced by the
 * {@code Mono<Msg>} return type on the call methods.
 */
public interface Agent extends CallableAgent, StreamableAgent, ObservableAgent {

    /**
     * Get the unique identifier for this agent.
     *
     * @return Agent ID
     */
    String getAgentId();

    /**
     * Get the name of this agent.
     *
     * @return Agent name
     */
    String getName();

    /**
     * Get the description of this agent.
     *
     * @return Agent description
     */
    default String getDescription() {
        return "Agent(" + getAgentId() + ") " + getName();
    }

    /**
     * Interrupt the current agent execution.
     * This method sets an interrupt flag that will be checked by the agent at appropriate
     * checkpoints during execution. The interruption is cooperative and may not take effect
     * immediately.
     */
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     * This method sets an interrupt flag and associates a user message with the interruption.
     * The interruption is cooperative and may not take effect immediately.
     *
     * @param msg User message associated with the interruption
     */
    void interrupt(Msg msg);

    /**
     * Interrupt the in-flight call identified by {@code ctx}.
     *
     * <p>The default implementation falls back to {@link #interrupt()} (the agent's default slot).
     * Multi-session agents and wrappers <strong>must</strong> override this to forward to the
     * session in {@code ctx}; merely exposing a delegate accessor is not enough. When {@code ctx}
     * carries a non-blank session or user id, the default logs a warning before falling back so
     * silent wrong-slot interrupts are visible.
     *
     * @param ctx runtime context identifying the session, may be {@code null}
     */
    default void interrupt(RuntimeContext ctx) {
        warnIfSessionScopedInterruptFallsBack(ctx);
        interrupt();
    }

    /**
     * Interrupt the in-flight call identified by {@code ctx} with an associated user message.
     *
     * <p>The default implementation falls back to {@link #interrupt(Msg)}. Multi-session agents
     * and wrappers <strong>must</strong> override this to target the session in {@code ctx}. When
     * {@code ctx} carries a non-blank session or user id, the default logs a warning before falling
     * back.
     *
     * @param ctx runtime context identifying the session, may be {@code null}
     * @param msg user message associated with the interruption, may be {@code null}
     */
    default void interrupt(RuntimeContext ctx, Msg msg) {
        warnIfSessionScopedInterruptFallsBack(ctx);
        interrupt(msg);
    }

    /**
     * Interrupt the in-flight call for a specific {@code (userId, sessionId)} session.
     *
     * <p>The default implementation falls back to {@link #interrupt()}. Multi-session agents and
     * wrappers <strong>must</strong> override this to target the identified slot. When either id is
     * non-blank, the default logs a warning before falling back.
     *
     * @param userId user identity for the slot, may be {@code null} for anonymous / single-tenant
     * @param sessionId session identity, may be {@code null} to use the agent's default session
     */
    default void interrupt(String userId, String sessionId) {
        warnIfSessionScopedInterruptFallsBack(userId, sessionId);
        interrupt();
    }

    /**
     * Interrupt the in-flight call for a specific {@code (userId, sessionId)} session with an
     * associated user message.
     *
     * <p>The default implementation falls back to {@link #interrupt(Msg)}. Multi-session agents and
     * wrappers <strong>must</strong> override this to target the identified slot. When either id is
     * non-blank, the default logs a warning before falling back.
     *
     * @param userId user identity for the slot, may be {@code null} for anonymous / single-tenant
     * @param sessionId session identity, may be {@code null} to use the agent's default session
     * @param msg user message associated with the interruption, may be {@code null}
     */
    default void interrupt(String userId, String sessionId, Msg msg) {
        warnIfSessionScopedInterruptFallsBack(userId, sessionId);
        interrupt(msg);
    }

    private static void warnIfSessionScopedInterruptFallsBack(RuntimeContext ctx) {
        if (ctx == null) {
            return;
        }
        warnIfSessionScopedInterruptFallsBack(ctx.getUserId(), ctx.getSessionId());
    }

    private static void warnIfSessionScopedInterruptFallsBack(String userId, String sessionId) {
        boolean hasUser = userId != null && !userId.isBlank();
        boolean hasSession = sessionId != null && !sessionId.isBlank();
        if (!hasUser && !hasSession) {
            return;
        }
        System.getLogger(Agent.class.getName())
                .log(
                        System.Logger.Level.WARNING,
                        "Agent.interrupt(...) default falls back to the no-session overload;"
                                + " multi-session wrappers must override to target userId={0},"
                                + " sessionId={1}",
                        userId,
                        sessionId);
    }

    /**
     * Returns the agent's runtime {@link AgentState}, or {@code null} if this agent type does not
     * maintain one.
     *
     * <p>This is the canonical access point used by tool methods declared with
     * {@code @Tool(stateInjected=true)}: the framework binds the live state to the
     * {@code AgentState} parameter at invocation time. For session-scoped lookup, use
     * {@link #getAgentState(String, String)} or {@link #getAgentState(RuntimeContext)}.
     */
    default AgentState getAgentState() {
        return null;
    }

    /**
     * Returns the {@link AgentState} for the given {@code (userId, sessionId)} slot, or {@code
     * null} if this agent type does not maintain session state (or none exists for the slot).
     *
     * <p>Protocol adapters may call this instead of probing concrete agent types. The default
     * returns {@code null}; multi-session agents override it.
     *
     * @param userId user identity for the slot, may be {@code null} for anonymous / single-tenant
     * @param sessionId session identity, may be {@code null} to use the agent's default session
     * @return the agent state for the identified session, or {@code null} if none exists
     */
    default AgentState getAgentState(String userId, String sessionId) {
        return null;
    }

    /**
     * Returns the {@link AgentState} for the session identified by {@code ctx}.
     *
     * @param ctx the runtime context (uses {@code getUserId()} and {@code getSessionId()}), may be
     *     {@code null}
     * @return the agent state for the identified session, or {@code null} if none exists
     */
    default AgentState getAgentState(RuntimeContext ctx) {
        if (ctx == null) {
            return getAgentState(null, null);
        }
        return getAgentState(ctx.getUserId(), ctx.getSessionId());
    }

    /**
     * Returns the agent's live {@link Toolkit}, or {@code null} if this agent type does not
     * maintain one.
     *
     * <p>This is the <em>runtime</em> toolkit — the same instance the agent uses when listing
     * available tools for the model and dispatching tool calls. Middleware that needs to register
     * tools dynamically (e.g., skill loaders) must use this accessor rather than any toolkit
     * reference captured at build time, because agents may deep-copy the toolkit during
     * construction.
     */
    default Toolkit getToolkit() {
        return null;
    }

    /**
     * Returns the agent's baseline {@link SkillFilter}, or {@code null} when this agent type
     * does not expose one. Consumers overlay per-call {@code RuntimeContext} filters on it
     * and fall back to their own default when {@code null}.
     */
    default SkillFilter getSkillFilter() {
        return null;
    }

    /**
     * Returns the agent's stable working directory for skill materialisation, or {@code null}
     * when this agent type does not provide one. Callers fall back to their own temporary
     * allocation when {@code null}.
     */
    default Path getSkillWorkDir() {
        return null;
    }
}
