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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Context holder for the AG-UI stream pipeline.
 * Manages the emission of immediate events and the queuing of deferred end events
 * to ensure strict adherence to the AG-UI lifecycle protocol.
 */
public class StreamContext {
    // Prefixes used to prevent key collisions in the deferred events map,
    // as different blocks (e.g., Text and Reasoning) may share the same message ID.
    public static final String PREFIX_TEXT = "text:";
    public static final String PREFIX_REASONING = "reasoning:";
    public static final String PREFIX_TOOL = "tool:";

    private final String threadId;
    private final String runId;
    private final AguiAdapterConfig config;

    // Events ready to be emitted in the current processing cycle
    private final List<AguiEvent> emittedEvents = new ArrayList<>();

    // Deferred event queue for storing pending end events (Key: prefix + id)
    private final Map<String, AguiEvent> deferredEndEvents = new LinkedHashMap<>();

    private final Set<String> activeTextIds = new LinkedHashSet<>();
    private final Set<String> activeReasoningIds = new LinkedHashSet<>();
    private final Set<String> activeToolIds = new LinkedHashSet<>();

    // Fallback ID for tool results that might lack an explicit ID
    private String lastActiveToolId = null;

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
        this.emittedEvents.add(event);
    }

    /**
     * Registers an end event to be emitted later.
     * This decouples the start logic from the termination logic.
     *
     * @param id       The prefixed identifier for the component
     * @param endEvent The end event to defer
     */
    public void deferEndEvent(String id, AguiEvent endEvent) {
        this.deferredEndEvents.put(id, endEvent);
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
    }

    // --- Tool State Management ---

    public boolean isToolActive(String id) {
        return activeToolIds.contains(id);
    }

    public void addActiveTool(String id) {
        this.activeToolIds.add(id);
        this.lastActiveToolId = id; // Update the fallback ID
    }

    public void removeActiveTool(String id) {
        this.activeToolIds.remove(id);
        // If the removed ID matches the last recorded fallback ID, reset or step back the pointer
        if (Objects.equals(this.lastActiveToolId, id)) {
            if (activeToolIds.isEmpty()) {
                this.lastActiveToolId = null;
            } else {
                // Retrieve the last inserted element from the LinkedHashSet
                String[] array = activeToolIds.toArray(new String[0]);
                this.lastActiveToolId = array[array.length - 1];
            }
        }
    }

    public String getLastActiveToolId() {
        return this.lastActiveToolId;
    }
}
