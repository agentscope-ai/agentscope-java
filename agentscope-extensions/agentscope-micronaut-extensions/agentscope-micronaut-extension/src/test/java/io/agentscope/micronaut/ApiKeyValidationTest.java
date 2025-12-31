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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.Model;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for API key validation in AgentScope Micronaut integration.
 *
 * <p>Verifies that appropriate exceptions are thrown when required API keys are missing.
 */
class ApiKeyValidationTest {

    @Test
    void shouldFailWhenDashScopeApiKeyMissing() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.dashscope.enabled",
                                                    "true",
                                                    "agentscope.model.provider",
                                                    "dashscope",
                                                    "agentscope.dashscope.api-key",
                                                    ""))) {
                                ctx.getBean(Model.class);
                            }
                        });

        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }

        assertTrue(cause instanceof IllegalStateException);
        assertTrue(
                cause.getMessage().contains("agentscope.dashscope.api-key must be configured"),
                "Expected error message about missing DashScope API key, but got: "
                        + cause.getMessage());
    }

    @Test
    void shouldFailWhenOpenAIApiKeyMissing() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "openai",
                                                    "agentscope.openai.enabled",
                                                    "true",
                                                    "agentscope.openai.model-name",
                                                    "gpt-4"))) {
                                ctx.getBean(Model.class);
                            }
                        });

        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }

        assertTrue(cause instanceof IllegalStateException);
        assertTrue(
                cause.getMessage().contains("agentscope.openai.api-key must be configured"),
                "Expected error message about missing OpenAI API key, but got: "
                        + cause.getMessage());
    }

    @Test
    void shouldFailWhenAnthropicApiKeyMissing() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "anthropic",
                                                    "agentscope.anthropic.enabled",
                                                    "true",
                                                    "agentscope.anthropic.model-name",
                                                    "claude-sonnet-4.5"))) {
                                ctx.getBean(Model.class);
                            }
                        });

        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }

        assertTrue(cause instanceof IllegalStateException);
        assertTrue(
                cause.getMessage().contains("agentscope.anthropic.api-key must be configured"),
                "Expected error message about missing Anthropic API key, but got: "
                        + cause.getMessage());
    }

    @Test
    void shouldFailWhenGeminiApiKeyMissingAndVertexAIDisabled() {
        Exception exception =
                assertThrows(
                        BeanInstantiationException.class,
                        () -> {
                            try (ApplicationContext ctx =
                                    ApplicationContext.run(
                                            Map.of(
                                                    "agentscope.model.provider",
                                                    "gemini",
                                                    "agentscope.gemini.enabled",
                                                    "true",
                                                    "agentscope.gemini.vertex-ai",
                                                    "false",
                                                    "agentscope.gemini.model-name",
                                                    "gemini-2.0-flash"))) {
                                ctx.getBean(Model.class);
                            }
                        });

        Throwable cause = exception.getCause();
        while (cause != null && !(cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }

        assertTrue(cause instanceof IllegalStateException);
        assertTrue(
                cause.getMessage().contains("agentscope.gemini.api-key must be configured")
                        || cause.getMessage().contains("Gemini"),
                "Expected error message about missing Gemini API key, but got: "
                        + cause.getMessage());
    }
}
