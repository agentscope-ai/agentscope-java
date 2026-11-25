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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;

/**
 * Bridge between {@link io.agentscope.harness.agent.tool.AgentSpawnTool} and the gateway layer.
 * Allows spawned subagents to be exposed as user-addressable entry points without the spawn tool
 * needing to know about channels, routers, or the gateway implementation.
 *
 * <p>When a subagent is exposed, the gateway assigns it a {@code subagentId} that the user can
 * use to send messages directly to that subagent, bypassing normal binding-based routing.
 */
@FunctionalInterface
public interface SubagentGatewayBridge {

    /**
     * Result of exposing a subagent to the user.
     *
     * @param subagentId the user-visible handle for addressing this subagent directly
     */
    record ExposeResult(String subagentId) {}

    /**
     * Exposes a spawned subagent as a user-addressable entry point in the gateway.
     *
     * @param agentId the subagent type identifier
     * @param sessionId the session id assigned to the subagent
     * @param agent the agent instance
     * @param replyTo the outbound address for delivering replies back to the user's channel;
     *     may be null if no outbound channel context is available
     * @return the expose result containing the subagentId handle
     */
    ExposeResult expose(String agentId, String sessionId, Agent agent, OutboundAddress replyTo);

    /**
     * Exposes a subagent using the validated parent invocation context for tenant-aware recovery.
     * Existing four-argument implementations retain their anonymous exposure behavior.
     *
     * @param context the parent invocation context; may be null for legacy direct calls
     * @return the expose result containing the subagentId handle
     */
    default ExposeResult expose(
            String agentId,
            String sessionId,
            Agent agent,
            OutboundAddress replyTo,
            RuntimeContext context) {
        return expose(agentId, sessionId, agent, replyTo);
    }

    /**
     * Revokes every public handle exposed for the given child session.
     *
     * <p>The gateway owns the mapping from public handles to child sessions. The default value
     * preserves compatibility with existing expose-only bridge implementations; gateway-backed
     * bridges should override it so releasing a subagent also makes its public handles
     * unaddressable.
     *
     * @param sessionId the child session whose exposed handles should be revoked
     * @return whether the bridge revoked at least one handle or knows the capability is supported
     */
    default boolean revokeBySessionId(String sessionId) {
        return false;
    }
}
