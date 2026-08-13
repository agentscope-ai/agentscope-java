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
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import java.util.Set;

/**
 * Suppresses ReActAgent external-execution handshake events in the default AG-UI stream.
 *
 * <p>AG-UI surfaces externally suspended tool calls through {@code RUN_FINISHED.outcome}, which is
 * derived from the final {@code AgentResultEvent}. These typed AgentScope events are still useful
 * to direct AgentEvent consumers, but emitting them would duplicate the native interrupt contract.
 */
final class ExternalExecutionEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireExternalExecutionEvent.class, ExternalExecutionResultEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        // Intentionally no-op.
    }
}
