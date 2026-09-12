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
package io.agentscope.extensions.model.openaiofficial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class OpenAIOfficialModelProviderTest {

    private final OpenAIOfficialModelProvider provider = new OpenAIOfficialModelProvider();

    @Test
    void providerIdReturnsOpenAIOfficial() {
        assertEquals("openai-official", provider.providerId());
    }

    @Nested
    class Supports {

        @Test
        void matchesOwnPrefix() {
            assertTrue(provider.supports("openai-official:gpt-4o"));
            assertTrue(provider.supports("openai-official:o3"));
        }

        @Test
        void rejectsOtherPrefixes() {
            assertFalse(provider.supports("openai:gpt-4o"));
            assertFalse(provider.supports("anthropic:claude-3"));
        }

        @Test
        void rejectsNull() {
            assertFalse(provider.supports(null));
        }
    }

    @Nested
    class ContextResolution {

        @Test
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
        void createFromContextWithEnvFallback() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o", ModelCreationContext.builder().build());
            assertNotNull(model);
            assertEquals("gpt-4o", model.getModelName());
            assertTrue(model.supportsNativeStructuredOutput());
            assertTrue(model.supportsNativeStructuredOutputWithTools());
        }

        @Test
        void createFromContextWithApiKey() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder().apiKey("sk-test-key").build());
            assertNotNull(model);
            assertEquals("gpt-4o", model.getModelName());
        }

        @Test
        void createWithCustomBaseUrl() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder()
                                    .apiKey("sk-test-key")
                                    .baseUrl("https://custom.example.com")
                                    .build());
            assertNotNull(model);
            assertEquals("gpt-4o", model.getModelName());
        }

        @Test
        void missingApiKeyThrows() {
            String envKey = System.getenv("OPENAI_API_KEY");
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    envKey == null || envKey.isBlank(),
                    "OPENAI_API_KEY must not be set for this test");

            assertThrows(
                    IllegalStateException.class, () -> provider.create("openai-official:gpt-4o"));
        }

        @Test
        void unsupportedModelIdThrows() {
            assertThrows(IllegalArgumentException.class, () -> provider.create("openai:gpt-4o"));
        }
    }

    @Nested
    class StreamDefault {

        @Test
        @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
        void streamDefaultsToTrueWhenNotSet() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder().apiKey("sk-test-key").build());
            assertNotNull(model);
            assertTrue(model instanceof OpenAIResponsesChatModel);
        }
    }

    @Nested
    class AdvancedOptions {

        @Test
        void createWithGenerateOptionsComponent() {
            GenerateOptions gopts = GenerateOptions.builder().temperature(0.5).build();
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder()
                                    .apiKey("sk-test-key")
                                    .component(GenerateOptions.class, gopts)
                                    .build());
            assertNotNull(model);
            assertEquals("gpt-4o", model.getModelName());
        }

        @Test
        void createWithContextWindowSizeOption() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder()
                                    .apiKey("sk-test-key")
                                    .option("contextWindowSize", 8192)
                                    .build());
            assertNotNull(model);
            assertEquals(8192, model.getContextWindowSize());
        }

        @Test
        void createWithAdditionalHeadersOption() {
            Model model =
                    provider.create(
                            "openai-official:gpt-4o",
                            ModelCreationContext.builder()
                                    .apiKey("sk-test-key")
                                    .option("additionalHeaders", Map.of("X-Custom", "value"))
                                    .build());
            assertNotNull(model);
            assertEquals("gpt-4o", model.getModelName());
        }
    }
}
