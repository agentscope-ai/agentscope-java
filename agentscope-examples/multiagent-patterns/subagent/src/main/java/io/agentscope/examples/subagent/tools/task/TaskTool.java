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

import io.agentscope.core.agent.CallableAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

/**
 * AgentScope tool that invokes sub-agents to handle complex, isolated tasks.
 * Supports both synchronous and background execution; use {@link TaskOutputTool}
 * to retrieve results when {@code run_in_background=true}.
 */
public class TaskTool {

    private static final Logger logger = LoggerFactory.getLogger(TaskTool.class);

    private final Map<String, CallableAgent> subAgents;
    private final TaskRepository taskRepository;

    public TaskTool(Map<String, CallableAgent> subAgents, TaskRepository taskRepository) {
        this.subAgents = Map.copyOf(subAgents);
        this.taskRepository = taskRepository;
    }

    @Tool(
            name = "Task",
            description =
                    """
                    Launch a specialized sub-agent to handle complex, multi-step tasks autonomously.
                    Use when the task requires exploring codebases, researching, or multi-step execution.
                    Parameters: description (short summary), prompt (task for the sub-agent), subagent_type (required), run_in_background (optional).
                    When run_in_background=true, returns a task_id; use TaskOutput tool with that task_id to get results.
                    """)
    public String task(
            @ToolParam(
                            name = "description",
                            description = "Short (3-5 word) summary of what the agent will do")
                    String description,
            @ToolParam(
                            name = "prompt",
                            description = "The detailed task for the sub-agent to perform",
                            required = true)
                    String prompt,
            @ToolParam(
                            name = "subagent_type",
                            description = "The type of specialized agent to use",
                            required = true)
                    String subagentType,
            @ToolParam(
                            name = "run_in_background",
                            description =
                                    "Set to true to run asynchronously; use TaskOutput to retrieve"
                                            + " results",
                            required = false)
                    Boolean runInBackground) {

        if (!StringUtils.hasText(subagentType)) {
            return "Error: subagent_type is required";
        }
        if (!subAgents.containsKey(subagentType)) {
            return "Error: Unknown subagent type: "
                    + subagentType
                    + ". Allowed types: "
                    + subAgents.keySet();
        }
        if (!StringUtils.hasText(prompt)) {
            return "Error: prompt is required";
        }

        CallableAgent subAgent = subAgents.get(subagentType);

        if (Boolean.TRUE.equals(runInBackground)) {
            String taskId = "task_" + UUID.randomUUID();
            taskRepository.putTask(taskId, () -> executeSubAgent(subAgent, prompt));
            return String.format(
                    "task_id: %s%n%nBackground task started. Use TaskOutput tool with task_id='%s'"
                            + " to retrieve results.",
                    taskId, taskId);
        }

        return executeSubAgent(subAgent, prompt);
    }

    private String executeSubAgent(CallableAgent subAgent, String prompt) {
        try {
            Msg userMsg = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
            Msg response = subAgent.call(userMsg).block();
            if (response == null) {
                return "Sub-agent returned no response";
            }
            String text = response.getTextContent();
            return StringUtils.hasText(text) ? text : "Sub-agent returned empty response";
        } catch (Exception e) {
            logger.warn("Sub-agent execution failed: {}", e.getMessage());
            return "Error executing sub-agent: " + e.getMessage();
        }
    }
}
