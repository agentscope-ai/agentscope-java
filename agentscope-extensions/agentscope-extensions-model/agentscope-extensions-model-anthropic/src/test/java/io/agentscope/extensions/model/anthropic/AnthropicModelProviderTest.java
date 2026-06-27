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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ModelRegistry;
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
    void modelRegistryFindsAnthropicProviderFromServiceLoader() {
        assertTrue(ModelRegistry.canResolve("anthropic:claude-sonnet-4.5"));
    }
}
