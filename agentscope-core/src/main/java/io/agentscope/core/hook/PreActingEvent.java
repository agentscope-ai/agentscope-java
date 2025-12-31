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
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import java.util.Objects;

/**
 * Event fired before tool execution.
 *
 * <p><b>Modifiable:</b> Yes - {@link #setToolUse(ToolUseBlock)}
 *
 * <p><b>Context:</b>
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Agent's memory</li>
 *   <li>{@link #getToolkit()} - The toolkit instance</li>
 *   <li>{@link #getToolUse()} - The tool call to execute (modifiable)</li>
 * </ul>
 *
 * <p><b>Note:</b> This is called once per tool. If the reasoning result contains
 * multiple tool calls, this event fires multiple times.
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>Validate or modify tool parameters for each tool call</li>
 *   <li>Add authentication or context to individual tool calls</li>
 *   <li>Implement per-tool authorization checks</li>
 *   <li>Log or monitor individual tool invocations</li>
 * </ul>
 */
public final class PreActingEvent extends ActingEvent {

    /**
     * Constructor for PreActingEvent.
     *
     * @param agent The agent instance (must not be null)
     * @param toolkit The toolkit instance (must not be null)
     * @param toolUse The tool call to execute (must not be null)
     * @throws NullPointerException if agent, toolkit, or toolUse is null
     */
    public PreActingEvent(Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(HookEventType.PRE_ACTING, agent, toolkit, toolUse);
    }

    /**
     * Modify the tool call (e.g., change parameters).
     *
     * @param toolUse The new tool use block (must not be null)
     * @throws NullPointerException if toolUse is null
     */
    public void setToolUse(ToolUseBlock toolUse) {
        this.toolUse = Objects.requireNonNull(toolUse, "toolUse cannot be null");
    }
}
