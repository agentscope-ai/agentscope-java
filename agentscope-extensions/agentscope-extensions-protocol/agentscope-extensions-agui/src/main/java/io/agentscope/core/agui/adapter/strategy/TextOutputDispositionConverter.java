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
import io.agentscope.core.event.TextOutputDispositionEvent;
import java.util.Set;

/** Converts opt-in text output lifecycle signals to AG-UI custom events and final snapshots. */
public final class TextOutputDispositionConverter implements AgentEventConverter {

    public static final String EVENT_NAME = "agentscope.text_output.disposition";

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(TextOutputDispositionEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        context.emitTextOutputDisposition((TextOutputDispositionEvent) event);
    }
}
