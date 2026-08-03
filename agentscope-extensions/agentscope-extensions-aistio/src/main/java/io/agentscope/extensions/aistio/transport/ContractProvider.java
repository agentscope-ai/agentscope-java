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
package io.agentscope.extensions.aistio.transport;

import java.util.List;
import java.util.Map;

/**
 * Backing data for the {@code /agentscope/*} HTTP contract, implemented by the bridge.
 *
 * <p>Every method returns JSON-serializable maps and lists. Throwing {@link NotFoundException} maps
 * to 404 and {@link UnsupportedOperationException} to 501; anything else becomes a 500.
 */
public interface ContractProvider {

    Map<String, Object> info();

    List<Map<String, Object>> sessions();

    Map<String, Object> sessionState(String sessionId);

    Map<String, Object> context(String sessionId);

    Map<String, Object> messages(String sessionId, int offset, int limit);

    List<Map<String, Object>> subagents();

    List<Map<String, Object>> workspaces();

    void compress(String sessionId);

    void terminate(String sessionId);

    /** Aborts the current turn without terminating the session. Capability: {@code session-abort}. */
    void abort(String sessionId);

    /**
     * Session task inventory. Capability: {@code task-query}. Returns a map with a {@code tasks}
     * list.
     */
    Map<String, Object> tasks(String sessionId);

    /**
     * Background subagent tasks (not todolist). Capability: {@code subagent-task-query}.
     */
    default Map<String, Object> subagentTasks(String sessionId) {
        throw new UnsupportedOperationException("subagent-task-query is not supported");
    }

    /** Cancel a background subagent task. Capability: {@code subagent-task-command}. */
    default void cancelSubagentTask(String sessionId, String taskId) {
        throw new UnsupportedOperationException("subagent-task-command is not supported");
    }

    /** Enter/exit plan mode. Body is JSON {@code {"active":true|false}}. Capability: {@code plan-mode}. */
    default void planMode(String sessionId, byte[] body) {
        throw new UnsupportedOperationException("plan-mode is not supported");
    }

    /**
     * Adopts a team session (HTTP fallback for ASDP {@code team_join}). Body is JSON with {@code
     * sessionId} and optional {@code params} TeamContext. Capability: {@code team-coordination}.
     */
    default void teamJoin(byte[] body) {
        throw new UnsupportedOperationException("team-coordination is not supported");
    }

    /**
     * Releases a team session (HTTP fallback for ASDP {@code team_leave}). Body carries the same
     * envelope as {@link #teamJoin(byte[])}. Capability: {@code team-coordination}.
     */
    default void teamLeave(byte[] body) {
        throw new UnsupportedOperationException("team-coordination is not supported");
    }

    /** Current phase string for command success responses. */
    default String sessionPhase(String sessionId) {
        return "running";
    }

    /** Signals that the requested session or resource does not exist on this instance. */
    class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
