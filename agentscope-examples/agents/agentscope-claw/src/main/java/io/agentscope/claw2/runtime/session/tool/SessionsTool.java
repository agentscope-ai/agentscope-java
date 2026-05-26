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
package io.agentscope.claw2.runtime.session.tool;

import io.agentscope.claw2.runtime.session.CommandLane;
import io.agentscope.claw2.runtime.session.HistoryResult;
import io.agentscope.claw2.runtime.session.PendingCompletion;
import io.agentscope.claw2.runtime.session.SendResult;
import io.agentscope.claw2.runtime.session.SessionAgentManager;
import io.agentscope.claw2.runtime.session.SessionConstants;
import io.agentscope.claw2.runtime.session.SessionEntry;
import io.agentscope.claw2.runtime.session.SessionView;
import io.agentscope.claw2.runtime.session.SpawnResult;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRunSpec;
import io.agentscope.harness.agent.tool.TaskTool;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool facade exposing managed subagent session operations to the agent.
 *
 * <p>Provides the following tools:
 *
 * <ul>
 *   <li>{@code sessions_spawn} — spawn an isolated subagent session
 *   <li>{@code sessions_send} — send a message to an existing session by key or label
 *   <li>{@code sessions_list} — list managed sessions with optional filters
 *   <li>{@code sessions_history} — read a session's conversation transcript
 * </ul>
 *
 * <p>Async operations ({@code timeout_seconds=0}) submit work to {@link TaskRepository} and return
 * a {@code task_id} for retrieval via the companion {@link TaskTool}. The same completion may
 * also be queued for the requester as a structured {@link PendingCompletion} (poll with {@code
 * sessions_pending_completions}).
 *
 * <p>Uses {@link SessionAgentManager} for full session lifecycle management.
 */
public class SessionsTool {

