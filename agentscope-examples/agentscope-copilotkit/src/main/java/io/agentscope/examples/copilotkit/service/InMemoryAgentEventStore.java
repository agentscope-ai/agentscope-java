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
package io.agentscope.examples.copilotkit.service;

import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory AgentEvent log keyed by threadId.
 *
 * <p>AgentScope {@link AgentEvent}s are the source of truth. AG-UI frames are projected on demand
 * (see {@link AgentEventAguiReplayer}) for {@code /agent/{agentId}/connect} and inspect APIs.
 *
 * <p>Per-run input messages (user / tool / resume) are stored separately so connect replay can
 * re-attach them to {@code RUN_STARTED.input}, matching CopilotKit's persistence model.
 *
 */
@Component
public final class InMemoryAgentEventStore {

    private static final int MAX_EVENTS_PER_THREAD = 5_000;

    private final ConcurrentHashMap<String, List<StoredAgentEvent>> eventsByThread =
            new ConcurrentHashMap<>();

    /** threadId → (runId → new input messages for that run). */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<AguiMessage>>>
            runInputsByThread = new ConcurrentHashMap<>();

    /**
     * Append one AgentEvent for a thread/run.
     *
     * @param threadId thread identifier
     * @param runId run identifier that produced the event
     * @param event source AgentEvent
     */
    public void append(String threadId, String runId, AgentEvent event) {
        if (threadId == null || threadId.isBlank() || event == null) {
            return;
        }
        String resolvedRunId = runId == null || runId.isBlank() ? "unknown" : runId;
        StoredAgentEvent stored = new StoredAgentEvent(resolvedRunId, event);
        eventsByThread.compute(
                threadId,
                (id, existing) -> {
                    List<StoredAgentEvent> events = existing != null ? existing : new ArrayList<>();
                    synchronized (events) {
                        events.add(stored);
                        while (events.size() > MAX_EVENTS_PER_THREAD) {
                            events.remove(0);
                        }
                    }
                    return events;
                });
    }

    /**
     * Persist the new AG-UI input messages for a run (typically the latest user message).
     *
     * <p>CopilotKit reconnect restores conversation turns from {@code RUN_STARTED.input.messages}.
     * Messages whose ids were already stored for this thread are skipped.
     *
     * @param threadId thread identifier
     * @param runId run identifier
     * @param messages candidate input messages from {@code RunAgentInput}
     */
    public void putRunInputMessages(String threadId, String runId, List<AguiMessage> messages) {
        if (threadId == null
                || threadId.isBlank()
                || runId == null
                || runId.isBlank()
                || messages == null
                || messages.isEmpty()) {
            return;
        }
        Set<String> knownIds = knownMessageIds(threadId);
        List<AguiMessage> fresh = new ArrayList<>();
        for (AguiMessage message : messages) {
            if (message == null || message.getId() == null || message.getId().isBlank()) {
                continue;
            }
            if (knownIds.contains(message.getId())) {
                continue;
            }
            fresh.add(message);
            knownIds.add(message.getId());
        }
        if (fresh.isEmpty()) {
            return;
        }
        runInputsByThread
                .computeIfAbsent(threadId, id -> new ConcurrentHashMap<>())
                .put(runId, List.copyOf(fresh));
    }

    /**
     * Input messages previously stored for a run, or empty when none.
     *
     * @param threadId thread identifier
     * @param runId run identifier
     * @return immutable list, never null
     */
    public List<AguiMessage> getRunInputMessages(String threadId, String runId) {
        if (threadId == null || runId == null) {
            return List.of();
        }
        Map<String, List<AguiMessage>> byRun = runInputsByThread.get(threadId);
        if (byRun == null) {
            return List.of();
        }
        List<AguiMessage> messages = byRun.get(runId);
        return messages == null ? List.of() : messages;
    }

    /**
     * Snapshot of persisted AgentEvents for a thread.
     *
     * @param threadId thread identifier
     * @return immutable copy, never null
     */
    public List<StoredAgentEvent> snapshot(String threadId) {
        List<StoredAgentEvent> events = eventsByThread.get(threadId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    /**
     * Whether the thread has any persisted AgentEvents.
     *
     * @param threadId thread identifier
     * @return true if non-empty
     */
    public boolean hasEvents(String threadId) {
        List<StoredAgentEvent> events = eventsByThread.get(threadId);
        if (events == null) {
            return false;
        }
        synchronized (events) {
            return !events.isEmpty();
        }
    }

    /**
     * Clear events and run inputs for a thread.
     *
     * @param threadId thread identifier
     */
    public void clear(String threadId) {
        eventsByThread.remove(threadId);
        runInputsByThread.remove(threadId);
    }

    private Set<String> knownMessageIds(String threadId) {
        Set<String> ids = new LinkedHashSet<>();
        Map<String, List<AguiMessage>> byRun = runInputsByThread.get(threadId);
        if (byRun == null) {
            return ids;
        }
        for (List<AguiMessage> messages : byRun.values()) {
            for (AguiMessage message : messages) {
                if (message != null && message.getId() != null) {
                    ids.add(message.getId());
                }
            }
        }
        return ids;
    }

    /**
     * One AgentEvent captured during a specific AG-UI run.
     *
     * @param runId run that emitted the event
     * @param event source AgentEvent
     */
    public record StoredAgentEvent(String runId, AgentEvent event) {}
}
