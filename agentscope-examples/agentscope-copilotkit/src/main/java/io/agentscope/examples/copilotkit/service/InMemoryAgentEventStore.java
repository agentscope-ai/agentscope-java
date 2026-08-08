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

import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory AgentEvent log keyed by threadId.
 *
 * <p>AgentScope {@link AgentEvent}s are the source of truth. AG-UI frames are projected on demand
 * (see {@link AgentEventAguiReplayer}) for {@code /agent/{agentId}/connect} and inspect APIs.
 *
 */
@Component
public final class InMemoryAgentEventStore {

    private static final int MAX_EVENTS_PER_THREAD = 5_000;

    private final ConcurrentHashMap<String, List<StoredAgentEvent>> eventsByThread =
            new ConcurrentHashMap<>();

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
     * Clear events for a thread.
     *
     * @param threadId thread identifier
     */
    public void clear(String threadId) {
        eventsByThread.remove(threadId);
    }

    /**
     * One AgentEvent captured during a specific AG-UI run.
     *
     * @param runId run that emitted the event
     * @param event source AgentEvent
     */
    public record StoredAgentEvent(String runId, AgentEvent event) {}
}
