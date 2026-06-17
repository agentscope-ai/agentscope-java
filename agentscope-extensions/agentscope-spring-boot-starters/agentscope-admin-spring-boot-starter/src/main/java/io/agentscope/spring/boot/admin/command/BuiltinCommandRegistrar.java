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
package io.agentscope.spring.boot.admin.command;

import io.agentscope.spring.boot.admin.properties.AdminProperties;
import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Registers the Phase-1 built-in {@link AdminCommand}s into the {@link AdminCommandRegistry}.
 *
 * <p>This class only declares metadata. The actual REST routes live in
 * {@code SessionAdminController} and the Actuator endpoints; this registrar exists so callers can
 * discover the full catalog via {@code GET /actuator/agentscope-commands} without having to crawl
 * Spring mappings at runtime.
 */
public final class BuiltinCommandRegistrar {

    private final AdminCommandRegistry registry;
    private final AdminProperties properties;

    public BuiltinCommandRegistrar(AdminCommandRegistry registry, AdminProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    public void registerBuiltins() {
        String base = properties.getBasePath();

        // ---- Data plane (per-session, REST) ----
        registry.register(
                new AdminCommand(
                        "session.list",
                        "List sessions",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions",
                        List.of(),
                        false,
                        true,
                        "List known session keys from the configured AgentStateStore store"));
        registry.register(
                new AdminCommand(
                        "session.messages",
                        "List session messages",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/messages",
                        List.of("timeline"),
                        false,
                        true,
                        "Return the message history of a single session"));
        registry.register(
                new AdminCommand(
                        "session.state",
                        "Dump session state",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/state",
                        List.of("state-dump"),
                        false,
                        true,
                        "Dump the full AgentState JSON for a session"));
        registry.register(
                new AdminCommand(
                        "session.export",
                        "Export session transcript",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}:export",
                        List.of("copy"),
                        false,
                        true,
                        "Render the session as a Markdown transcript"));
        registry.register(
                new AdminCommand(
                        "session.compact",
                        "Compact session",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:compact",
                        List.of("summarize"),
                        true,
                        false,
                        "Summarize the conversation context and truncate older messages"));
        registry.register(
                new AdminCommand(
                        "session.abort",
                        "Abort session",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:abort",
                        List.of("interrupt"),
                        true,
                        true,
                        "Interrupt in-flight reasoning for a session"));
        registry.register(
                new AdminCommand(
                        "session.undo",
                        "Undo last mutation",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:undo",
                        List.of(),
                        true,
                        false,
                        "Restore the AgentState snapshot taken before the most recent"
                                + " compact/undo"));
        registry.register(
                new AdminCommand(
                        "session.redo",
                        "Redo a previously undone mutation",
                        "AgentStateStore",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:redo",
                        List.of(),
                        true,
                        false,
                        "Reverse a previous /undo for this session"));

        // ---- Plan mode ----
        registry.register(
                new AdminCommand(
                        "session.plan",
                        "View plan-mode state",
                        "Plan",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/plan",
                        List.of(),
                        false,
                        true,
                        "Return whether plan mode is active, the current plan file, and middleware"
                                + " presence"));
        registry.register(
                new AdminCommand(
                        "session.enter_plan_mode",
                        "Enter plan mode",
                        "Plan",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:enter-plan-mode",
                        List.of("plan-on"),
                        true,
                        true,
                        "Switch the agent into read-only plan mode (persisted via AgentState)"));
        registry.register(
                new AdminCommand(
                        "session.exit_plan_mode",
                        "Exit plan mode",
                        "Plan",
                        CommandPlane.DATA,
                        "POST",
                        base + "/sessions/{sessionId}:exit-plan-mode",
                        List.of("plan-off"),
                        true,
                        true,
                        "Leave plan mode; mutating tools become permitted again"));

        // ---- Agent task list (in-AgentState TodoWrite-style records) ----
        registry.register(
                new AdminCommand(
                        "session.agent_tasks",
                        "List agent tasks",
                        "Tasks",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/tasks",
                        List.of("todos"),
                        false,
                        true,
                        "Read AgentState.tasksContext: subject/state/owner/blocks/blockedBy for"
                                + " each task"));

        // ---- Subagent background tasks (TaskRepository-managed) ----
        registry.register(
                new AdminCommand(
                        "subagent.task.list",
                        "List subagent background tasks",
                        "Subagent",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/subagent-tasks",
                        List.of(),
                        false,
                        true,
                        "List BackgroundTasks dispatched to subagents (optional"
                                + " ?status=RUNNING|COMPLETED|...)"));
        registry.register(
                new AdminCommand(
                        "subagent.task.get",
                        "Get subagent background task",
                        "Subagent",
                        CommandPlane.DATA,
                        "GET",
                        base + "/sessions/{sessionId}/subagent-tasks/{taskId}",
                        List.of(),
                        false,
                        true,
                        "Fetch a single BackgroundTask: status, result preview, error"));
        registry.register(
                new AdminCommand(
                        "subagent.task.cancel",
                        "Cancel subagent background task",
                        "Subagent",
                        CommandPlane.DATA,
                        "DELETE",
                        base + "/sessions/{sessionId}/subagent-tasks/{taskId}",
                        List.of("interrupt-subagent"),
                        true,
                        true,
                        "Request cancellation of a subagent BackgroundTask via"
                                + " TaskRepository.cancelTask"));

        // ---- Control plane (Actuator) ----
        registry.register(
                new AdminCommand(
                        "system.status",
                        "View status",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-status",
                        List.of(),
                        false,
                        true,
                        "Process-wide status: shutdown state, active requests, registered agents"));
        registry.register(
                new AdminCommand(
                        "system.agents",
                        "List agents",
                        "Agent",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-agents",
                        List.of(),
                        false,
                        true,
                        "Enumerate Agent beans known to the process"));
        registry.register(
                new AdminCommand(
                        "system.tools",
                        "List tools",
                        "Agent",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-tools",
                        List.of(),
                        false,
                        true,
                        "Enumerate Toolkit beans and their registered tools"));
        registry.register(
                new AdminCommand(
                        "system.models",
                        "List models",
                        "Agent",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-models",
                        List.of(),
                        false,
                        true,
                        "Enumerate Model beans by name"));
        registry.register(
                new AdminCommand(
                        "system.commands",
                        "List admin commands",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-commands",
                        List.of("help"),
                        false,
                        true,
                        "Machine-readable catalog of all admin commands in this process"));
        registry.register(
                new AdminCommand(
                        "system.doctor",
                        "Run self-check",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-doctor",
                        List.of(),
                        false,
                        true,
                        "Self-check: JVM, sessions writability, write-toggle status, etc."));
        registry.register(
                new AdminCommand(
                        "system.usage",
                        "View token usage",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-usage",
                        List.of("cost"),
                        false,
                        true,
                        "Cumulative token usage by agent and by model since process start"));
        registry.register(
                new AdminCommand(
                        "system.permissions",
                        "List permissions",
                        "System",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-permissions",
                        List.of(),
                        false,
                        true,
                        "Per-agent allow/deny/ask permission rules"));
        registry.register(
                new AdminCommand(
                        "system.drain",
                        "Drain process",
                        "System",
                        CommandPlane.CONTROL,
                        "POST",
                        "/actuator/agentscope-drain",
                        List.of(),
                        true,
                        true,
                        "Stop accepting new requests; wait for in-flight reasoning to finish"));
        registry.register(
                new AdminCommand(
                        "system.shutdown",
                        "Shutdown process",
                        "System",
                        CommandPlane.CONTROL,
                        "POST",
                        "/actuator/agentscope-shutdown",
                        List.of("exit"),
                        true,
                        false,
                        "Drain and close the application context (JVM exit handled by container)"));
        registry.register(
                new AdminCommand(
                        "system.subagents",
                        "List subagents",
                        "Subagent",
                        CommandPlane.CONTROL,
                        "GET",
                        "/actuator/agentscope-subagents",
                        List.of(),
                        false,
                        true,
                        "Enumerate subagents declared in the process (via Spring"
                                + " SubagentEntry/Declaration beans)"));
    }
}
