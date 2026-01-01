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
package io.agentscope.core.hook;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/**
 * Event fired after tool execution completes.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setToolResult(ToolResultBlock)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getToolkit()} - The toolkit instance</li>
 *   <li>{@link #getToolUse()} - The original tool call</li>
 *   <li>{@link #getToolResult()} - The tool execution result (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This is called once per tool execution.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Post-process individual tool results</li>
 *   <li>Filter or sanitize tool output</li>
 *   <li>Add metadata to results</li>
 *   <li>Handle tool execution errors</li>
 *   <li>Transform result format</li>
 * </ul>
 */
public final class PostActingEvent extends ActingEvent {

    private ToolResultBlock toolResult;

    /**
     * Constructor for PostActingEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param toolkit The toolkit instance (must not be null)
     * @param toolUse The original tool call (must not be null)
     * @param toolResult The tool execution result (must not be null)
     * @throws NullPointerException if agent, toolkit, toolUse, or toolResult is null
     */
    public PostActingEvent(
            Agent agent, Toolkit toolkit, ToolUseBlock toolUse, ToolResultBlock toolResult) {
        super(HookEventType.POST_ACTING, agent, toolkit, toolUse);
        this.toolResult = Objects.requireNonNull(toolResult, "toolResult cannot be null");
    }

    /**
     * Get the tool execution result.
     *
     * @return The tool result block
     */
    public ToolResultBlock getToolResult() {
        return toolResult;
    }

    /**
     * Modify the tool execution result.
     *
     * @param toolResult The new tool result (must not be null)
     * @throws NullPointerException if toolResult is null
     */
    public void setToolResult(ToolResultBlock toolResult) {
        this.toolResult = Objects.requireNonNull(toolResult, "toolResult cannot be null");
    }
}
