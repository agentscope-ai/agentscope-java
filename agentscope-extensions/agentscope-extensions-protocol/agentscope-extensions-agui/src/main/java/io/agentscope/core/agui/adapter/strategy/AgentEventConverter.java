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
import java.util.Set;

/**
 * Extension point for converting AgentScope events to AG-UI protocol events.
 *
 * <p>Implementations declare the {@link AgentEvent} types they handle and emit AG-UI events through
 * the provided {@link AguiStreamContext}. Custom converters can be registered through
 * {@code AguiAdapterConfig} to extend or override built-in semantic mappings.
 */
public interface AgentEventConverter {

    /**
     * Return the AgentScope event types handled by this converter.
     *
     * @return supported AgentScope event types
     */
    Set<Class<? extends AgentEvent>> eventTypes();

    /**
     * Convert a source AgentScope event by emitting AG-UI events to the stream context.
     *
     * @param event source AgentScope event
     * @param context stream conversion context used to emit AG-UI events
     */
    void convert(AgentEvent event, AguiStreamContext context);
}
