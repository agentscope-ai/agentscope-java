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
package io.agentscope.builder.web.persistence.jpa;

/** Atomic event writes participating in the caller's transaction. */
public interface SessionEventAppendOperations {
    /** The persisted event and whether this call inserted it. */
    record AppendResult(SessionEventEntity event, boolean inserted) {}

    /** Allocates a sequence and inserts the event, rejecting an already claimed event ID. */
    SessionEventEntity appendEvent(SessionEventEntity event);

    /**
     * Allocates a sequence for a new event or returns the committed owner of its event ID without
     * advancing the sequence. The caller must validate the returned event's session, type and
     * payload before accepting a replay.
     */
    AppendResult appendIdempotentEvent(SessionEventEntity event);

    /** Clears the transcript and resets its sequence while retaining the stable session lock. */
    void deleteBySessionId(String sessionId);
}
