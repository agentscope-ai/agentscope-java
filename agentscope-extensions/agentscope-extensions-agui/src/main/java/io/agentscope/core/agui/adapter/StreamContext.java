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
package io.agentscope.core.agui.adapter;

import io.agentscope.core.agui.event.AguiEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Context holder for the AG-UI stream pipeline.
 * Manages the emission of immediate events and the queuing of deferred end events
 * to ensure strict adherence to the AG-UI lifecycle protocol.
 */
public class StreamContext {
    private final String threadId;
    private final String runId;
    private final AguiAdapterConfig config;

    // Events ready to be emitted in the current processing cycle
    private final List<AguiEvent> emittedEvents = new ArrayList<>();

    // Deferred event queue for storing pending end events (Key: prefix + id)
    private final Map<String, AguiEvent> deferredEndEvents = new LinkedHashMap<>();

    // Prefixes used to prevent key collisions in the deferred events map,
    // as different blocks (e.g., Text and Reasoning) may share the same message ID.
    public static final String PREFIX_TEXT = "text:";
    public static final String PREFIX_REASONING = "reasoning:";
    public static final String PREFIX_TOOL = "tool:";

    private final Set<String> activeTextIds = new LinkedHashSet<>();
    private final Set<String> activeReasoningIds = new LinkedHashSet<>();
    private final Set<String> activeToolIds = new LinkedHashSet<>();

    private final Set<String> finishedTextIds = new HashSet<>();
    private final Set<String> finishedReasoningIds = new HashSet<>();

    private final Set<String> anonymousToolIds = new LinkedHashSet<>();
    private final Map<String, String> anonymousToolNames = new LinkedHashMap<>();

    /**
     * Initializes a new StreamContext.
     *
     * @param threadId The thread ID
     * @param runId    The run ID
     * @param config   The adapter configuration
     */
    public StreamContext(String threadId, String runId, AguiAdapterConfig config) {
        this.threadId = threadId;
        this.runId = runId;
        this.config = config;
    }

    /**
     * Returns the thread identifier associated with this stream context.
     *
     * @return the thread ID
     */
    public String getThreadId() {
        return threadId;
    }

    /**
     * Returns the run identifier associated with this stream context.
     *
     * @return the run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Returns the adapter configuration used by this stream context.
     *
     * @return the adapter configuration
     */
    public AguiAdapterConfig getConfig() {
        return config;
    }

    // --- Event Emission and Deferred Management API ---

    /**
     * Adds an event to the emission queue for the current processing cycle.
     *
     * @param event The AG-UI event to emit
     */
    public void emit(AguiEvent event) {
        emittedEvents.add(event);
    }

    /**
     * Registers an end event to be emitted later.
     * This decouples the start logic from the termination logic.
     *
     * @param id       The prefixed identifier for the component
     * @param endEvent The end event to defer
     */
    public void deferEndEvent(String id, AguiEvent endEvent) {
        deferredEndEvents.put(id, endEvent);
    }

    /**
     * Retrieves and clears all events accumulated in the current cycle.
     *
     * @return A list of events to be dispatched downstream
     */
    public List<AguiEvent> getAndClearEmittedEvents() {
        List<AguiEvent> result = new ArrayList<>(emittedEvents);
        emittedEvents.clear();
        return result;
    }

    /**
     * Flushes a specific deferred end event into the emission queue.
     *
     * @param id The prefixed identifier of the event to flush
     */
    public void flushEndEvent(String id) {
        AguiEvent endEvent = deferredEndEvents.remove(id);
        if (endEvent != null) {
            emit(endEvent);
        }
    }

    /**
     * Flushes all remaining deferred end events.
     * Typically called during stream termination or error recovery to ensure all UI components are closed.
     *
     * @return A list of all remaining deferred events
     */
    public List<AguiEvent> flushAllRemainingDeferred() {
        List<AguiEvent> remaining = new ArrayList<>(deferredEndEvents.values());

        deferredEndEvents.clear();

        activeTextIds.clear();
        activeReasoningIds.clear();
        activeToolIds.clear();

        finishedTextIds.clear();
        finishedReasoningIds.clear();

        return remaining;
    }

