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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;

/**
 * Creates a new subagent instance for a single spawn or session. Registered under an {@code
 * agent_id} in {@link DefaultAgentManager}; each {@link #create(RuntimeContext)} call should
 * return a fresh agent when isolation is required.
 *
 * <p>This type replaces a raw {@link java.util.function.Supplier} for subagent wiring so call sites
 * and maps are self-documenting.
 *
 * <p><b>Phase B-0:</b> Implementations should bake {@code parentRc.getUserId()} and
 * {@code parentRc.getSessionId()} into the child agent's persisted {@code SessionKey} so that
 * {@code AgentState} stays isolated per (user, parent-session) — regardless of which
 * {@link io.agentscope.core.session.Session} backend (Workspace / Redis / InMemory / custom) is
 * configured. Both fields are nullable; when absent the child falls back to the legacy single
 * bucket form keyed only by declaration name.
 */
@FunctionalInterface
public interface SubagentFactory {

    /**
     * Builds a new subagent instance for the given parent runtime context. Implementations are
     * responsible for translating {@code parentRc} into appropriate state-isolation hints (e.g.
     * a parent-aware {@code SessionKey}); see the type-level javadoc.
     *
     * @param parentRc parent agent's runtime context at spawn time; may be
     *     {@link RuntimeContext#empty()} when caller has no scope
     */
    Agent create(RuntimeContext parentRc);
}
