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

/**
 * Base class for tool execution (acting) related events.
 *
 * <p>This sealed class provides common context for all acting events:
 * <ul>
 *   <li>{@link #getToolkit()} - The toolkit instance</li>
 *   <li>{@link #getToolUse()} - The tool being executed</li>
 * </ul>
 *
 * <p>Subclasses represent different stages of tool execution:
 * <ul>
 *   <li>{@link PreActingEvent} - Before tool execution</li>
 *   <li>{@link PostActingEvent} - After tool execution</li>
 *   <li>{@link ActingChunkEvent} - During tool streaming</li>
 * </ul>
 *
 * @see PreActingEvent
 * @see PostActingEvent
 * @see ActingChunkEvent
 */
public abstract sealed class ActingEvent extends HookEvent
        permits PreActingEvent, PostActingEvent, ActingChunkEvent {

    private final Toolkit toolkit;
    protected ToolUseBlock toolUse;

    /**
     * Constructor for ActingEvent.
     *
     * @param type The event type (must not be null)
     * @param agent The agent instance (must not be null)
     * @param toolkit The toolkit instance
     * @param toolUse The tool being executed (can be null for empty events)
     */
    protected ActingEvent(HookEventType type, Agent agent, Toolkit toolkit, ToolUseBlock toolUse) {
        super(type, agent);
        this.toolkit = toolkit;
        this.toolUse = toolUse;
    }

    /**
     * Get the toolkit instance.
     *
     * @return The toolkit
     */
    public final Toolkit getToolkit() {
        return toolkit;
    }

    /**
     * Get the tool being executed.
     *
     * @return The tool use block
     */
    public final ToolUseBlock getToolUse() {
        return toolUse;
    }
}
