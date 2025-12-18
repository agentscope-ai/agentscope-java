/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.spring.boot.agui.webflux;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.spring.boot.agui.webflux.config.AguiAgentRegistry;
import io.agentscope.spring.boot.agui.webflux.handler.AguiWebFluxHandler;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AguiWebFluxHandler.
 */
class AguiWebFluxHandlerTest {

    @Test
    void testBuilderRequiresRegistry() {
        assertThrows(IllegalStateException.class, () -> AguiWebFluxHandler.builder().build());
    }

    @Test
    void testBuilderWithRegistry() {
        AguiAgentRegistry registry = new AguiAgentRegistry();

        AguiWebFluxHandler handler = AguiWebFluxHandler.builder().agentRegistry(registry).build();

        assertNotNull(handler);
    }

    @Test
    void testBuilderWithConfig() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        AguiAdapterConfig config = AguiAdapterConfig.defaultConfig();

        AguiWebFluxHandler handler =
                AguiWebFluxHandler.builder().agentRegistry(registry).config(config).build();

        assertNotNull(handler);
    }
}
