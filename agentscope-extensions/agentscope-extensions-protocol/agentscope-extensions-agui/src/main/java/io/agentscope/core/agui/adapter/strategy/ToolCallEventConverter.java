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
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.Set;

final class ToolCallEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(ToolCallStartEvent.class, ToolCallDeltaEvent.class, ToolCallEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof ToolCallDeltaEvent delta) {
            if (context.getConfig().isEmitToolCallArgs()) {
                context.appendToolCallArgs(delta.getToolCallId(), delta.getDelta());
            }
        } else if (event instanceof ToolCallStartEvent start) {
            context.startToolCall(start.getToolCallId(), start.getToolCallName());
        } else {
            ToolCallEndEvent end = (ToolCallEndEvent) event;
            context.endToolCall(end.getToolCallId());
        }
    }
}
