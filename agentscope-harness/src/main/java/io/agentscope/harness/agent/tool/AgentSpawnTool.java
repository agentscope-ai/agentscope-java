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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple subagent tool for agent-internal use. Much lighter than {@code SessionsTool}:
 *
 * <ul>
 *   <li>{@code agent_spawn} — spawn a subagent, run task, return result (sync or async)
 *   <li>{@code agent_send} — send follow-up message to a previously spawned subagent
 *   <li>{@code agent_list} — list active subagents
 * </ul>
 *
 * <p>No sessions, no lanes, no run registry, no announce dispatch. Just "create agent, invoke,
 * return result". Uses {@link DefaultAgentManager} for agent creation and invocation only.
 */
public class AgentSpawnTool {

    private static final Logger log = LoggerFactory.getLogger(AgentSpawnTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 600;
    private static final int MAX_SPAWN_DEPTH = 3;

    private static final String BG_RESULT_TEMPLATE =
            """
            status: accepted
            task_id: %s
            Use task_output(task_id='%s') to retrieve the result, \
            task_cancel(task_id='%s') to cancel, or task_list() to see all tasks.\
            """;

    private final DefaultAgentManager agentManager;
    private final TaskRepository taskRepository;
    private final int parentSpawnDepth;

    private record SpawnedAgent(
            String key, String agentId, String sessionId, String label, Agent agent, int depth) {}

    private final ConcurrentHashMap<String, SpawnedAgent> agentsByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> labelToKey = new ConcurrentHashMap<>();

    public AgentSpawnTool(
            DefaultAgentManager agentManager, TaskRepository taskRepository, int parentSpawnDepth) {
        this.agentManager = Objects.requireNonNull(agentManager, "agentManager");
        this.taskRepository = taskRepository;
        this.parentSpawnDepth = parentSpawnDepth;
    }

    @Tool(
            name = "agent_spawn",
            description =
                    """
                    Spawn an isolated subagent for delegated or background work. \
                    Every response starts with three lines: agent_key (pass this verbatim to \
                    agent_send as agent_key), agent_id (the subagent type name), and session_id \
                    (internal; do not use as agent_key). Sync mode returns the reply below that; \
                    async (timeout_seconds=0) adds task_id for task_output — task_id is NOT agent_key.\
                    """)
    public String agentSpawn(
            @ToolParam(name = "agent_id", description = "Subagent identifier to instantiate")
                    String agentId,
            @ToolParam(
                            name = "task",
                            description = "Task or prompt to send to the spawned agent",
                            required = false)
                    String task,
            @ToolParam(
                            name = "label",
                            description =
                                    "Optional human-readable label for referencing via agent_send",
                            required = false)
                    String label,
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    """
                                    Max seconds to wait for the task result. 0=fire-and-forget, \
                                    returns task_id. Default: 30. Max: 600.\
                                    """,
                            required = false)
                    Integer timeoutSeconds) {

        int nextDepth = parentSpawnDepth + 1;
        if (nextDepth > MAX_SPAWN_DEPTH) {
            return "Error: Maximum spawn depth exceeded (max=" + MAX_SPAWN_DEPTH + ")";
        }
        if (!agentManager.hasAgent(agentId)) {
            return "Error: Unknown agent_id: " + agentId;
        }

        String canonLabel = label != null && !label.isBlank() ? label.trim() : null;
        if (canonLabel != null && labelToKey.containsKey(canonLabel.toLowerCase())) {
            return "Error: Label already in use: " + canonLabel;
        }

        Agent agent = agentManager.createAgent(agentId);
        String key = "agent:" + agentId + ":" + UUID.randomUUID();
        String sessionId = "sub-" + UUID.randomUUID();

        SpawnedAgent spawned =
                new SpawnedAgent(key, agentId, sessionId, canonLabel, agent, nextDepth);
        agentsByKey.put(key, spawned);
        if (canonLabel != null) {
            labelToKey.put(canonLabel.toLowerCase(), key);
        }

        String spawnInfo = formatSpawnInfo(key, agentId, sessionId);
        boolean hasTask = task != null && !task.isBlank();

        if (!hasTask) {
            return spawnInfo + "\nstatus: accepted";
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            final String capturedTask = task;
            taskRepository.putTask(
                    taskId,
                    agentId,
                    () -> {
                        try {
                            Msg reply =
                                    agentManager
                                            .invokeAgent(agent, sessionId, capturedTask)
                                            .block();
                            return reply != null ? reply.getTextContent() : "";
                        } catch (RuntimeException e) {
                            return "Error: "
                                    + (e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName());
                        }
                    });
            return spawnInfo + "\n" + String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId);
        }

