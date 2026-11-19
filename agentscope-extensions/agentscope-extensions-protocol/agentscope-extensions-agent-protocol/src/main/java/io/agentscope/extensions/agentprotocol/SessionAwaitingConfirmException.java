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
package io.agentscope.extensions.agentprotocol;

import java.time.Instant;

/**
 * A new submission named a session whose previous turn is paused for confirmation.
 *
 * <p>Reported as HTTP 409 with the session, the pending task and its deadline in the body. The
 * caller cannot retry its way past this: the paused turn owns the session until it is resumed,
 * cancelled, or times out, because the confirm decisions it waits for belong to the state it paused
 * in. Cancelling the pending task is the way to free the session.
 */
final class SessionAwaitingConfirmException extends IllegalStateException {

    private final String sessionId;
    private final String pendingTaskId;
    private final Instant expiresAt;

    SessionAwaitingConfirmException(String sessionId, String pendingTaskId, Instant expiresAt) {
        super(
                "session "
                        + sessionId
                        + " has a turn awaiting confirmation (task "
                        + pendingTaskId
                        + "); resume or cancel it before submitting again");
        this.sessionId = sessionId;
        this.pendingTaskId = pendingTaskId;
        this.expiresAt = expiresAt;
    }

    String sessionId() {
        return sessionId;
    }

    String pendingTaskId() {
        return pendingTaskId;
    }

    Instant expiresAt() {
        return expiresAt;
    }
}
