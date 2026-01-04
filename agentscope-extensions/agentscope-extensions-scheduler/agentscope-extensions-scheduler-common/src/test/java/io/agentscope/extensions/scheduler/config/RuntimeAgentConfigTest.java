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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RuntimeAgentConfig}. */
class RuntimeAgentConfigTest {

    private ModelConfig createTestModelConfig() {
        return DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
    }

    @Test
    void testBuilderWithRequiredFields() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertNotNull(config);
        assertEquals("TestAgent", config.getName());
        assertEquals(modelConfig, config.getModelConfig());
        assertNotNull(config.getModel());
    }

    @Test
    void testBuilderWithAllFields() {
        ModelConfig modelConfig = createTestModelConfig();
        Toolkit toolkit = new Toolkit();
        List<Hook> hooks = new ArrayList<>();

        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a helpful assistant")
                        .toolkit(toolkit)
                        .hooks(hooks)
                        .build();

        assertNotNull(config);
        assertEquals("TestAgent", config.getName());
        assertEquals(modelConfig, config.getModelConfig());
        assertEquals("You are a helpful assistant", config.getSysPrompt());
        assertEquals(toolkit, config.getToolkit());
        assertNotNull(config.getHooks());
        assertNotNull(config.getModel());
    }

    @Test
    void testGetToolkit() {
        ModelConfig modelConfig = createTestModelConfig();
        Toolkit toolkit = new Toolkit();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .toolkit(toolkit)
                        .build();

        assertEquals(toolkit, config.getToolkit());
    }

    @Test
    void testGetToolkitWhenNull() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertNull(config.getToolkit());
    }

    @Test
    void testGetHooks() {
        ModelConfig modelConfig = createTestModelConfig();
        List<Hook> hooks = new ArrayList<>();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .hooks(hooks)
                        .build();

        assertNotNull(config.getHooks());
        assertEquals(0, config.getHooks().size());
    }

    @Test
    void testGetHooksWhenNull() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertNotNull(config.getHooks());
        assertEquals(0, config.getHooks().size());
    }

    @Test
    void testGetModel() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        Model model = config.getModel();
        assertNotNull(model);
    }

    @Test
    void testToString() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("TestAgent"));
        assertTrue(str.contains("RuntimeAgentConfig"));
    }

    @Test
    void testInheritanceFromAgentConfig() {
        ModelConfig modelConfig = createTestModelConfig();
        RuntimeAgentConfig config =
                RuntimeAgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertTrue(config instanceof AgentConfig);
    }
}
