/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentScope Micronaut starter auto-configuration.
 */
@MicronautTest
class AgentscopeFactoryTest {

    @Inject ApplicationContext context;

    @Test
    void testMemoryBeanCreated() {
        Memory memory = context.getBean(Memory.class);
        assertNotNull(memory, "Memory bean should be created");
    }

    @Test
    void testToolkitBeanCreated() {
        Toolkit toolkit = context.getBean(Toolkit.class);
        assertNotNull(toolkit, "Toolkit bean should be created");
    }

    @Test
    void testModelBeanCreated() {
        // Start context with minimal config
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {
            assertTrue(ctx.containsBean(Model.class), "Model bean should be created");
        }
    }

    @Test
    void testReActAgentBeanCreated() {
        // Start context with minimal config
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {
            assertTrue(ctx.containsBean(ReActAgent.class), "ReActAgent bean should be created");
        }
    }

    @Test
    void testAgentDisabled() {
        // Start context with agent disabled
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.agent.enabled",
                                "false",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {
            assertTrue(
                    !ctx.containsBean(ReActAgent.class),
                    "ReActAgent bean should not be created when disabled");
        }
    }
}
