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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.AguiException;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link AgentResolver} for Spring Boot integration.
 *
 * <p>This resolver supports two modes:
 * <ul>
 *   <li><b>Simple mode</b>: Directly looks up agents from the registry</li>
 *   <li><b>Session mode</b>: Uses {@link AguiSessionManager} for server-side memory</li>
 * </ul>
 */
public class DefaultAgentResolver implements AgentResolver {

    private final AguiAgentRegistry registry;
    private final AguiSessionManager sessionManager;
    private final boolean serverSideMemory;

    /** Tracks the agentId used for each threadId during a request lifecycle. */
    private final Map<String, String> threadAgentIdMap = new ConcurrentHashMap<>();

    /**
     * Creates a simple resolver without session support.
     *
     * @param registry The agent registry
     */
    public DefaultAgentResolver(AguiAgentRegistry registry) {
        this(registry, null, false);
    }

    /**
     * Creates a resolver with optional session support.
     *
     * @param registry The agent registry
     * @param sessionManager The session manager (may be null)
     * @param serverSideMemory Whether to enable server-side memory
     */
    public DefaultAgentResolver(
            AguiAgentRegistry registry,
            AguiSessionManager sessionManager,
            boolean serverSideMemory) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.sessionManager = sessionManager;
        this.serverSideMemory = serverSideMemory && sessionManager != null;
    }

    @Override
    public Agent resolveAgent(String agentId, String threadId) {
        if (serverSideMemory && sessionManager != null) {
            // Track the agentId for this threadId so hasMemory/onComplete can use it
            threadAgentIdMap.put(threadId, agentId);
            // Server-side memory mode: use session manager
            return sessionManager.getOrCreateAgent(
                    threadId,
                    agentId,
                    () ->
                            registry.getAgent(agentId)
                                    .orElseThrow(
                                            () ->
                                                    new AguiException.AgentNotFoundException(
                                                            agentId)));
        } else {
            // Standard mode: create new agent for each request
            return registry.getAgent(agentId)
                    .orElseThrow(() -> new AguiException.AgentNotFoundException(agentId));
        }
    }

    @Override
    public boolean hasMemory(String threadId) {
        if (serverSideMemory && sessionManager != null) {
            String agentId = threadAgentIdMap.get(threadId);
            return agentId != null && sessionManager.hasMemory(threadId, agentId);
        }
        return false;
    }

    @Override
    public void onComplete(String threadId, Agent agent) {
        if (serverSideMemory && sessionManager != null) {
            String agentId = threadAgentIdMap.remove(threadId);
            if (agentId == null) {
                agentId = agent.getAgentId();
            }
            sessionManager.saveAgent(threadId, agentId, agent);
        }
    }

    /**
     * Creates a new builder for DefaultAgentResolver.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for DefaultAgentResolver. */
    public static class Builder {

        private AguiAgentRegistry registry;
        private AguiSessionManager sessionManager;
        private boolean serverSideMemory = false;

        /**
         * Set the agent registry.
         *
         * @param registry The agent registry
         * @return This builder
         */
        public Builder registry(AguiAgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Set the session manager for server-side memory support.
         *
         * @param sessionManager The session manager
         * @return This builder
         */
        public Builder sessionManager(AguiSessionManager sessionManager) {
            this.sessionManager = sessionManager;
            return this;
        }

        /**
         * Enable or disable server-side memory management.
         *
         * @param enabled Whether to enable server-side memory
         * @return This builder
         */
        public Builder serverSideMemory(boolean enabled) {
            this.serverSideMemory = enabled;
            return this;
        }

        /**
         * Build the resolver.
         *
         * @return The built resolver
         * @throws NullPointerException if registry is not set
         */
        public DefaultAgentResolver build() {
            return new DefaultAgentResolver(registry, sessionManager, serverSideMemory);
        }
    }
}
