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
     * @param userId the owning user namespace
     * @param parentSessionId the parent runtime session that spawned the child
     * @return the expose result containing the subagentId handle
     */
    ExposeResult expose(
            String agentId,
            String sessionId,
            Agent agent,
            OutboundAddress replyTo,
            String userId,
            String parentSessionId);
}
