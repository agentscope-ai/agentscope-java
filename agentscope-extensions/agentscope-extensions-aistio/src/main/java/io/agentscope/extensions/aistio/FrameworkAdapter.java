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
package io.agentscope.extensions.aistio;

import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.Inventory;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import reactor.core.publisher.Mono;

/**
 * One adapter per agent framework, presenting a uniform view of that framework's sessions to {@link
 * SessionBridge}. The Java counterpart of the Python SDK's {@code FrameworkAdapter}.
 *
 * <p><b>Bypass principle:</b> interception copies data, it never replaces or blocks the framework's
 * own path, and a failed report is swallowed rather than propagated. An instrumented agent must
 * behave exactly as an uninstrumented one.
 *
 * <p>Optional operations default to failing with {@link UnsupportedOperationException}. Declare only
 * what you actually implement in {@link #capabilities()} — the control plane gates its UI on that
 * list, so over-declaring produces buttons that fail.
 */
public interface FrameworkAdapter {

    String CAP_CONTEXT_QUERY = "context-query";
    String CAP_MESSAGE_QUERY = "message-query";
    String CAP_SUBAGENT_INVENTORY = "subagent-inventory";
    String CAP_WORKSPACE_INVENTORY = "workspace-inventory";
    String CAP_SESSION_COMMAND = "session-command";
    String CAP_SESSION_ABORT = "session-abort";
    String CAP_TASK_QUERY = "task-query";
    String CAP_SUBAGENT_TASK_QUERY = "subagent-task-query";
    String CAP_SUBAGENT_TASK_COMMAND = "subagent-task-command";
    String CAP_PLAN_MODE = "plan-mode";
    String CAP_TEAM_COORDINATION = "team-coordination";

    String COMMAND_COMPRESS = "compress";
    String COMMAND_TERMINATE = "terminate";
    String COMMAND_ABORT = "abort";
    String COMMAND_TEAM_JOIN = "team_join";
    String COMMAND_TEAM_LEAVE = "team_leave";

    /** Framework identifier reported on every snapshot, e.g. {@code agentscope-java}. */
    String frameworkName();

    /** Framework version, or an empty string when it cannot be determined. */
    default String frameworkVersion() {
        return "";
    }

    /** Whether this adapter can instrument {@code target}. Must be side-effect free. */
    boolean canHandle(Object target);

    /**
     * Attaches to the framework object. Session activity is published through {@code emit}, which
     * must never be allowed to break the framework's main path.
     */
    void attach(Object target, Consumer<SessionEvent> emit);

    /** Removes the interception and restores the framework object. */
    void detach();

    /**
     * Called once the adapter is mounted, giving it a handle for metadata the event stream cannot
     * carry — system prompt, tool list, context window — via {@link
     * SessionBridge#describeSession}.
     */
    default void onBridgeAttached(SessionBridge bridge) {
        // Adapters that only emit events need nothing here.
    }

    /** Level 4: the context that the session's next model call will actually see. */
    Mono<ContextSnapshot> extractContext(String sessionId);

    /** Level 3: full history including messages already compacted away. */
    default Mono<MessagePage> listMessages(String sessionId, int offset, int limit) {
        return Mono.error(unsupported("message-query"));
    }

    default Mono<List<Inventory.SubagentInfo>> listSubagents() {
        return Mono.error(unsupported("subagent-inventory"));
    }

    default Mono<List<Inventory.WorkspaceInfo>> listWorkspaces() {
        return Mono.error(unsupported("workspace-inventory"));
    }

    /**
     * Session task list for {@code GET .../tasks}. Each map follows the frozen Task shape: {@code
     * id}, {@code subject}, {@code state}, {@code owner}, {@code blockedBy}, {@code updatedAt},
     * {@code frameworkMeta}.
     */
    default Mono<List<Map<String, Object>>> listTasks(String sessionId) {
        return Mono.error(unsupported("task-query"));
    }

    /**
     * Background subagent tasks for {@code GET .../subagent-tasks} (not todolist). Each map is
     * framework-shaped; typical keys: {@code id}, {@code status}, {@code subject}/{@code label}.
     */
    default Mono<List<Map<String, Object>>> listSubagentTasks(String sessionId) {
        return Mono.error(unsupported("subagent-task-query"));
    }

    /** Cancel one background subagent task. Capability: {@code subagent-task-command}. */
    default Mono<Void> cancelSubagentTask(String sessionId, String taskId) {
        return Mono.error(unsupported("subagent-task-command"));
    }

    /**
     * Enter or exit plan mode for the session. Capability: {@code plan-mode}. {@code params} is a
     * JSON object with {@code "active": true|false}.
     */
    default Mono<Void> setPlanMode(String sessionId, byte[] params) {
        return Mono.error(unsupported("plan-mode"));
    }

    /** Effective Definition snapshot for {@code GET /agentscope/info} → {@code agentConfig}. */
    default Map<String, Object> buildAgentConfig() {
        return Map.of();
    }

    /**
     * Executes {@link #COMMAND_COMPRESS}, {@link #COMMAND_TERMINATE}, or {@link #COMMAND_ABORT}
     * against the session.
     */
    default Mono<Void> handleCommand(String sessionId, String command, byte[] params) {
        return Mono.error(unsupported("session-command"));
    }

    /**
     * Capabilities contributed by this adapter. Push-side capabilities ({@code session-reporting},
     * {@code event-reporting}, {@code context-reporting}) are added by the bridge.
     */
    default Set<String> capabilities() {
        return Set.of(CAP_CONTEXT_QUERY);
    }

    private static UnsupportedOperationException unsupported(String capability) {
        return new UnsupportedOperationException(capability + " is not supported by this adapter");
    }
}
