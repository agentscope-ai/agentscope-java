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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ModelRegistry;
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
    void modelRegistryFindsDashScopeProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("dashscope:qwen-max"));
        assertTrue(ModelRegistry.canResolve("qwen-max"));
    }
}
