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

/**
 * The target session already has a live run, so the submission cannot start.
 *
 * <p>Reported as HTTP 409 with the session in the body: the caller must wait for the running turn
 * instead of retrying, and it is the session — not a task — that is busy.
 */
final class SessionBusyException extends IllegalStateException {

    private final String sessionId;

    SessionBusyException(String sessionId, String taskId) {
        super("session " + sessionId + " is still running; task " + taskId + " was not started");
        this.sessionId = sessionId;
    }

    String sessionId() {
        return sessionId;
    }
}
