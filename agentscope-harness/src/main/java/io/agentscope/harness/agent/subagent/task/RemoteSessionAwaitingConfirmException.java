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
package io.agentscope.harness.agent.subagent.task;

/**
 * The remote server refused a submission because the session it targets has a turn paused for
 * confirmation ({@code POST /tasks} answered HTTP 409 with {@code status: awaiting_confirm}).
 *
 * <p>Nothing was started: the caller's message was not appended to the conversation, and the task id
 * it allocated does not exist remotely. Unlike {@link RemoteSessionBusyException}, the paused turn
 * will not finish on its own — waiting on it with {@code task_output} does not help. The caller has
 * to resume it with confirmation decisions or cancel it before another turn can be sent.
 *
 * <p>The pending task id and the confirmation deadline are reported when the server includes them,
 * so the caller can name the turn that needs a decision.
 */
public final class RemoteSessionAwaitingConfirmException extends RuntimeException {

    private final String sessionId;
    private final String pendingTaskId;
    private final String expiresAt;

    public RemoteSessionAwaitingConfirmException(
            String sessionId, String pendingTaskId, String expiresAt, String detail) {
        super(
                "session "
                        + (sessionId != null ? sessionId : "?")
                        + " has a turn awaiting confirmation: "
                        + detail);
        this.sessionId = sessionId;
        this.pendingTaskId = pendingTaskId;
        this.expiresAt = expiresAt;
    }

    /** Session whose previous turn is paused for confirmation. */
    public String sessionId() {
        return sessionId;
    }

    /** Task of that paused turn, or {@code null} when the server did not report it. */
    public String pendingTaskId() {
        return pendingTaskId;
    }

    /** Confirmation deadline as an ISO-8601 string, or {@code null} when not reported. */
    public String expiresAt() {
        return expiresAt;
    }
}
