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
package io.agentscope.extensions.model.openai.compat.minimax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MiniMaxModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsMiniMaxModelIds() {
        MiniMaxModelProvider provider = new MiniMaxModelProvider();
        assertTrue(provider.supports("minimax:MiniMax-M2"));
        assertFalse(provider.supports("minimax:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        MiniMaxModelProvider provider = new MiniMaxModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("minimax:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("MiniMax-M2"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createRequiresApiKey() {
        assumeTrue(
                System.getenv("MINIMAX_API_KEY") == null
                        || System.getenv("MINIMAX_API_KEY").isBlank(),
                "MINIMAX_API_KEY is set, so provider can create a model from the environment");
        MiniMaxModelProvider provider = new MiniMaxModelProvider();

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> provider.create("minimax:MiniMax-M2", ModelCreationContext.empty()));

        assertTrue(exception.getMessage().contains("ModelCreationContext.apiKey"));
        assertTrue(exception.getMessage().contains("MINIMAX_API_KEY"));
    }

    @Test
    void createDisablesNativeStructuredOutputByDefault() {
        MiniMaxModelProvider provider = new MiniMaxModelProvider();

        Model model =
                provider.create(
                        "minimax:MiniMax-M2",
                        ModelCreationContext.builder().apiKey("test-minimax-key").build());

        assertFalse(model.supportsNativeStructuredOutput());
        assertFalse(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void createAllowsNativeStructuredOutputOverride() {
        MiniMaxModelProvider provider = new MiniMaxModelProvider();

        Model model =
                provider.create(
                        "minimax:MiniMax-M2",
                        ModelCreationContext.builder()
                                .apiKey("test-minimax-key")
                                .option("nativeStructuredOutput", true)
                                .option("nativeStructuredOutputWithTools", true)
                                .build());

        assertTrue(model.supportsNativeStructuredOutput());
        assertTrue(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void createUsesModelCreationContext() {
        MiniMaxModelProvider provider = new MiniMaxModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-minimax-key")
                        .baseUrl("https://minimax.example.com/v1")
                        .endpointPath("/v1/chat/completions")
                        .stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 245760)
                        .option("nativeStructuredOutput", false)
                        .option("nativeStructuredOutputWithTools", false)
                        .build();

        Model model = provider.create("minimax:MiniMax-M2", context);

        assertTrue(model instanceof OpenAIChatModel);
        assertEquals("MiniMax-M2", model.getModelName());
        assertEquals(245760, model.getContextWindowSize());
        assertFalse(model.supportsNativeStructuredOutput());
        assertFalse(model.supportsNativeStructuredOutputWithTools());
    }

    @Test
    void modelRegistryFindsMiniMaxProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("minimax:MiniMax-M2"));
    }
}
