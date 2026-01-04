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
package io.agentscope.extensions.scheduler.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ModelConfig}. */
class ModelConfigTest {

    @Test
    void testGetModelNameWithDashScopeImpl() {
        ModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        assertEquals("qwen-max", config.getModelName());
    }

    @Test
    void testCreateModelWithDashScopeImpl() {
        ModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-turbo").build();

        Model model = config.createModel();
        assertNotNull(model);
    }

    @Test
    void testInterfaceContract() {
        // Verify that DashScopeModelConfig implements ModelConfig
        ModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-plus").build();

        assertNotNull(config);
        assertNotNull(config.getModelName());
        assertNotNull(config.createModel());
    }
}
