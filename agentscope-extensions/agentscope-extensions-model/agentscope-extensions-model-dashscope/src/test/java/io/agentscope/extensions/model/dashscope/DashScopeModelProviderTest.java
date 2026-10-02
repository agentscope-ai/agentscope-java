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
package io.agentscope.extensions.model.dashscope;

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

class DashScopeModelProviderTest {

    @AfterEach
    void tearDown() {
        ModelRegistry.reset();
    }

    @Test
    void supportsDashScopeModelIdsAndQwenShortNames() {
        DashScopeModelProvider provider = new DashScopeModelProvider();
        assertTrue(provider.supports("dashscope:qwen-max"));
        assertTrue(provider.supports("qwen-max"));
        assertTrue(provider.supports("qwen3.7-plus"));
        assertFalse(provider.supports("dashscope:"));
        assertFalse(provider.supports("qwen"));
        assertFalse(provider.supports("openai:gpt-4o-mini"));
    }

    @Test
    void createRejectsUnsupportedModelIdsBeforeReadingEnvironment() {
        DashScopeModelProvider provider = new DashScopeModelProvider();

        assertThrows(IllegalArgumentException.class, () -> provider.create("qwen"));
        assertThrows(IllegalArgumentException.class, () -> provider.create("dashscope:"));
        assertThrows(IllegalArgumentException.class, () -> provider.create(null));
    }

    @Test
    void createUsesModelCreationContext() {
        DashScopeModelProvider provider = new DashScopeModelProvider();
        ModelCreationContext context =
                ModelCreationContext.builder()
                        .apiKey("test-dashscope-key")
                        .baseUrl("https://dashscope.example.com")
                        .stream(false)
                        .enableThinking(true)
                        .component(GenerateOptions.class, GenerateOptions.builder().build())
                        .component(ProxyConfig.class, ProxyConfig.http("localhost", 8080))
                        .option("contextWindowSize", 128000)
                        .option("enableSearch", true)
                        .option("endpointType", EndpointType.TEXT)
                        .option("nativeStructuredOutputWithTools", true)
                        .build();

        Model model = provider.create("dashscope:qwen-max", context);

        assertTrue(model instanceof DashScopeChatModel);
        assertTrue(model.getModelName().equals("qwen-max"));
        assertEquals(128000, model.getContextWindowSize());
    }

    @Test
    void modelRegistryFindsDashScopeProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("dashscope:qwen-max"));
        assertTrue(ModelRegistry.canResolve("qwen-max"));
    }
}
