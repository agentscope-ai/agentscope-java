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
 * Default AG-UI base event properties enricher.
 */
public class BaseEventPropertiesEnricher implements AguiEventEnricher {

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