    private static final Logger log = LoggerFactory.getLogger(SessionsTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output with task_id='%s' to retrieve the result.\
            """;

    private final SessionAgentManager sessionAgentManager;
    private final TaskRepository taskRepository;
    private final String parentSessionKey;
    private final int parentSpawnDepth;

    /**
     * @param sessionAgentManager full session lifecycle manager
     * @param taskRepository background task store (for async fire-and-forget)
     * @param parentSessionKey session key of the agent that owns this tool instance (null = main)
     * @param parentSpawnDepth spawn depth of the owning agent (0 = main agent)
     */
    public SessionsTool(
            SessionAgentManager sessionAgentManager,
            TaskRepository taskRepository,
            String parentSessionKey,
            int parentSpawnDepth) {
        this.sessionAgentManager =
                Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        this.taskRepository = taskRepository;
        this.parentSessionKey = parentSessionKey;
        this.parentSpawnDepth = parentSpawnDepth;
    }

    // -----------------------------------------------------------------
    //  sessions_spawn
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_spawn",
            description =
                    """
                    Spawn an isolated subagent session for delegated or background work. \
                    mode="run" (default) executes task immediately and returns reply. \
                    mode="session" registers a persistent session for follow-up sends via \
                    sessions_send. timeout_seconds=0 fires and forgets — returns task_id for \
                    task_output and queues a completion record for \
                    sessions_pending_completions. Returns run_id and session_key for follow-up \
                    with sessions_send.\
                    """)
    public String sessionsSpawn(
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description =
                                    """
                                    Initial task or prompt to send to the spawned agent. Omit to \
                                    create a session without running a task.\
                                    """,
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    """
                                    Optional human-readable label for referencing this session via \
                                    sessions_send or sessions_list\
                                    """,
                            required = false)
                    String label,
            @ToolParam(
                            name = "mode",
                            description =
                                    """
                                    "run" (one-shot, default) or "session" (persistent, register \
                                    now and send later via sessions_send)\
                                    """,
                            required = false)
                    String mode,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for the task result. 0=fire-and-forget, \
                                    returns task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        boolean sessionMode = "session".equalsIgnoreCase(mode);
        boolean hasTask = task != null && !task.isBlank();

        if (sessionMode && canonLabel != null) {
            var existingKey = sessionAgentManager.resolveSessionKey(canonLabel);
            if (existingKey.isPresent()) {
                var existingEntry = sessionAgentManager.getSession(existingKey.get());
                if (existingEntry.isPresent()) {
                    SessionEntry e = existingEntry.get();
                    String resumeInfo = formatExistingSessionInfo(e);
                    if (!hasTask) {
                        return resumeInfo + "\nstatus: resumed";
                    }
                    long tMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
                    if (tMs == 0) {
                        String taskId = "task_" + UUID.randomUUID();
                        final String capturedTask = task;
                        taskRepository.putTask(
                                RuntimeContext.empty(),
                                taskId,
                                e.agentId(),
                                parentSessionScope(),
                                new TaskRunSpec.LocalTaskRunSpec(
                                        () -> {
                                            SendResult r =
                                                    sessionAgentManager.execute(
                                                            e.sessionKey(),
                                                            capturedTask,
                                                            0,
                                                            true,
                                                            CommandLane.SUBAGENT);
                                            return "ok".equals(r.status())
                                                    ? r.reply()
                                                    : "Error: " + r.error();
                                        }));
                        return resumeInfo
                                + "\n"
                                + String.format(BG_RESULT_TEMPLATE, taskId, taskId);
                    }
                    SendResult result =
                            sessionAgentManager.execute(
                                    e.sessionKey(), task, tMs, false, CommandLane.SUBAGENT);
                    if ("error".equals(result.status())) {
                        return resumeInfo + "\nstatus: error\nerror: " + result.error();
                    }
                    return resumeInfo + "\nstatus: ok\nreply:\n" + result.reply();
                }
            }
        }

        SpawnResult reg =
                sessionAgentManager.registerSession(
                        agentId, canonLabel, parentSessionKey, parentSpawnDepth);

        if ("error".equals(reg.status())) {
            return "Error: " + reg.error();
        }

        String spawnedInfo = formatSpawnInfo(reg);

        if (sessionMode || !hasTask) {
            return spawnedInfo + "\nstatus: accepted";
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            final String spawnedSessionKey = reg.sessionKey();
            taskRepository.putTask(
                    RuntimeContext.empty(),
                    taskId,
                    agentId,
                    parentSessionScope(),
                    new TaskRunSpec.LocalTaskRunSpec(
                            () -> {
                                SendResult r =
                                        sessionAgentManager.execute(
                                                spawnedSessionKey,
                                                capturedTask,
                                                0,
                                                true,
                                                CommandLane.SUBAGENT);
                                return "ok".equals(r.status()) ? r.reply() : "Error: " + r.error();
                            }));
            return spawnedInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId);
        }

        SendResult result =
                sessionAgentManager.execute(
                        reg.sessionKey(), task, timeoutMs, false, CommandLane.SUBAGENT);
        if ("error".equals(result.status())) {
            return spawnedInfo + "\nstatus: error\nerror: " + result.error();
        }
        return spawnedInfo + "\nstatus: ok\nreply:\n" + result.reply();
    }

    // -----------------------------------------------------------------
    //  sessions_send
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_send",
            description =
                    """
                    Send a message to an existing managed session by session_key or label. \
                    timeout_seconds=0 fires and forgets — returns task_id for task_output.\
                    """)
    public String sessionsSend(
            @ToolParam(
                            name = "session_key",
                            description =
                                    """
                                    Session key returned by sessions_spawn. Mutually exclusive \
                                    with label.\
                                    """,
                            required = false)
                    String sessionKey,
            @ToolParam(
                            name = "label",
                            description =
                                    """
                                    Session label assigned at spawn time. Mutually exclusive with \
                                    session_key.\
                                    """,
                            required = false)
                    String label,
            @ToolParam(
                            name = "message",
                            description = "Message or follow-up task to send to the session")
                    String message,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for a reply. 0=fire-and-forget, returns \
                                    task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        boolean hasKey = sessionKey != null && !sessionKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return "Error: Provide either session_key or label, not both.";
        }
        if (!hasKey && !hasLabel) {
            return "Error: Either session_key or label is required.";
        }
        if (message == null || message.isBlank()) {
            return "Error: message is required";
        }

        String target = hasKey ? sessionKey.trim() : label.trim();
        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            String resolvedAgentId =
                    sessionAgentManager
                            .getSession(
                                    sessionAgentManager.resolveSessionKey(target).orElse(target))
                            .map(e -> e.agentId())
                            .orElse("unknown");
            final String capturedTarget = target;
            taskRepository.putTask(
                    RuntimeContext.empty(),
                    taskId,
                    resolvedAgentId,
                    parentSessionScope(),
                    new TaskRunSpec.LocalTaskRunSpec(
                            () -> {
                                SendResult r =
                                        sessionAgentManager.execute(
                                                capturedTarget,
                                                message,
                                                0,
                                                true,
                                                CommandLane.SUBAGENT);
                                return "ok".equals(r.status()) ? r.reply() : "Error: " + r.error();
                            }));
            return String.format(BG_RESULT_TEMPLATE, taskId, taskId);
        }

        SendResult result =
                sessionAgentManager.execute(
                        target, message, timeoutMs, false, CommandLane.SUBAGENT);
        return switch (result.status()) {
            case "ok" ->
                    "session_key: "
                            + result.sessionKey()
                            + "\nstatus: ok\nreply:\n"
                            + result.reply();
            case "error" -> "Error: " + result.error();
            default -> "status: " + result.status();
        };
    }

    // -----------------------------------------------------------------
    //  sessions_list
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_list",
            description =
                    """
                    List managed subagent sessions with optional filters. Returns session_key, \
                    agent_id, label, kind, spawn_depth, session_file_path, and last_activity_ms \
                    for each session.\
                    """)
    public String sessionsList(
            @ToolParam(
                            name = "kinds",
                            description =
                                    """
                                    Comma-separated session kinds to include (subagent, main). \
                                    Default: all.\
                                    """,
                            required = false)
                    String kinds,
            @ToolParam(
                            name = "limit",
                            description = "Maximum number of sessions to return",
                            required = false)
                    Integer limit,
            @ToolParam(
                            name = "active_minutes",
                            description = "Only include sessions active within the last N minutes",
                            required = false)
                    Integer activeMinutes) {

        Set<String> kindFilter = parseKinds(kinds);
        int effectiveLimit = limit != null && limit > 0 ? limit : 0;
        int effectiveActive = activeMinutes != null && activeMinutes > 0 ? activeMinutes : 0;

        List<SessionView> sessions =
                sessionAgentManager.list(kindFilter, effectiveLimit, effectiveActive);

        if (sessions.isEmpty()) {
            return "No managed sessions.";
        }

        StringBuilder sb =
                new StringBuilder("Managed sessions (").append(sessions.size()).append("):\n");
        for (SessionView v : sessions) {
            sb.append("- session_key: ").append(v.sessionKey()).append("\n");
            sb.append("  agent_id: ").append(v.agentId()).append("\n");
            if (v.label() != null) {
                sb.append("  label: ").append(v.label()).append("\n");
            }
            sb.append("  kind: ").append(v.kind()).append("\n");
            sb.append("  spawn_depth: ").append(v.spawnDepth()).append("\n");
            if (v.spawnedBy() != null) {
                sb.append("  spawned_by: ").append(v.spawnedBy()).append("\n");
            }
            if (v.spawnRunId() != null) {
                sb.append("  run_id: ").append(v.spawnRunId()).append("\n");
            }
            List<String> ch = sessionAgentManager.listChildren(v.sessionKey());
            if (!ch.isEmpty()) {
                sb.append("  child_session_keys: ").append(String.join(", ", ch)).append("\n");
            }
            sb.append("  session_file_path: ").append(v.sessionFilePath()).append("\n");
            sb.append("  last_activity_ms: ").append(v.lastActivityMs()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  sessions_history
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_history",
            description =
                    """
                    Read the conversation transcript of a managed session. Transcripts are \
                    written when messages are offloaded from memory. Returns the \
                    session_file_path and transcript content.\
                    """)
    public String sessionsHistory(
            @ToolParam(
                            name = "session_key",
                            description = "Session key or label of the target session")
                    String sessionKey,
            @ToolParam(
                            name = "limit",
                            description = "Max transcript lines to return from the end (0 = all)",
                            required = false)
                    Integer limit) {

        if (sessionKey == null || sessionKey.isBlank()) {
            return "Error: session_key is required";
        }

        int effectiveLimit = limit != null && limit > 0 ? limit : 0;
        HistoryResult result = sessionAgentManager.history(sessionKey.trim(), effectiveLimit);

        if (result.error() != null) {
            return "Error: " + result.error();
        }
        return "session_key: "
                + result.sessionKey()
                + "\nsession_file_path: "
                + result.sessionFilePath()
                + "\n\n"
                + result.content();
    }

    // -----------------------------------------------------------------
    //  sessions_pending_completions
    // -----------------------------------------------------------------

    @Tool(
            name = "sessions_pending_completions",
            description =
                    """
                    Drain structured subagent completion events for this requester session. \
                    Use requester_session_key matching your session_key when you spawned \
                    children, or omit for the top-level harness. Each entry includes \
                    announce_text for merging into your next reply.\
                    """)
    public String sessionsPendingCompletions(
            @ToolParam(
                            name = "requester_session_key",
                            description =
                                    """
                                    Session key of the requester that spawned subagents. Omit to \
                                    use the default root requester (main harness).\
                                    """,
                            required = false)
                    String requesterSessionKey,
            @ToolParam(
                            name = "limit",
                            description = "Max events to drain (default 10)",
                            required = false)
                    Integer limit) {

        String rk =
                requesterSessionKey != null && !requesterSessionKey.isBlank()
                        ? requesterSessionKey.trim()
                        : SessionConstants.resolveRequesterKey(parentSessionKey);
        int lim = limit != null && limit > 0 ? limit : 10;
        List<PendingCompletion> pending = sessionAgentManager.drainPendingCompletions(rk, lim);
        if (pending.isEmpty()) {
            return "No pending completion events for requester_session_key=" + rk + ".";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Pending completions (")
                .append(pending.size())
                .append(") for ")
                .append(rk)
                .append(":\n\n");
        for (PendingCompletion p : pending) {
            sb.append("---\n");
            sb.append("run_id: ").append(p.runId()).append("\n");
            sb.append("child_session_key: ").append(p.childSessionKey()).append("\n");
            sb.append("status: ").append(p.status()).append("\n");
            if (p.error() != null) {
                sb.append("error: ").append(p.error()).append("\n");
            }
            sb.append("\n").append(p.announceText()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private String parentSessionScope() {
        if (parentSessionKey == null || parentSessionKey.isBlank()) {
            return null;
        }
        return sessionAgentManager
                .getSession(parentSessionKey)
                .map(e -> e.sessionId())
                .orElse(parentSessionKey);
    }

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    private static String formatSpawnInfo(SpawnResult reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("run_id: ").append(reg.runId()).append("\n");
        sb.append("session_key: ").append(reg.sessionKey()).append("\n");
        sb.append("agent_id: ").append(reg.agentId()).append("\n");
        sb.append("session_id: ").append(reg.sessionId()).append("\n");
        sb.append("session_file_path: ").append(reg.sessionFilePath());
        return sb.toString();
    }

    private static String formatExistingSessionInfo(SessionEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("session_key: ").append(entry.sessionKey()).append("\n");
        sb.append("agent_id: ").append(entry.agentId()).append("\n");
        sb.append("session_id: ").append(entry.sessionId()).append("\n");
        sb.append("label: ").append(entry.label()).append("\n");
        sb.append("session_file_path: ").append(entry.sessionFilePath()).append("\n");
        sb.append("note: existing session reused (label match)");
        return sb.toString();
    }

    private static Set<String> parseKinds(String kinds) {
        if (kinds == null || kinds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(kinds.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
