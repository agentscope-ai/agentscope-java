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
import io.agentscope.core.agui.event.AguiEvents;
import io.agentscope.core.event.AgentEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * AG-UI base event properties enricher that fills missing event timestamps.
 *
 * <p>This enricher preserves existing timestamps and raw event payloads. It does not create a base
 * {@code rawEvent}; applications that want to expose raw source data should provide a custom
 * {@link AguiEventEnricher}.
 */
public class BaseEventPropertiesEnricher implements AguiEventEnricher {

    /**
     * Add a timestamp to each AG-UI event that does not already have one.
     *
     * @param source source AgentScope event, or {@code null} for framework-created events
     * @param events AG-UI events to enrich
     * @param context stream conversion context
     * @return events with AG-UI base properties preserved or filled
     */
    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        List<AguiEvent> enriched = new ArrayList<>(events.size());
        for (AguiEvent event : events) {
            Long timestamp =
                    event.timestamp() != null ? event.timestamp() : System.currentTimeMillis();
            enriched.add(AguiEvents.withBaseProperties(event, timestamp, event.rawEvent()));
        }
        return List.copyOf(enriched);
    }
}
