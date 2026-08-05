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
import java.util.Set;

/**
 * Fallback converter that emits official AG-UI {@code RAW} events for unmapped AgentScope events.
 *
 * <p>The AG-UI raw event payload uses the protocol {@code event} and {@code source} fields. Base
 * event properties such as {@code rawEvent} are left to configured enrichers.
 */
final class RawAgentEventConverter implements AgentEventConverter {

    /**
     * Return an empty type set because this converter is selected as the registry fallback.
     *
     * @return no explicitly registered event types
     */
    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of();
    }

    /**
     * Convert an unmapped AgentScope event into an AG-UI raw event.
     *
     * @param event source AgentScope event
     * @param context stream conversion context
     */
    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        context.emit(
                new AguiEvent.Raw(
                        context.getThreadId(), context.getRunId(), event, event.getSource()));
    }
}
