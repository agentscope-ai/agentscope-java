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
package io.agentscope.core.agui.store;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable materialized presentation state for one AG-UI thread.
 *
 * <p>A snapshot captures what a reconnecting client should draw: the message transcript, the
 * folded state, any activity frames, and the trailing run id. It is <b>presentation-only</b>:
 * derived from the live event stream, safe to lose, and not a source of truth for human-in-the-loop
 * contracts (the agent state store and resume coordinator own those).
 *
 * @param threadId the AG-UI thread id
 * @param messages the materialized conversation messages
 * @param state the folded AG-UI state
 * @param activities the materialized activity frames
 * @param pendingOutcome the trailing unresolved run outcome, or null when the run completed
 *     normally; only the trailing interrupt is ever retained
 * @param lastRunId the run id that produced this snapshot
 * @param updatedAt the wall-clock millis when the snapshot was materialized
 */
public record AguiThreadSnapshot(
        String threadId,
        List<AguiMessage> messages,
        Map<String, Object> state,
        List<ActivityFrame> activities,
        AguiEvent.RunFinishedOutcome pendingOutcome,
        String lastRunId,
        long updatedAt) {

    /**
     * One materialized activity frame, keyed by {@code (messageId, activityType)}.
     *
     * @param messageId the message id the activity is attached to
     * @param activityType the activity type
     * @param content the activity content
     */
    public record ActivityFrame(
            String messageId, String activityType, Map<String, Object> content) {
        public ActivityFrame {
            content =
                    content != null
                            ? Collections.unmodifiableMap(new LinkedHashMap<>(content))
                            : Collections.emptyMap();
        }
    }

    /**
     * Canonical constructor with defensive copies, matching the style of
     * {@link AguiEvent.MessagesSnapshot}.
     */
    public AguiThreadSnapshot {
        messages =
                messages != null
                        ? Collections.unmodifiableList(new ArrayList<>(messages))
                        : Collections.emptyList();
        state =
                state != null
                        ? Collections.unmodifiableMap(new LinkedHashMap<>(state))
                        : Collections.emptyMap();
        activities =
                activities != null
                        ? Collections.unmodifiableList(new ArrayList<>(activities))
                        : Collections.emptyList();
    }

    /**
     * Create an empty snapshot for a thread.
     *
     * @param threadId the thread id
     * @return an empty snapshot with no messages, state, activities or pending outcome
     */
    public static AguiThreadSnapshot empty(String threadId) {
        return new AguiThreadSnapshot(threadId, List.of(), Map.of(), List.of(), null, null, 0L);
    }

    /**
     * Return a copy of this snapshot with the trailing interrupt outcome cleared.
     *
     * <p>Used when a new run starts so a previously-unresolved interrupt cannot reappear on
     * reconnect.
     *
     * @return a snapshot with a null pending outcome
     */
    public AguiThreadSnapshot withoutPendingOutcome() {
        return new AguiThreadSnapshot(
                threadId, messages, state, activities, null, lastRunId, updatedAt);
    }
}
