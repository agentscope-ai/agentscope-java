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
import io.agentscope.core.tool.Toolkit;

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
     *
     * @deprecated since 2.0.0, use {@link #interrupt(RuntimeContext)} with explicit runtime
     *     context to ensure the interruption targets the correct session slot.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    void interrupt();

    /**
     * Interrupt the current agent execution with a user message.
     *
     * @deprecated since 2.0.0, use {@link #interrupt(RuntimeContext, Msg)} with explicit runtime
     *     context to ensure the interruption targets the correct session slot.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    void interrupt(Msg msg);

    /**
     * Interrupt the agent execution for the session identified by the given {@link RuntimeContext}.
     * This is the session-aware variant of {@link #interrupt()} that targets a specific
     * {@code (userId, sessionId)} session instead of a hardcoded default session.
     *
     * <p>When called from a middleware or tool that holds both {@code Agent agent} and
     * {@code RuntimeContext ctx}, this method ensures the interruption targets the current
     * in-flight call's session rather than a different session.
     *
     * <p>Default implementation delegates to {@link #interrupt()} for backward compatibility.
     * Subclasses that maintain per-session state (e.g., {@link io.agentscope.core.ReActAgent})
     * should override this to trigger the interrupt signal on the correct session slot.
     *
     * @param ctx the runtime context identifying the session to interrupt; if {@code null} or
     *            {@code ctx.getSessionId()} is {@code null}/{@code blank}, implementations may
     *            fall back to a default session
     */
    default void interrupt(RuntimeContext ctx) {
        interrupt();
    }

    /**
     * Interrupt the agent execution for the session identified by the given {@link RuntimeContext}
     * with an associated user message.
     *
     * <p>This is the session-aware variant of {@link #interrupt(Msg)} that targets a specific
     * {@code (userId, sessionId)} session instead of a hardcoded default session.
     *
     * <p>Default implementation delegates to {@link #interrupt(Msg)} for backward compatibility.
     * Subclasses that maintain per-session state (e.g., {@link io.agentscope.core.ReActAgent})
     * should override this to trigger the interrupt signal on the correct session slot.
     *
     * @param ctx the runtime context identifying the session to interrupt
     * @param msg optional user message to associate with the interruption
     */
    default void interrupt(RuntimeContext ctx, Msg msg) {
        interrupt(msg);
    }

    /**
     * Returns the agent's runtime {@link io.agentscope.core.state.AgentState}, or {@code null} if
     * this agent type does not maintain one.
     *
     * <p>This is the canonical access point used by tool methods declared with
     * {@code @Tool(stateInjected=true)}: the framework binds the live state to the
     * {@code AgentState} parameter at invocation time.
     */
    default io.agentscope.core.state.AgentState getAgentState() {
        return null;
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
}
