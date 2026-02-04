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

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.StateModule;
import java.util.List;
import java.util.Optional;

/**
 * Sub-agent context for managing tool call results during HITL (Human-in-the-Loop) interactions.
 *
 * <p>This class provides business logic for handling sub-agent results, including:
 *
 * <ul>
 *   <li>Detection of whether a ToolResultBlock is a sub-agent result confirmation</li>
 *   <li>Extraction of sub-agent session IDs from tool results</li>
 *   <li>Delegation of state management to {@link SubAgentPendingStore}</li>
 * </ul>
 *
 * <p>The actual storage and persistence of pending states is handled by {@link SubAgentPendingStore},
 * which delegates to {@link SubAgentPendingStore} that implements {@link io.agentscope.core.state.State} for direct serialization support. This class follows a sessionId-first
 * constraint: a session ID must be registered before any results can be added for that tool.
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * SubAgentContext context = new SubAgentContext();
 *
 * // 1. Store session ID first (required before adding results)
 * context.setSessionId("tool-123", "session-abc");
 *
 * // 2. Submit sub-agent result (can only be added after session ID is set)
 * context.submitSubAgentResult("tool-123", result);
 *
 * // 3. Check if result is from sub-agent
 * if (SubAgentContext.isSubAgentResult(result)) {
 *     Optional<String> sessionId = SubAgentContext.extractSessionId(result);
 * }
 *
 * // 4. Consume pending results
 * Optional<SubAgentPendingContext> pending = context.consumePendingResult("tool-123");
 *
 * // 5. Save state to session
 * context.saveTo(session, sessionKey);
 *
 * // 6. Later, restore state
 * context.loadFrom(session, sessionKey);
 * }</pre>
 *
 * <h3>Thread Safety:</h3>
 * This class is thread-safe and delegates all state management to the thread-safe
 * {@link SubAgentPendingStore}.
 */
public class SubAgentContext implements StateModule {

    /** Metadata key for sub-agent session ID in ToolResultBlock. */
    public static final String METADATA_SUBAGENT_SESSION_ID = "subagent_session_id";

    /** Metadata key for sub-agent suspend type in ToolResultBlock. */
    public static final String METADATA_GENERATE_REASON = "subagent_generate_reason";

    /** The pending store manager. */
    private SubAgentPendingStore pendingStore;

    /**
     * Creates a new SubAgentContext with an empty pending state.
     */
    public SubAgentContext() {
        this.pendingStore = new SubAgentPendingStore();
    }

    /**
     * Gets the pending state manager.
     *
     * <p>This provides access to the underlying state management for advanced use cases.
     * Use with caution as direct modifications may bypass business logic validation.
     *
     * @return The pending state manager
     */
    public SubAgentPendingStore getPendingStore() {
        return pendingStore;
    }

