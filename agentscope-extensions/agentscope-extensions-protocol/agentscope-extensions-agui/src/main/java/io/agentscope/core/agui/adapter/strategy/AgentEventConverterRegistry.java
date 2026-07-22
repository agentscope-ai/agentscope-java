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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AgentEventConverterRegistry {

    private final Map<Class<? extends AgentEvent>, AgentEventConverter> converters;
    private final RawAgentEventConverter rawConverter = new RawAgentEventConverter();
    private final List<AguiEventEnricher> enrichers;

    public AgentEventConverterRegistry() {
        this(List.of(), List.of());
    }

    public AgentEventConverterRegistry(
            List<AgentEventConverter> customConverters, List<AguiEventEnricher> enrichers) {
        Map<Class<? extends AgentEvent>, AgentEventConverter> map = new LinkedHashMap<>();
        register(map, new AgentLifecycleEventConverter());
        register(map, new TextBlockEventConverter());
        register(map, new ThinkingBlockEventConverter());
        register(map, new ToolCallEventConverter());
        register(map, new ToolResultEventConverter());
        register(map, new ModelCallUsageEventConverter());
        register(map, new CustomAgentEventConverter());
        if (customConverters != null) {
            for (AgentEventConverter converter : customConverters) {
                register(map, Objects.requireNonNull(converter, "converter cannot be null"));
            }
        }
        this.converters = Map.copyOf(map);
        this.enrichers = enrichers != null ? List.copyOf(enrichers) : List.of();
    }

    public List<AguiEvent> convert(AgentEvent event, AguiStreamContext context) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        context.beginEvent();
        converters.getOrDefault(event.getClass(), rawConverter).convert(event, context);
        return enrich(event, context.drainEvents(), context);
    }

    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        Objects.requireNonNull(events, "events cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        List<AguiEvent> enriched = List.copyOf(events);
        for (AguiEventEnricher enricher : enrichers) {
            enriched =
                    List.copyOf(
                            Objects.requireNonNull(
                                    enricher.enrich(source, enriched, context),
                                    "enriched events cannot be null"));
        }
        return enriched;
    }

    private static void register(
            Map<Class<? extends AgentEvent>, AgentEventConverter> map,
            AgentEventConverter converter) {
        for (Class<? extends AgentEvent> eventType : converter.eventTypes()) {
            map.put(eventType, converter);
        }
    }
}
