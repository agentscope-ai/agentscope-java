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
import java.util.List;
import java.util.Map;

/**
 * Replays a stored {@link AguiThreadSnapshot} as a read-only sequence of AG-UI frames.
 *
 * <p>Frames are emitted in this exact order:
 *
 * <pre>{@code
 * RUN_STARTED(threadId, runId)
 * MESSAGES_SNAPSHOT(messages)
 * STATE_SNAPSHOT(state)                     // omitted when state is empty
 * ACTIVITY_SNAPSHOT(...) per ActivityFrame  // omitted when none
 * RUN_FINISHED(result=null, outcome=pendingOutcome)
 * }</pre>
 *
 * <p>A null {@code pendingOutcome} serializes as a plain successful run — exactly the shape the
 * former CopilotKit empty-handshake forced via post-processing. An empty or missing snapshot
 * produces the minimal three-frame handshake ({@code RUN_STARTED} → {@code MESSAGES_SNAPSHOT([])}
 * → {@code RUN_FINISHED}).
 *
 * <p>Hydration is strictly read-only: it never mutates the snapshot store, the agent state store,
 * or the resume coordinator.
 */
public final class AguiSnapshotHydrator {

    /** Create a new stateless hydrator. */
    public AguiSnapshotHydrator() {}

    /**
     * Build the hydrate frame sequence for a snapshot.
     *
     * @param snapshot the stored snapshot, or null when the thread has no history
     * @param threadId the thread id for the emitted frames
     * @param runId the run id for the emitted frames
     * @return the ordered AG-UI frames
     */
    public List<AguiEvent> hydrate(AguiThreadSnapshot snapshot, String threadId, String runId) {
        List<AguiEvent> events = new ArrayList<>();
        events.add(new AguiEvent.RunStarted(threadId, runId));

        List<AguiMessage> messages =
                snapshot != null && snapshot.messages() != null ? snapshot.messages() : List.of();
        events.add(new AguiEvent.MessagesSnapshot(threadId, runId, messages));

        Map<String, Object> state = snapshot != null ? snapshot.state() : Map.of();
        if (state != null && !state.isEmpty()) {
            events.add(new AguiEvent.StateSnapshot(threadId, runId, state));
        }

        if (snapshot != null && snapshot.activities() != null) {
            for (AguiThreadSnapshot.ActivityFrame frame : snapshot.activities()) {
                events.add(
                        new AguiEvent.ActivitySnapshot(
                                threadId,
                                runId,
                                frame.messageId(),
                                frame.activityType(),
                                frame.content(),
                                true));
            }
        }

        AguiEvent.RunFinishedOutcome outcome = snapshot != null ? snapshot.pendingOutcome() : null;
        events.add(new AguiEvent.RunFinished(threadId, runId, null, outcome));
        return events;
    }
}
