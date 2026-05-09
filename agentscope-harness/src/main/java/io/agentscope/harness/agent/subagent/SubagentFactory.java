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

/**
 * Creates a new subagent instance for a single spawn or session. Registered under an {@code
 * agent_id} in {@link DefaultAgentManager}; each {@link #create()} call should return a fresh
 * agent when isolation is required.
 *
 * <p>This type replaces a raw {@link java.util.function.Supplier} for subagent wiring so call sites
 * and maps are self-documenting.
 */
@FunctionalInterface
public interface SubagentFactory {

    /** Builds a new subagent instance (typically a new {@link io.agentscope.harness.agent.HarnessAgent}). */
    Agent create();
}