    /**
     * Stores the session ID for a tool.
     *
     * <p>This method must be called before any results can be added for the tool.
     * This enforces the sessionId-first constraint to ensure proper lifecycle management.
     *
     * @param toolId The tool ID
     * @param sessionId The session ID
     */
    public void setSessionId(String toolId, String sessionId) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId cannot be null");
        }
        String existingSessionId = pendingStore.getSessionId(toolId);
        if (existingSessionId != null && existingSessionId.equals(sessionId)) {
            return;
        }
        pendingStore.setSessionId(toolId, sessionId);
    }

    /**
     * Gets pending tool results for a tool.
     *
     * @param toolId The sub-agent tool ID
     * @return An Optional containing the pending results, or empty if none exist
     */
    public Optional<List<ToolResultBlock>> getPendingResult(String toolId) {
        List<ToolResultBlock> results = pendingStore.getPendingResults(toolId);
        return Optional.ofNullable(results.isEmpty() ? null : results);
    }

    /**
     * Gets the session ID for a tool.
     *
     * @param toolId The tool ID
     * @return An Optional containing the session ID, or empty if none exist
     */
    public Optional<String> getSessionId(String toolId) {
        if (toolId == null) {
            throw new IllegalArgumentException("toolId cannot be null");
        }
        return Optional.ofNullable(pendingStore.getSessionId(toolId));
    }

    /**
     * Consumes and removes the pending context for a tool.
     *
     * <p>This method atomically retrieves and removes all pending state for the tool,
     * including both the session ID and pending results. This is typically called when
     * resuming a suspended sub-agent execution.
     *
     * @param toolId The tool ID
     * @return An Optional containing the pending context, or empty if none exist
     */
    public Optional<SubAgentPendingContext> consumePendingResult(String toolId) {
        if (!pendingStore.contains(toolId)) {
            return Optional.empty();
        }
        SubAgentPendingContext pending =
                new SubAgentPendingContext(
                        toolId,
                        pendingStore.getSessionId(toolId),
                        pendingStore.getPendingResults(toolId));
        clearToolResult(toolId);
        return Optional.of(pending);
    }

    /**
     * Clears the pending context for a tool.
     *
     * <p>This removes both the session ID and all pending results for the tool.
     *
     * @param toolId The tool ID
     */
    public void clearToolResult(String toolId) {
        pendingStore.remove(toolId);
    }

    /**
     * Checks if a tool has any pending results.
     *
     * @param toolId The tool ID
     * @return true if the tool has pending results, false otherwise
     */
    public boolean hasPendingResult(String toolId) {
        return pendingStore.hasPendingResults(toolId);
    }

    /**
     * Clears all pending data for all tools.
     *
     * <p>This removes all session IDs and pending results from the context.
     */
    public void clear() {
        pendingStore.clearAll();
    }

    /**
     * Submit sub-agent tool call results and store them.
     *
     * <p>This method should be called when users provide confirmation or results for suspended sub-agents.
     * This is a convenience method that wraps a single result into a list and delegates to the batch submission method.
     *
     * @param subAgentToolId The sub-agent tool ID
     * @param pendingResult The sub-agent tool result
     * @throws IllegalArgumentException if the result is null
     */
    public void submitSubAgentResult(String subAgentToolId, ToolResultBlock pendingResult) {
        if (pendingResult == null) {
            throw new IllegalArgumentException("result cannot be null");
        }
        submitSubAgentResults(subAgentToolId, List.of(pendingResult));
    }

    /**
     * Submit multiple sub-agent tool call results and store them.
     *
     * <p>This is the core method for submitting sub-agent results. It validates and stores multiple
     * results for suspended sub-agents. Tools must have registered session IDs before adding results
     * (enforced through sessionId-first constraint).
     *
     * @param subAgentToolId The sub-agent tool ID
     * @param pendingResults A list of sub-agent tool results
     * @throws IllegalArgumentException If the results list is null or empty, or if the tool does not exist in pending state
     */
    public void submitSubAgentResults(String subAgentToolId, List<ToolResultBlock> pendingResults) {
        if (pendingResults == null || pendingResults.isEmpty()) {
            throw new IllegalArgumentException("pendingResults cannot be null or empty");
        }

        if (!pendingStore.contains(subAgentToolId)) {
            throw new IllegalArgumentException("No pending result for tool: " + subAgentToolId);
        }

        pendingStore.addResults(subAgentToolId, pendingResults);
    }

    /**
     * Extracts the sub-agent session ID from a tool result block.
     *
     * <p>This is a static utility method that can be called without a SubAgentContext instance.
     *
     * @param result The tool result block
     * @return An Optional containing the session ID if present, otherwise empty
     */
    public static Optional<String> extractSessionId(ToolResultBlock result) {
        if (result == null || result.getMetadata() == null) {
            return Optional.empty();
        }
        Object sessionId = result.getMetadata().get(METADATA_SUBAGENT_SESSION_ID);
        return sessionId instanceof String ? Optional.of((String) sessionId) : Optional.empty();
    }

    /**
     * Gets the generation reason for a sub-agent.
     *
     * <p>This method retrieves the generation reason from the tool result and returns the corresponding enum value.
     * If no valid generation reason is found, it defaults to MODEL_STOP.
     *
     * @param toolResult The tool execution result
     * @return Returns the generation reason, or MODEL_STOP if no valid reason is found
     */
    public static GenerateReason getSubAgentGenerateReason(ToolResultBlock toolResult) {
        Object reason = toolResult.getMetadata().get(SubAgentContext.METADATA_GENERATE_REASON);
        if (reason instanceof GenerateReason) {
            return (GenerateReason) reason;
        }
        return GenerateReason.MODEL_STOP;
    }

    /**
     * Checks if the tool result originates from a sub-agent.
     *
     * @param result The tool result block
     * @return True if the result comes from a sub-agent, false otherwise
     */
    public static boolean isSubAgentResult(ToolResultBlock result) {
        return extractSessionId(result).isPresent();
    }

    // ==================== StateModule Implementation ====================

    /**
     * Saves the context state to a session.
     *
     * <p>Delegates to the pending state manager for actual persistence.
     * The pending state is serialized directly as it implements {@link io.agentscope.core.state.State}.
     *
     * @param session The session object
     * @param sessionKey The session identifier
     */
    @Override
    public void saveTo(Session session, SessionKey sessionKey) {
        session.save(sessionKey, "subagent_context", pendingStore);
    }

    /**
     * Restores the context state from a session.
     *
     * <p>Delegates to the pending state manager for actual restoration.
     * The existing pending state is replaced with the loaded state.
     *
     * @param session The session object
     * @param sessionKey The session identifier
     */
    @Override
    public void loadFrom(Session session, SessionKey sessionKey) {
        this.pendingStore = null;
        session.get(sessionKey, "subagent_context", SubAgentPendingStore.class)
                .ifPresent(state -> this.pendingStore = state);
    }
}