    /**
     * Flushes all active text end events.
     * Commonly used when an interruption occurs (e.g., a tool call starts).
     */
    public void flushAllActiveTexts() {
        for (String id : new ArrayList<>(activeTextIds)) {
            flushEndEvent(PREFIX_TEXT + id);
            removeActiveText(id);
        }
    }

    /**
     * Flushes all active reasoning end events.
     * Commonly used when an interruption occurs (e.g., a tool call starts).
     */
    public void flushAllActiveReasonings() {
        for (String id : new ArrayList<>(activeReasoningIds)) {
            flushEndEvent(PREFIX_REASONING + id);
            removeActiveReasoning(id);
        }
    }

    // --- Text State Management ---

    public boolean isTextActive(String id) {
        return activeTextIds.contains(id);
    }

    public void addActiveText(String id) {
        activeTextIds.add(id);
    }

    public void removeActiveText(String id) {
        activeTextIds.remove(id);
        finishedTextIds.add(id);
    }

    public boolean isTextFinished(String id) {
        return finishedTextIds.contains(id);
    }

    // --- Reasoning State Management ---

    public boolean isReasoningActive(String id) {
        return activeReasoningIds.contains(id);
    }

    public void addActiveReasoning(String id) {
        activeReasoningIds.add(id);
    }

    public void removeActiveReasoning(String id) {
        activeReasoningIds.remove(id);
        finishedReasoningIds.add(id);
    }

    public boolean isReasoningFinished(String id) {
        return finishedReasoningIds.contains(id);
    }

    // --- Tool State Management ---

    public boolean isToolActive(String id) {
        return activeToolIds.contains(id);
    }

    public void addActiveTool(String id) {
        activeToolIds.add(id);
    }

    public void removeActiveTool(String id) {
        activeToolIds.remove(id);
        anonymousToolIds.remove(id);
        anonymousToolNames.remove(id);
    }

    /**
     * Resolves the tool call ID for a ToolResultBlock that does not provide an explicit ID.
     *
     * <p>An anonymous tool result can only be matched safely when there is exactly one active tool
     * call. If multiple tool calls are active, the result is ambiguous and should not be associated
     * with any particular tool call by guesswork.
     *
     * @return the only active tool call ID, or an empty Optional if the result cannot be matched
     */
    public Optional<String> resolveAnonymousToolResultId() {
        if (activeToolIds.size() == 1) {
            return Optional.of(activeToolIds.iterator().next());
        }
        return Optional.empty();
    }

    /**
     * Resolves or creates a stable local ID for a ToolUseBlock that does not provide an explicit ID.
     *
     * <p>When streaming tool arguments, multiple ToolUseBlock chunks may belong to the same anonymous
     * tool call. This method reuses an active anonymous tool call with the same normalized tool name
     * whenever the match is unambiguous. If no matching anonymous tool call exists, a new local ID is
     * generated and tracked for subsequent chunks.
     *
     * @param toolName The tool name from the ToolUseBlock
     * @return a stable local tool call ID for the anonymous tool use
     */
    public String resolveOrCreateAnonymousToolUseId(String toolName) {
        String normalizedName =
                toolName != null && !toolName.isBlank() ? toolName : "unknown";

        String matchedId = null;
        for (String id : anonymousToolIds) {
            if (activeToolIds.contains(id)
                    && Objects.equals(anonymousToolNames.get(id), normalizedName)) {
                matchedId = id;
            }
        }

        if (matchedId != null) {
            return matchedId;
        }

        String generatedId = UUID.randomUUID().toString();
        anonymousToolIds.add(generatedId);
        anonymousToolNames.put(generatedId, normalizedName);
        return generatedId;
    }
}
