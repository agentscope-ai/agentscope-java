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

import java.util.Optional;

/**
 * Presentation-state store for AG-UI threads.
 *
 * <p>Holds the <b>derived</b> presentation state (materialized messages / state / activity) so a
 * reconnecting client can rebuild the visible conversation without re-running the agent. This is
 * presentation-only data: it is safe to lose, and it is <b>not</b> a source of truth for
 * human-in-the-loop interrupts. Authoritative agent state and the live HITL contract are owned by
 * the agent state store and the resume coordinator; hydrate is strictly read-only and never
 * mutates either.
 *
 * <p>Because the store only ever retains the <em>trailing</em> unresolved interrupt, a resolved
 * historical interrupt cannot be revived on reconnect — that failure mode is removed at the data
 * model rather than filtered after the fact.
 */
public interface AguiSnapshotStore {

    /**
     * Persist a materialized snapshot for a thread.
     *
     * @param snapshot the snapshot to store
     */
    void save(AguiThreadSnapshot snapshot);

    /**
     * Look up the snapshot for a thread.
     *
     * @param threadId the thread id
     * @return the snapshot, or empty if none is stored
     */
    Optional<AguiThreadSnapshot> find(String threadId);

    /**
     * Delete the snapshot for a thread.
     *
     * @param threadId the thread id
     */
    void delete(String threadId);

    /**
     * Drop the trailing interrupt outcome for a thread, if any.
     *
     * <p>Called when a new run starts so a previously-unresolved interrupt cannot reappear on
     * reconnect. Implementations that retain only the trailing interrupt can satisfy this with a
     * read-modify-write via {@link AguiThreadSnapshot#withoutPendingOutcome()}.
     *
     * @param threadId the thread id
     */
    default void clearPendingInterrupts(String threadId) {
        find(threadId)
                .filter(snapshot -> snapshot.pendingOutcome() != null)
                .ifPresent(snapshot -> save(snapshot.withoutPendingOutcome()));
    }
}
