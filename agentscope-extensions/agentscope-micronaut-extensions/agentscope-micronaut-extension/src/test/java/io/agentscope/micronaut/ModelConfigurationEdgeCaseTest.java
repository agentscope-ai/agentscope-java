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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.Model;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for edge cases and boundary conditions in model configuration.
 */
class ModelConfigurationEdgeCaseTest {

    @Test
    void shouldHandleEmptyApiKeyGracefully() {
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
                                                    ""))) {
                                ctx.getBean(Model.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("api-key must be configured"),
                "Expected error about empty API key");
    }

    @Test
    void shouldHandleDisabledProviderWhenSelected() {
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
                                                    "false",
                                                    "agentscope.openai.api-key",
                                                    "test-key"))) {
                                ctx.getBean(Model.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("auto-configuration is disabled"),
                "Expected error about disabled provider");
    }

    @Test
    void shouldHandleCaseInsensitiveProviderName() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "DashScope", // Mixed case
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
        }
    }

    @Test
    void shouldHandleProviderNameWithSpaces() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "  dashscope  ", // With spaces
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
        }
    }

    @Test
    void shouldValidateGeminiRequiresApiKeyOrProject() {
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
                                                    "true"
                                                    // Neither api-key nor project provided
                                                    ))) {
                                ctx.getBean(Model.class);
                            }
                        });

        assertTrue(
                exception.getMessage().contains("api-key or")
                        && exception.getMessage().contains("project"),
                "Expected error about missing api-key or project for Gemini");
    }

    @Test
    void shouldAcceptGeminiWithApiKeyOnly() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "gemini",
                                "agentscope.gemini.enabled",
                                "true",
                                "agentscope.gemini.api-key",
                                "test-key"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            // Model name can vary based on default
            assertTrue(model.getModelName().startsWith("gemini-"));
        }
    }

    @Test
    void shouldAcceptGeminiWithProjectOnlyButRequiresVertexAI() {
        // This test validates that project-only config requires vertex-ai to be true
        // Vertex AI requires GCP credentials which we don't have in tests
        // So we just validate the configuration is accepted but skip actual model creation
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
                                                    "agentscope.gemini.project",
                                                    "test-project",
                                                    "agentscope.gemini.location",
                                                    "us-central1",
                                                    "agentscope.gemini.vertex-ai",
                                                    "true"))) {
                                ctx.getBean(Model.class);
                            }
                        });

        // Should fail with credentials error, not configuration error
        assertTrue(
                exception.getMessage().contains("credentials")
                        || exception.getMessage().contains("credential"),
                "Expected credentials error but got: " + exception.getMessage());
    }

    @Test
    void shouldHandleOpenAIWithCustomBaseUrl() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "openai",
                                "agentscope.openai.enabled",
                                "true",
                                "agentscope.openai.api-key",
                                "test-key",
                                "agentscope.openai.base-url",
                                "https://custom.api.com/v1",
                                "agentscope.openai.model-name",
                                "gpt-4"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("gpt-4", model.getModelName());
        }
    }

    @Test
    void shouldHandleAnthropicWithCustomBaseUrl() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "anthropic",
                                "agentscope.anthropic.enabled",
                                "true",
                                "agentscope.anthropic.api-key",
                                "test-key",
                                "agentscope.anthropic.base-url",
                                "https://custom.anthropic.com",
                                "agentscope.anthropic.model-name",
                                "claude-3-opus"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
            assertEquals("claude-3-opus", model.getModelName());
        }
    }

    @Test
    void shouldHandleDashScopeWithThinkingEnabled() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.enable-thinking",
                                "true"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
        }
    }

    @Test
    void shouldHandleDashScopeWithStreamDisabled() {
        try (ApplicationContext ctx =
                ApplicationContext.run(
                        Map.of(
                                "agentscope.model.provider",
                                "dashscope",
                                "agentscope.dashscope.enabled",
                                "true",
                                "agentscope.dashscope.api-key",
                                "test-key",
                                "agentscope.dashscope.stream",
                                "false"))) {

            Model model = ctx.getBean(Model.class);
            assertNotNull(model);
        }
    }
}
