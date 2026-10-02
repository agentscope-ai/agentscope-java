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
package io.agentscope.extensions.model.ollama;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OllamaModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsOllamaModelIds() {
        OllamaModelProvider provider = new OllamaModelProvider();
        assertTrue(provider.supports("ollama:llama3"));
        assertTrue(provider.supports("ollama:qwen2.5:14b-instruct"));
        assertFalse(provider.supports("ollama:"));
        assertFalse(provider.supports("llama3"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIds() {
        OllamaModelProvider provider = new OllamaModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("ollama:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("llama3"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        OllamaModelProvider provider = new OllamaModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .baseUrl("http://ollama.example.com")
                        .component(OllamaOptions.class, OllamaOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 128000)
                        .build();

        Model model = provider.create("ollama:llama3", context);

        assertTrue(model instanceof OllamaChatModel);
        assertTrue(model.getModelName().equals("llama3"));
        assertEquals(128000, model.getContextWindowSize());
    }

    @Test
    void modelRegistryFindsOllamaProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("ollama:llama3"));
    }
}
