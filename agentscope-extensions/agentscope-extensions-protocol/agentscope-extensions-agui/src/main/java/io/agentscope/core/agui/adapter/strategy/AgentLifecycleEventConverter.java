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

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import java.util.Set;

final class AgentLifecycleEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(
                AgentStartEvent.class,
                AgentEndEvent.class,
                AgentResultEvent.class,
                ModelCallStartEvent.class,
                ModelCallEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {}
}
