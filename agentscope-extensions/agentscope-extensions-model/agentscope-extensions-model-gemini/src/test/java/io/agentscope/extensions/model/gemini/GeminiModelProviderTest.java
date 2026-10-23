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
package io.agentscope.extensions.model.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.types.HttpOptions;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GeminiModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsGeminiModelIds() {
        GeminiModelProvider provider = new GeminiModelProvider();
        assertTrue(provider.supports("gemini:gemini-2.0-flash"));
        assertFalse(provider.supports("gemini:"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        GeminiModelProvider provider = new GeminiModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("gemini:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("gemini-2.0-flash"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesGeminiApiModelCreationContext() {
        GeminiModelProvider provider = new GeminiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-gemini-key")
                        .baseUrl("https://gemini.example.com")
                        .stream(false)
                        .component(HttpOptions.class, HttpOptions.builder().build())
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 1000000)
                        .option("vertexAI", false)
                        .build();

        Model model = provider.create("gemini:gemini-2.0-flash", context);

        assertTrue(model instanceof GeminiChatModel);
        assertTrue(model.getModelName().equals("gemini-2.0-flash"));
    }

    @Test
    void createUsesVertexAiModelCreationContext() {
        GeminiModelProvider provider = new GeminiModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder().stream(false)
                        .component(GoogleCredentials.class, GoogleCredentials.create(null))
                        .option("contextWindowSize", 128000)
                        .option("project", "test-project")
                        .option("location", "us-central1")
                        .option("vertexAI", true)
                        .build();

        Model model = provider.create("gemini:gemini-2.0-flash", context);

        assertTrue(model instanceof GeminiChatModel);
        assertTrue(model.getModelName().equals("gemini-2.0-flash"));
        assertEquals(128000, model.getContextWindowSize());
    }

    @Test
    void modelRegistryFindsGeminiProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("gemini:gemini-2.0-flash"));
    }
}
