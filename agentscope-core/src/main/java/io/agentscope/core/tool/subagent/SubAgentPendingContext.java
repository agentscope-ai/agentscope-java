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
package io.agentscope.core.tool.subagent;

import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the pending state of a single sub-agent tool during HITL (Human-in-the-Loop) interactions.
 *
 * <p>This immutable record encapsulates the complete state of a sub-agent tool that has been suspended,
 * containing all information needed to resume execution:
 *
 * <ul>
 *   <li>The tool ID that identifies the specific sub-agent tool</li>
 *   <li>The session ID of the sub-agent's execution context</li>
 *   <li>The list of pending tool results that need to be injected when resuming</li>
 * </ul>
 *
 * <h3>Immutability:</h3>
 * This record is immutable. The pendingResults list is defensively copied during construction
 * to prevent external modifications. This ensures thread-safety and predictable behavior.
 *
 * <h3>Usage:</h3>
 * This class is typically created by {@link SubAgentPendingStore} when consuming pending state
 * and is used to pass complete context information between components.
 *
 * @param toolId The tool ID that identifies the sub-agent tool
 * @param sessionId The session ID of the sub-agent's execution context
 * @param pendingResults The list of pending tool results that need to be injected when resuming
 */
public record SubAgentPendingContext(
        String toolId, String sessionId, List<ToolResultBlock> pendingResults) {

    /**
     * Creates a new SubAgentPendingContext with defensive copying of the results list.
     *
     * <p>The compact constructor ensures that the pendingResults list is defensively copied
     * to prevent external modifications after construction.
     *
     * @param toolId The tool ID
     * @param sessionId The session ID
     * @param pendingResults The list of pending results (will be copied)
     */
    public SubAgentPendingContext {
        if (pendingResults == null) {
            pendingResults = new ArrayList<>();
        } else {
            pendingResults = new ArrayList<>(pendingResults);
        }
    }
}
