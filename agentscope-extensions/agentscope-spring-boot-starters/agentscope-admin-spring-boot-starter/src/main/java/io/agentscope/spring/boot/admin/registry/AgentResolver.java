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
package io.agentscope.spring.boot.admin.registry;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * Small unwrap helper that hides the {@code HarnessAgent} composition from admin call sites.
 *
 * <p>{@link HarnessAgent} wraps a {@link ReActAgent} delegate via composition, so {@code agent
 * instanceof ReActAgent} returns {@code false} for harness-built agents. Every read-only accessor
 * the admin surface needs (model, AgentState, maxIters, permission engine, session, sessionKey,
 * middlewares) is either defined on the delegate or forwarded by {@code HarnessAgent} to the
 * delegate, so unwrapping once at the boundary is the simplest correct treatment.
 */
public final class AgentResolver {

    private AgentResolver() {}

    /**
     * Returns the underlying {@link ReActAgent} for both bare {@code ReActAgent} instances and for
     * {@link HarnessAgent}-wrapped agents (via {@link HarnessAgent#getDelegate()}). Returns
     * {@code null} for any other {@link Agent} implementation, so callers can short-circuit
     * gracefully.
     */
    public static ReActAgent unwrapReActAgent(Agent agent) {
        if (agent instanceof ReActAgent r) {
            return r;
        }
        if (agent instanceof HarnessAgent h) {
            return h.getDelegate();
        }
        return null;
    }
}
