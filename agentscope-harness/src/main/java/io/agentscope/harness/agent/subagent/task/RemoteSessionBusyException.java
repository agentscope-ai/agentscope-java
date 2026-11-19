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
 * The remote server refused a submission because the session it targets already has a live run
 * ({@code POST /tasks} answered HTTP 409).
 *
 * <p>Nothing was started: the caller's message was not appended to the conversation, and the task id
 * it allocated does not exist remotely. Callers must not treat this as a failed turn of the
 * conversation — the previous turn is still executing and has to reach a terminal state (via {@code
 * task_output} or {@code wait_async_results}) before the next one can be sent.
 *
 * <p>The busy turn's task id is reported when the server includes it, because that is what the
 * caller needs to wait on; it is {@code null} for servers that only report the session.
 */
public final class RemoteSessionBusyException extends RuntimeException {

    private final String sessionId;
    private final String taskId;

    public RemoteSessionBusyException(String sessionId, String taskId, String detail) {
        super("session " + (sessionId != null ? sessionId : "?") + " is still running: " + detail);
        this.sessionId = sessionId;
        this.taskId = taskId;
    }

    /** Session whose previous turn is still running. */
    public String sessionId() {
        return sessionId;
    }

    /** Task of that running turn, or {@code null} when the server did not report it. */
    public String taskId() {
        return taskId;
    }
}
