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
package io.agentscope.extensions.model.openai;

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

class OpenAIModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsOpenAiModelIds() {
        OpenAIModelProvider provider = new OpenAIModelProvider();
        assertTrue(provider.supports("openai:gpt-4o-mini"));
        assertFalse(provider.supports("openai:"));
        assertFalse(provider.supports("dashscope:qwen-max"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        OpenAIModelProvider provider = new OpenAIModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("openai:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("gpt-4o-mini"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        OpenAIModelProvider provider = new OpenAIModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-openai-key")
                        .baseUrl("https://openai.example.com/v1")
                        .endpointPath("/v4/chat/completions")
                        .stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 128000)
                        .option("nativeStructuredOutput", false)
                        .option("nativeStructuredOutputWithTools", true)
                        .build();

        Model model = provider.create("openai:gpt-4o-mini", context);

        assertTrue(model instanceof OpenAIChatModel);
        assertTrue(model.getModelName().equals("gpt-4o-mini"));
        assertEquals(128000, model.getContextWindowSize());
        assertFalse(model.supportsNativeStructuredOutput());
    }

    @Test
    void modelRegistryFindsOpenAiProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("openai:gpt-4o-mini"));
    }
}
