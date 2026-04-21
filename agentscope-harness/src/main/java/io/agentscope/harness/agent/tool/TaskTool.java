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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Unified tool for background task lifecycle management. Combines task result retrieval,
 * cancellation, and listing into a single tool class.
 *
 * <ul>
 *   <li>{@code task_output} — retrieve result (blocking or non-blocking)
 *   <li>{@code task_cancel} — cancel a running task
 *   <li>{@code task_list} — list all tracked tasks with optional status filter
 * </ul>
 */
public class TaskTool {

    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final TaskRepository taskRepository;

    public TaskTool(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Tool(
            name = "task_output",
            description =
                    "Retrieve the output of a background subagent task. Use when agent_spawn or"
                        + " agent_send was called with timeout_seconds=0. Supports blocking wait"
                        + " for completion or non-blocking status peek (block=false).")
    public String taskOutput(
            @ToolParam(
                            name = "task_id",
                            description =
                                    "The task_id returned by agent_spawn or agent_send when"
                                            + " timeout_seconds was 0")
                    String taskId,
            @ToolParam(
                            name = "block",
                            description = "Whether to wait for completion (default: true)",
                            required = false)
                    Boolean block,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Max wait time in milliseconds (default: 30000, max: 600000)",
                            required = false)
                    Long timeout) {

        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required";
        }

        BackgroundTask bgTask = taskRepository.getTask(taskId);
        if (bgTask == null) {
            return "Error: No background task found with ID: " + taskId;
        }

        bgTask.updateLastCheckedAt();

        boolean shouldBlock = block == null || block;
        long timeoutMs = timeout != null ? Math.min(timeout, 600_000) : 30_000;

        if (shouldBlock && !bgTask.isCompleted()) {
            try {
                bgTask.waitForCompletion(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Error: Wait for task interrupted";
            }
        }

        return formatTaskDetail(bgTask);
    }

    @Tool(
            name = "task_cancel",
            description =
                    "Cancel a running background task. Use to stop a task that is no longer"
                            + " needed. Has no effect on already-completed tasks.")
    public String taskCancel(
            @ToolParam(name = "task_id", description = "The task_id to cancel") String taskId) {

        if (taskId == null || taskId.isBlank()) {
            return "Error: task_id is required";
        }

        BackgroundTask bgTask = taskRepository.getTask(taskId);
        if (bgTask == null) {
            return "Error: No background task found with ID: " + taskId;
        }

        TaskStatus currentStatus = bgTask.getTaskStatus();
        if (currentStatus.isTerminal()) {
            return "task_id: "
                    + taskId
                    + "\nstatus: "
                    + currentStatus.name().toLowerCase()
                    + "\nnote: Task already in terminal state, cannot cancel.";
        }

        taskRepository.cancelTask(taskId);
        return "task_id: " + taskId + "\nstatus: cancelled\nCancellation requested successfully.";
    }

    @Tool(
            name = "task_list",
            description =
                    "List all tracked background tasks with their current statuses. Optionally"
                        + " filter by status (running, completed, failed, cancelled). Use to get an"
                        + " overview of all background work.")
    public String taskList(
            @ToolParam(
                            name = "status_filter",
                            description =
                                    "Filter by status: running, completed, failed, cancelled, or"
                                            + " omit for all tasks",
                            required = false)
                    String statusFilter) {

        TaskStatus filter = parseStatusFilter(statusFilter);
        Collection<BackgroundTask> tasks = taskRepository.listTasks(filter);

        if (tasks.isEmpty()) {
            String filterDesc =
                    filter != null ? " with status '" + filter.name().toLowerCase() + "'" : "";
            return "No background tasks tracked" + filterDesc + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(tasks.size()).append(" tracked task(s):\n");
        for (BackgroundTask task : tasks) {
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  created: ").append(ISO_FORMATTER.format(task.getCreatedAt()));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static TaskStatus parseStatusFilter(String filter) {
        if (filter == null || filter.isBlank() || "all".equalsIgnoreCase(filter.trim())) {
            return null;
        }
        try {
            return TaskStatus.valueOf(filter.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String formatTaskDetail(BackgroundTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("task_id: ").append(task.getTaskId()).append('\n');
        if (task.getAgentId() != null) {
            sb.append("agent_id: ").append(task.getAgentId()).append('\n');
        }
        sb.append("status: ").append(task.getStatus()).append('\n');
        sb.append("created_at: ").append(ISO_FORMATTER.format(task.getCreatedAt())).append('\n');

        if (task.isCompleted() && task.getResult() != null) {
            sb.append("\nResult:\n").append(task.getResult());
        } else if (task.getError() != null) {
            Exception err = task.getError();
            sb.append("\nError:\n").append(err.getMessage());
            if (err.getCause() != null) {
                sb.append("\nCause: ").append(err.getCause().getMessage());
            }
        } else if (!task.isCompleted()) {
            sb.append("\nTask still running...");
        } else {
            sb.append("\nTask completed with no result.");
        }
        return sb.toString();
    }
}
