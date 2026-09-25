/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import java.util.Set;

final class ToolResultEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(
                ToolResultStartEvent.class,
                ToolResultTextDeltaEvent.class,
                ToolResultDataDeltaEvent.class,
                ToolResultEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof ToolResultTextDeltaEvent textDelta) {
            context.appendToolResultText(textDelta.getToolCallId(), textDelta.getDelta());
        } else if (event instanceof ToolResultDataDeltaEvent dataDelta) {
            context.appendToolResultData(dataDelta.getToolCallId(), dataDelta.getData());
        } else if (event instanceof ToolResultStartEvent start) {
            context.beginToolResult(start.getToolCallId());
        } else {
            ToolResultEndEvent end = (ToolResultEndEvent) event;
            if (end.getState() == ToolResultState.RUNNING) {
                context.markToolCallSuspended(end.getToolCallId());
                return;
            }
            context.endToolResult(end.getReplyId(), end.getToolCallId());
        }
    }
}
