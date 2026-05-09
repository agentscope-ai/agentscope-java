/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.subagent.tools.task;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.util.Assert;

/**
 * AgentScope tool for retrieving output from running or completed background tasks.
 * Use when TaskTool was invoked with run_in_background=true; provides the task_id
 * to check status and retrieve results.
 */
public class TaskOutputTool {

    private static final String DEFAULT_DESCRIPTION =
            """
            Retrieves output from a running or completed background task (sub-agent).
            Use when a Task was launched with run_in_background=true.
            Parameters: task_id (required), block (wait for completion, default true), timeout (max wait ms, default 30000).
            """;

    private final TaskRepository taskRepository;

    public TaskOutputTool(TaskRepository taskRepository) {
        Assert.notNull(taskRepository, "taskRepository must not be null");
        this.taskRepository = taskRepository;
    }

    @Tool(name = "TaskOutput", description = DEFAULT_DESCRIPTION)
    public String taskOutput(
            @ToolParam(
                            name = "task_id",
                            description =
                                    "The task ID returned by the Task tool when run_in_background"
                                            + " was true",
                            required = true)
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

        BackgroundTask bgTask = taskRepository.getTask(taskId);

        if (bgTask == null) {
            return "Error: No background task found with ID: " + taskId;
        }

        boolean shouldBlock = block == null || block;
        long timeoutMs = timeout != null ? Math.min(timeout, 600000) : 30000;

        if (shouldBlock && !bgTask.isCompleted()) {
            try {
                bgTask.waitForCompletion(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Error: Wait for task interrupted";
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("Task ID: ").append(taskId).append("\n");
        result.append("Status: ").append(bgTask.getStatus()).append("\n\n");

        if (bgTask.isCompleted() && bgTask.getResult() != null) {
            result.append("Result:\n").append(bgTask.getResult());
        } else if (bgTask.getError() != null) {
            result.append("Error:\n").append(bgTask.getError().getMessage());
            if (bgTask.getError().getCause() != null) {
                result.append("\nCause: ").append(bgTask.getError().getCause().getMessage());
            }
        } else if (!bgTask.isCompleted()) {
            result.append("Task still running...");
        }

        return result.toString();
    }
}
