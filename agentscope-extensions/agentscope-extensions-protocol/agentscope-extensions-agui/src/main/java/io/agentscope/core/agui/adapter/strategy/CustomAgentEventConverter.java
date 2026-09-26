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

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.Set;

/**
 * Converts AgentScope custom events to AG-UI {@code CUSTOM} events.
 */
final class CustomAgentEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(CustomEvent.class);
    }

    /**
     * Emit an AG-UI custom event with the source event name and value.
     *
     * @param event source custom event
     * @param context stream conversion context
     */
    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        CustomEvent customEvent = (CustomEvent) event;
        context.emit(
                new AguiEvent.Custom(
                        context.getThreadId(),
                        context.getRunId(),
                        customEvent.getName(),
                        customEvent.getValue()));
    }
}
