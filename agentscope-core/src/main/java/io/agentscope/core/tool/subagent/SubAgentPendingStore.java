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
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the pending state of multiple sub-agent tools during HITL (Human-in-the-Loop) interactions.
 *
 * <p>This class is responsible for storing and managing pending states for sub-agent tools.
 * It enforces a sessionId-first constraint: a session ID must be registered before any results
 * can be added for that tool. This ensures proper lifecycle management and prevents orphaned
 * results without associated sessions.
 *
 * <h3>Storage Structure:</h3>
 * The storage is organized by tool ID: {@code Map<String, SubAgentPendingContext>}.
 * Each tool ID maps to a complete context containing the tool ID, session ID, and pending results.
 * This single-source-of-truth design ensures data consistency.
 *
 * <h3>Key Features:</h3>
 * <ul>
 *   <li>Thread-safe storage using ConcurrentHashMap</li>
 *   <li>SessionId-first constraint: results can only be added after session ID is registered</li>
 *   <li>Defensive copying to prevent external modifications</li>
 *   <li>Simple CRUD operations for managing pending states</li>
 *   <li>Implements {@link State} for direct serialization support</li>
 *   <li>Single data source ensures consistency</li>
 * </ul>
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * // 1. Register session ID first (required) - creates a pending context
 * state.setSessionId("tool-123", "session-abc");
 *
 * // 2. Add results to the registered session
 * state.addResult("tool-123", result1);
 * state.addResult("tool-123", result2);
 *
 * // 3. Retrieve data
 * String sessionId = state.getSessionId("tool-123");
 * List<ToolResultBlock> results = state.getPendingResults("tool-123");
 *
 * // 4. Clean up when done
 * state.remove("tool-123");
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 * This class is thread-safe and can be used concurrently from multiple threads.
 * All operations are atomic at the method level.
 */
public class SubAgentPendingStore implements State {

    /**
     * Map of tool IDs to their pending contexts.
     * This is the single source of truth for all pending state data.
     * Each tool ID maps to a complete context containing the tool ID, session ID, and pending results.
     */
    private final Map<String, SubAgentPendingContext> toolIdToContext = new ConcurrentHashMap<>();

    /**
     * Gets the session ID for a tool.
     *
     * @param toolId The tool ID
     * @return The session ID, or null if not found
     */
    public String getSessionId(String toolId) {
        SubAgentPendingContext context = toolIdToContext.get(toolId);
        return context == null ? null : context.sessionId();
    }

    /**
     * Sets or updates the session ID for a tool.
     *
     * <p>This method must be called before any results can be added for the tool.
     * It creates a new SubAgentPendingContext containing the tool ID and session ID.
     * If the tool already has a context, it will be replaced.
     *
     * @param toolId The tool ID
     * @param sessionId The session ID
     * @throws IllegalArgumentException if toolId or sessionId is null
     */
    public void setSessionId(String toolId, String sessionId) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId cannot be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId cannot be null");
        }

        // Create or update the context for this tool
        SubAgentPendingContext context =
                new SubAgentPendingContext(toolId, sessionId, new ArrayList<>());
        toolIdToContext.put(toolId, context);
    }

    /**
     * Gets the pending tool results for a tool.
     *
     * <p>Returns a defensive copy of the results list to prevent external modifications.
     *
     * @param toolId The tool ID
     * @return A defensive copy of the pending results list, or empty list if not found
     */
    public List<ToolResultBlock> getPendingResults(String toolId) {
        SubAgentPendingContext context = toolIdToContext.get(toolId);
        if (context == null) {
            return new ArrayList<>();
        }

        List<ToolResultBlock> results = context.pendingResults();
        return results == null ? new ArrayList<>() : new ArrayList<>(results);
    }

    /**
     * Adds a single result to the pending results for a tool.
     *
     * <p>The tool must have a registered session ID before results can be added.
     *
     * @param toolId The tool ID
     * @param result The result to add
     * @throws IllegalStateException if the tool does not have a registered session ID
     * @throws IllegalArgumentException if toolId or result is null
     */
    public void addResult(String toolId, ToolResultBlock result) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId cannot be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        SubAgentPendingContext existingContext = toolIdToContext.get(toolId);
        if (existingContext == null) {
            throw new IllegalStateException(
                    "Cannot add result for tool '"
                            + toolId
                            + "' without a registered session ID. "
                            + "Call setSessionId() first.");
        }

        addResults(toolId, List.of(result));
    }

    /**
     * Adds multiple results to the pending results for a tool.
     *
     * <p>The tool must have a registered session ID before results can be added.
     *
     * @param toolId The tool ID
     * @param result The results to add
     * @throws IllegalStateException if the tool does not have a registered session ID
     * @throws IllegalArgumentException if toolId or result is null
     */
    public void addResults(String toolId, List<ToolResultBlock> result) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId cannot be null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        SubAgentPendingContext existingContext = toolIdToContext.get(toolId);
        if (existingContext == null) {
            throw new IllegalStateException(
                    "Cannot add result for tool '"
                            + toolId
                            + "' without a registered session ID. "
                            + "Call setSessionId() first.");
        }

        // Create new list with the added result
        List<ToolResultBlock> newResults = new ArrayList<>(existingContext.pendingResults());
        newResults.addAll(result);

        // Create new context with updated results
        SubAgentPendingContext newContext =
                new SubAgentPendingContext(
                        existingContext.toolId(), existingContext.sessionId(), newResults);
        toolIdToContext.put(toolId, newContext);
    }

    /**
     * Removes all pending data for a tool (both session ID and results).
     *
     * @param toolId The tool ID
     */
    public SubAgentPendingContext remove(String toolId) {
        if (toolId != null) {
            return toolIdToContext.remove(toolId);
        }
        return null;
    }

    /**
     * Checks if there are any pending results.
     *
     * @return true if there are no pending results, false otherwise
     */
    public boolean isEmpty() {
        return toolIdToContext.isEmpty();
    }

    /**
     * Checks if a tool has a registered session ID.
     *
     * <p>This is the primary check for whether a tool is in the system.
     * A tool must have a session ID before it can have results.
     *
     * @param toolId The tool ID
     * @return true if the tool has a registered session ID, false otherwise
     */
    public boolean contains(String toolId) {
        return toolId != null && toolIdToContext.containsKey(toolId);
    }

    /**
     * Checks if a tool has any pending results.
     *
     * @param toolId The tool ID
     * @return true if the tool has pending results, false otherwise
     */
    public boolean hasPendingResults(String toolId) {
        if (toolId == null) {
            return false;
        }
        SubAgentPendingContext context = toolIdToContext.get(toolId);
        return context != null && !context.pendingResults().isEmpty();
    }

    /**
     * Clears all pending data for all tools.
     *
     * <p>This removes all session IDs and all pending results from the state.
     */
    public void clearAll() {
        toolIdToContext.clear();
    }
}
