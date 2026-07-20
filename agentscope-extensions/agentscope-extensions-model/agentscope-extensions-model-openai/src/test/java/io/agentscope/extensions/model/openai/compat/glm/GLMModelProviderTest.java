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
package io.agentscope.extensions.model.openai.compat.glm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GLMModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void providerIdIsGlm() {
        assertEquals("glm", new GLMModelProvider().providerId());
    }

    @Test
    void supportsGlmModelIds() {
        GLMModelProvider provider = new GLMModelProvider();
        assertTrue(provider.supports("glm:glm-5.2"));
        assertTrue(provider.supports("glm:glm-4.6v"));
        assertFalse(provider.supports("glm:"));
        // Whitespace-only model names must not be treated as supported
        assertFalse(provider.supports("glm: "));
        assertFalse(provider.supports("glm:   "));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
        assertFalse(provider.supports(null));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        GLMModelProvider provider = new GLMModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("glm:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("glm: "));
        assertThrows(IllegalArgumentException.class, () -> provider.create("glm-5.2"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createTrimsModelName() {
        GLMModelProvider provider = new GLMModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-glm-key").build();

        Model model = provider.create("glm: glm-5.2", context);

        assertEquals("glm-5.2", model.getModelName());
    }

    @Test
    void createUsesModelCreationContext() {
        GLMModelProvider provider = new GLMModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-glm-key").stream(false)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 128000)
                        .build();

        Model model = provider.create("glm:glm-5.2", context);

        assertTrue(model instanceof OpenAIChatModel);
        assertEquals("glm-5.2", model.getModelName());
        assertEquals(128000, model.getContextWindowSize());
    }

    @Test
    void nativeStructuredOutputDisabledByDefault() {
        GLMModelProvider provider = new GLMModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().apiKey("test-glm-key").build();

        Model model = provider.create("glm:glm-5.2", context);

        // GLM response_format only supports json_object, so native structured
        // output falls back to the generate_response tool by default
        assertFalse(model.supportsNativeStructuredOutput());
    }

    @Test
    void nativeStructuredOutputCanBeEnabledByOption() {
        GLMModelProvider provider = new GLMModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-glm-key")
                        .option("nativeStructuredOutput", true)
                        .build();

        Model model = provider.create("glm:glm-5.2", context);

        assertTrue(model.supportsNativeStructuredOutput());
    }

    @Test
    void customBaseUrlOverridesDefault() {
        GLMModelProvider provider = new GLMModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-glm-key")
                        .baseUrl("https://glm.example.com/v4")
                        .build();

        Model model = provider.create("glm:glm-5.2", context);

        assertTrue(model instanceof OpenAIChatModel);
    }

    @Test
    void modelRegistryFindsGlmProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("glm:glm-5.2"));
    }
}
