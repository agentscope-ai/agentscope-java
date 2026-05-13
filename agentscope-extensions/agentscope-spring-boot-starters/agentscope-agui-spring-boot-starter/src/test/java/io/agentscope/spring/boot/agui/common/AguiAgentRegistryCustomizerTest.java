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
package io.agentscope.spring.boot.agui.common;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AguiAgentRegistryCustomizer}.
 */
@Tag("unit")
@DisplayName("AguiAgentRegistryCustomizer Unit Tests")
class AguiAgentRegistryCustomizerTest {

    @Test
    @DisplayName("Should invoke customize method")
    void testCustomize() {
        Agent mockAgent = mock(Agent.class);
        AguiAgentRegistryCustomizer customizer =
                new AguiAgentRegistryCustomizer() {
                    @Override
                    public void customize(AguiAgentRegistry registry) {
                        registry.registerFactory("custom-agent", () -> mockAgent);
                    }
                };

        AguiAgentRegistry registry = new AguiAgentRegistry();
        customizer.customize(registry);

        assertTrue(registry.hasAgent("custom-agent"));
    }

    @Test
    @DisplayName("Should invoke customize method by consumer accept")
    void testConsumer() {
        Agent mockAgent = mock(Agent.class);
        Consumer<AguiAgentRegistry> consumer =
                new AguiAgentRegistryCustomizer() {
                    @Override
                    public void customize(AguiAgentRegistry registry) {
                        registry.registerFactory("custom-agent", () -> mockAgent);
                    }
                };

        AguiAgentRegistry registry = new AguiAgentRegistry();
        consumer.accept(registry);

        assertTrue(registry.hasAgent("custom-agent"));
    }
}
