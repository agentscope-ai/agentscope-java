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
package io.agentscope.extensions.model.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AnthropicModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsAnthropicModelIds() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        assertTrue(provider.supports("anthropic:claude-sonnet-4.5"));
        assertFalse(provider.supports("anthropic:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        AnthropicModelProvider provider = new AnthropicModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("anthropic:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("claude-sonnet-4.5"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        AnthropicModelProvider provider = new AnthropicModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-anthropic-key")
                        .baseUrl("https://anthropic.example.com")
                        .stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 200000)
                        .build();

        Model model = provider.create("anthropic:claude-sonnet-4.5", context);

        assertTrue(model instanceof AnthropicChatModel);
        assertTrue(model.getModelName().equals("claude-sonnet-4.5"));
        assertEquals(200000, model.getContextWindowSize());
    }

    @Test
    void modelRegistryFindsAnthropicProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("anthropic:claude-sonnet-4.5"));
    }
}