        try {
            Msg reply =
                    agentManager
                            .invokeAgent(agent, sessionId, task.trim())
                            .block(Duration.ofMillis(timeoutMs));
            String text = reply != null ? reply.getTextContent() : "";
            return spawnInfo + "\nstatus: ok\nreply:\n" + text;
        } catch (RuntimeException e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("agent_spawn execute failed: agentId={}", agentId, e);
            return spawnInfo + "\nstatus: error\nerror: " + err;
        }
    }

    @Tool(
            name = "agent_send",
            description =
                    """
                    Send a message to an existing subagent. Use the exact string from the \
                    agent_key line of agent_spawn output (starts with agent:), or the label \
                    you set at spawn. Do not pass agent_id, session_id, or task_id here. \
                    timeout_seconds=0 returns task_id for task_output.\
                    """)
    public String agentSend(
            @ToolParam(
                            name = "agent_key",
                            description =
                                    "Exact value from agent_spawn's first line after 'agent_key: '"
                                        + " (format agent:<type>:<uuid>). Not agent_id, session_id,"
                                        + " or task_id. Mutually exclusive with label.",
                            required = false)
                    String agentKey,
            @ToolParam(
                            name = "label",
                            description =
                                    "Agent label assigned at spawn time. Mutually exclusive with"
                                            + " agent_key.",
                            required = false)
                    String label,
            @ToolParam(name = "message", description = "Message to send to the subagent")
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

        boolean hasKey = agentKey != null && !agentKey.isBlank();
        boolean hasLabel = label != null && !label.isBlank();
        if (hasKey && hasLabel) {
            return "Error: Provide either agent_key or label, not both.";
        }
        if (!hasKey && !hasLabel) {
            return "Error: Either agent_key or label is required.";
        }
        if (message == null || message.isBlank()) {
            return "Error: message is required";
        }

        String key;
        if (hasKey) {
            key = agentKey.trim();
        } else {
            key = labelToKey.get(label.trim().toLowerCase());
            if (key == null) {
                return "Error: Unknown label: " + label.trim();
            }
        }

        SpawnedAgent spawned = agentsByKey.get(key);
        if (spawned == null) {
            return "Error: Unknown agent_key: " + key;
        }

        long timeoutMs = resolveTimeoutMs(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);

        if (timeoutMs == 0) {
            String taskId = "task_" + UUID.randomUUID();
            taskRepository.putTask(
                    taskId,
                    spawned.agentId(),
                    () -> {
                        try {
                            Msg reply =
                                    agentManager
                                            .invokeAgent(
                                                    spawned.agent(), spawned.sessionId(), message)
                                            .block();
                            return reply != null ? reply.getTextContent() : "";
                        } catch (RuntimeException e) {
                            return "Error: "
                                    + (e.getMessage() != null
                                            ? e.getMessage()
                                            : e.getClass().getSimpleName());
                        }
                    });
            return String.format(BG_RESULT_TEMPLATE, taskId, taskId, taskId);
        }

        try {
            Msg reply =
                    agentManager
                            .invokeAgent(spawned.agent(), spawned.sessionId(), message.trim())
                            .block(Duration.ofMillis(timeoutMs));
            String text = reply != null ? reply.getTextContent() : "";
            return "agent_key: " + key + "\nstatus: ok\nreply:\n" + text;
        } catch (RuntimeException e) {
            String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("agent_send failed: key={}", key, e);
            return "Error: " + err;
        }
    }

    @Tool(name = "agent_list", description = "List active subagents spawned by this agent.")
    public String agentList() {
        if (agentsByKey.isEmpty()) {
            return "No active subagents.";
        }

        StringBuilder sb =
                new StringBuilder("Active subagents (").append(agentsByKey.size()).append("):\n");
        for (SpawnedAgent a : agentsByKey.values()) {
            sb.append("- agent_key: ").append(a.key()).append("\n");
            sb.append("  agent_id: ").append(a.agentId()).append("\n");
            if (a.label() != null) {
                sb.append("  label: ").append(a.label()).append("\n");
            }
            sb.append("  spawn_depth: ").append(a.depth()).append("\n");
        }
        return sb.toString().trim();
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private static long resolveTimeoutMs(Integer timeoutSeconds, int defaultSeconds) {
        if (timeoutSeconds == null) {
            return (long) defaultSeconds * 1_000;
        }
        if (timeoutSeconds <= 0) {
            return 0L;
        }
        return (long) Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS) * 1_000;
    }

    private static String formatSpawnInfo(String key, String agentId, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent_key: ").append(key).append("\n");
        sb.append("agent_id: ").append(agentId).append("\n");
        sb.append("session_id: ").append(sessionId);
        return sb.toString();
    }
}
