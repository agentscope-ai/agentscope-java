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
package io.agentscope.external.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verifies custom converters outside the AG-UI package can emit events.
 */
@Tag("unit")
@DisplayName("External AgentEventConverter Unit Tests")
class ExternalAgentEventConverterTest {

    @Test
    @DisplayName("Should allow external converter to emit AG-UI events")
    void testExternalConverterCanEmitEvents() {
        AguiStreamContext context =
                new AguiStreamContext("thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        AgentEventConverter converter = new ExternalCustomEventConverter();

        context.beginEvent();
        converter.convert(new CustomEvent("external", Map.of("ok", true)), context);

        List<AguiEvent> events = context.drainEvents();
        assertEquals(1, events.size());
        AguiEvent.Custom custom = (AguiEvent.Custom) events.get(0);
        assertEquals("external_custom", custom.name());
        assertEquals(true, ((Map<?, ?>) custom.value()).get("ok"));
    }

    private static final class ExternalCustomEventConverter implements AgentEventConverter {

        @Override
        public Set<Class<? extends AgentEvent>> eventTypes() {
            return Set.of(CustomEvent.class);
        }

        @Override
        public void convert(AgentEvent event, AguiStreamContext context) {
            context.emit(
                    new AguiEvent.Custom(
                            context.getThreadId(),
                            context.getRunId(),
                            "external_custom",
                            ((CustomEvent) event).getValue()));
        }
    }
}
