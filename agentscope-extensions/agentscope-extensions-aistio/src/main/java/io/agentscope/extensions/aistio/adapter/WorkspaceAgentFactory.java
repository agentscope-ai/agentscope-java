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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.harness.agent.HarnessAgent;

/** Explicit application opt-in to consume the immutable control-plane definition per attempt. */
@FunctionalInterface
public interface WorkspaceAgentFactory {
    /** Creates a fresh attempt-owned agent, including its tools and credential policy. */
    HarnessAgent create(JsonNode definition, AgentTaskAssignment assignment);
}
