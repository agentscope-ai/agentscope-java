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
package io.agentscope.core.event;

import io.agentscope.core.message.ToolUseBlock;
import java.util.List;

/**
 * Emitted during the acting phase when all tool calls from the most recent reasoning step were
 * denied (by user HITL confirmation or by permission rules).
 *
 * <p>Middlewares observing {@link io.agentscope.core.middleware.MiddlewareBase#onActing} can detect
 * this event and emit a {@link RequestStopEvent} with
 * {@link io.agentscope.core.message.GenerateReason#ALL_TOOLS_DENIED} to prevent the agent from
 * continuing to the next reasoning iteration.
 *
 * <p>If no middleware emits a stop, the agent continues reasoning as before (backward compatible).
 */
public class AllToolsDeniedEvent extends AgentEvent {

    private final List<ToolUseBlock> deniedToolCalls;

    public AllToolsDeniedEvent(List<ToolUseBlock> deniedToolCalls) {
        this.deniedToolCalls = deniedToolCalls != null ? List.copyOf(deniedToolCalls) : List.of();
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.ALL_TOOLS_DENIED;
    }

    /** Returns the tool calls that were denied. */
    public List<ToolUseBlock> getDeniedToolCalls() {
        return deniedToolCalls;
    }
}
