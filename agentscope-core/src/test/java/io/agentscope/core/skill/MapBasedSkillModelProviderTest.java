/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.model.Model;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MapBasedSkillModelProviderTest {

    @Nested
    @DisplayName("Direct Name Lookup")
    class DirectNameLookupTests {

        @Test
        @DisplayName("Should return model by direct name")
        void shouldReturnModelByDirectName() {
            Model qwenTurboModel = mock(Model.class);
            when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("qwen-turbo", qwenTurboModel)
                            .build();

            Model result = provider.getModel("qwen-turbo");
            assertEquals(qwenTurboModel, result);
        }

        @Test
        @DisplayName("Should return model with provider:model format")
        void shouldResolveProviderFormat() {
            Model qwenPlusModel = mock(Model.class);
            when(qwenPlusModel.getModelName()).thenReturn("qwen-plus");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("dashscope:qwen-plus", qwenPlusModel)
                            .build();

            Model result = provider.getModel("dashscope:qwen-plus");
            assertEquals(qwenPlusModel, result);
        }
    }

    @Nested
    @DisplayName("Alias Resolution")
    class AliasResolutionTests {

        @Test
        @DisplayName("Should resolve alias to registered model")
        void shouldResolveAlias() {
            Model qwenTurboModel = mock(Model.class);
            when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("qwen-turbo", qwenTurboModel)
                            .alias("qwen-turbo", "qwen-turbo")
                            .build();

            Model result = provider.getModel("qwen-turbo");
            assertEquals(qwenTurboModel, result);
        }

        @Test
        @DisplayName("Should resolve multiple aliases to same model")
        void shouldResolveMultipleAliases() {
            Model qwenTurboModel = mock(Model.class);
            when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("qwen-turbo", qwenTurboModel)
                            .alias("qwen-turbo", "qwen-turbo")
                            .alias("qwen-turbo-latest", "qwen-turbo")
                            .alias("fast-model", "qwen-turbo")
                            .build();

            assertEquals(qwenTurboModel, provider.getModel("qwen-turbo"));
            assertEquals(qwenTurboModel, provider.getModel("qwen-turbo-latest"));
            assertEquals(qwenTurboModel, provider.getModel("fast-model"));
        }

        @Test
        @DisplayName("Should prefer direct match over alias")
        void shouldPreferDirectMatchOverAlias() {
            Model directModel = mock(Model.class);
            when(directModel.getModelName()).thenReturn("direct");
            Model aliasTargetModel = mock(Model.class);
            when(aliasTargetModel.getModelName()).thenReturn("alias-target");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("qwen-turbo", directModel)
                            .register("qwen-max", aliasTargetModel)
                            .alias("fast-model", "qwen-max")
                            .build();

            // Direct registration should take precedence
            Model result = provider.getModel("qwen-turbo");
            assertEquals(directModel, result);
        }
    }

    @Nested
    @DisplayName("Default Model Fallback")
    class DefaultModelFallbackTests {

        @Test
        @DisplayName("Should return default model when not found")
        void shouldReturnDefaultModelWhenNotFound() {
            Model defaultModel = mock(Model.class);
            when(defaultModel.getModelName()).thenReturn("default");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().defaultModel(defaultModel).build();

            Model result = provider.getModel("unknown");
            assertEquals(defaultModel, result);
        }

        @Test
        @DisplayName("Should return null when no default and not found")
        void shouldReturnNullWhenNoDefaultAndNotFound() {
            MapBasedSkillModelProvider provider = MapBasedSkillModelProvider.builder().build();

            Model result = provider.getModel("unknown");
            assertNull(result);
        }

        @Test
        @DisplayName("Should return default for null reference")
        void shouldReturnDefaultForNullRef() {
            Model defaultModel = mock(Model.class);

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().defaultModel(defaultModel).build();

            assertEquals(defaultModel, provider.getModel(null));
        }

        @Test
        @DisplayName("Should return default for empty reference")
        void shouldReturnDefaultForEmptyRef() {
            Model defaultModel = mock(Model.class);

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().defaultModel(defaultModel).build();

            assertEquals(defaultModel, provider.getModel(""));
        }

        @Test
        @DisplayName("Should return default for blank reference")
        void shouldReturnDefaultForBlankRef() {
            Model defaultModel = mock(Model.class);

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().defaultModel(defaultModel).build();

            assertEquals(defaultModel, provider.getModel("  "));
        }
    }

    @Nested
    @DisplayName("isAvailable Method")
    class IsAvailableTests {

        @Test
        @DisplayName("Should return true for registered model")
        void shouldReturnTrueForRegisteredModel() {
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("test-model");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().register("test", model).build();

            assertTrue(provider.isAvailable("test"));
        }

        @Test
        @DisplayName("Should return true for aliased model")
        void shouldReturnTrueForAliasedModel() {
            Model model = mock(Model.class);
            when(model.getModelName()).thenReturn("test-model");

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder()
                            .register("full-name", model)
                            .alias("short", "full-name")
                            .build();

            assertTrue(provider.isAvailable("short"));
        }

        @Test
        @DisplayName("Should return false for unknown model without default")
        void shouldReturnFalseForUnknownWithoutDefault() {
            MapBasedSkillModelProvider provider = MapBasedSkillModelProvider.builder().build();

            assertFalse(provider.isAvailable("unknown"));
        }

        @Test
        @DisplayName("Should return true for unknown model with default")
        void shouldReturnTrueForUnknownWithDefault() {
            Model defaultModel = mock(Model.class);

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().defaultModel(defaultModel).build();

            assertTrue(provider.isAvailable("unknown"));
        }
    }

    @Nested
    @DisplayName("registerAll Method")
    class RegisterAllTests {

        @Test
        @DisplayName("Should register multiple models at once")
        void shouldRegisterMultipleModels() {
            Model qwenTurboModel = mock(Model.class);
            Model qwenPlusModel = mock(Model.class);
            when(qwenTurboModel.getModelName()).thenReturn("qwen-turbo");
            when(qwenPlusModel.getModelName()).thenReturn("qwen-plus");

            Map<String, Model> models = new HashMap<>();
            models.put("qwen-turbo", qwenTurboModel);
            models.put("qwen-plus", qwenPlusModel);

            MapBasedSkillModelProvider provider =
                    MapBasedSkillModelProvider.builder().registerAll(models).build();

            assertEquals(qwenTurboModel, provider.getModel("qwen-turbo"));
            assertEquals(qwenPlusModel, provider.getModel("qwen-plus"));
        }
    }
}
