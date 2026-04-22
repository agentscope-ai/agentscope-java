/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentProperties configuration validation.
 *
 * <p>Validates that appropriate exceptions are thrown for invalid agent configuration.
 */
class AgentConfigurationValidationTest {

    @Test
    void shouldFailWhenAgentNameIsEmpty() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    ""))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("agentscope.agent.name must be configured"),
                "Expected error about empty name but got: " + exception.getMessage());
    }

    @Test
    void shouldFailWhenAgentNameIsWhitespace() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    "   "))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("agentscope.agent.name must be configured"),
                "Expected error about whitespace name but got: " + exception.getMessage());
    }

    @Test
    void shouldFailWhenSysPromptIsEmpty() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    "TestAgent",
                                                    "agentscope.agent.sys-prompt",
                                                    ""))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("agentscope.agent.sys-prompt must be configured"),
                "Expected error about empty sys-prompt but got: " + exception.getMessage());
    }

    @Test
    void shouldFailWhenSysPromptIsWhitespace() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    "TestAgent",
                                                    "agentscope.agent.sys-prompt",
                                                    "   "))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("agentscope.agent.sys-prompt must be configured"),
                "Expected error about whitespace sys-prompt but got: " + exception.getMessage());
    }

    @Test
    void shouldFailWhenMaxItersIsZero() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    "TestAgent",
                                                    "agentscope.agent.max-iters",
                                                    "0"))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception
                        .getMessage()
                        .contains("agentscope.agent.max-iters must be a positive integer"),
                "Expected error about zero max-iters but got: " + exception.getMessage());
    }

    @Test
    void shouldFailWhenMaxItersIsNegative() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.dashscope.api-key",
                                                    "test-key",
                                                    "agentscope.agent.enabled",
                                                    "true",
                                                    "agentscope.agent.name",
                                                    "TestAgent",
                                                    "agentscope.agent.max-iters",
                                                    "-5"))) {
                                ctx.getBean(ReActAgent.class);
                            }
                        });

        assertTrue(
                exception
                        .getMessage()
                        .contains("agentscope.agent.max-iters must be a positive integer"),
                "Expected error about negative max-iters but got: " + exception.getMessage());
    }

    @Test
    void shouldSucceedWithValidConfiguration() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "ValidAgent",
                                "agentscope.agent.sys-prompt",
                                "Valid prompt",
                                "agentscope.agent.max-iters",
                                "10"))) {

            ReActAgent agent = ctx.getBean(ReActAgent.class);
            assertEquals("ValidAgent", agent.getName());
        }
    }

    @Test
    void shouldSucceedWithMinimalMaxIters() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "MinimalAgent",
                                "agentscope.agent.sys-prompt",
                                "Valid prompt",
                                "agentscope.agent.max-iters",
                                "1"))) {

            ReActAgent agent = ctx.getBean(ReActAgent.class);
            assertEquals("MinimalAgent", agent.getName());
        }
    }

    @Test
    void shouldSucceedWithLargeMaxIters() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.agent.enabled",
                                "true",
                                "agentscope.agent.name",
                                "LargeIterAgent",
                                "agentscope.agent.sys-prompt",
                                "Valid prompt",
                                "agentscope.agent.max-iters",
                                "1000"))) {

            ReActAgent agent = ctx.getBean(ReActAgent.class);
            assertEquals("LargeIterAgent", agent.getName());
        }
    }
}
