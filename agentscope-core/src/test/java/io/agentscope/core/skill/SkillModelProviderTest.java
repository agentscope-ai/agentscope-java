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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SkillModelProvider Tests")
class SkillModelProviderTest {

    @Test
    @DisplayName("Should return model from provider")
    void shouldReturnModelFromProvider() {
        Model qwenTurboModel = mock(Model.class);
        when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");

        SkillModelProvider provider =
                modelRef -> "qwen-turbo".equals(modelRef) ? qwenTurboModel : null;

        Model result = provider.getModel("qwen-turbo");
        assertNotNull(result);
        assertEquals("qwen-turbo", result.getModelName());
    }

    @Test
    @DisplayName("Should return null when model not found")
    void shouldReturnNullWhenModelNotFound() {
        SkillModelProvider provider = modelRef -> null;

        Model result = provider.getModel("unknown");
        assertNull(result);
    }

    @Test
    @DisplayName("Should check availability")
    void shouldCheckAvailability() {
        Model qwenTurboModel = mock(Model.class);
        SkillModelProvider provider =
                modelRef -> "qwen-turbo".equals(modelRef) ? qwenTurboModel : null;

        assertTrue(provider.isAvailable("qwen-turbo"));
        assertFalse(provider.isAvailable("unknown"));
    }
}
