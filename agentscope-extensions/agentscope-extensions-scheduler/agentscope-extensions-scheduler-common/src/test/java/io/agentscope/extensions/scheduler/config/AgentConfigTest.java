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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgentConfig}. */
class AgentConfigTest {

    private ModelConfig createTestModelConfig() {
        return DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
    }

    @Test
    void testBuilderWithRequiredFields() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertNotNull(config);
        assertEquals("TestAgent", config.getName());
        assertEquals(modelConfig, config.getModelConfig());
    }

    @Test
    void testBuilderWithAllFields() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a helpful assistant")
                        .build();

        assertNotNull(config);
        assertEquals("TestAgent", config.getName());
        assertEquals(modelConfig, config.getModelConfig());
        assertEquals("You are a helpful assistant", config.getSysPrompt());
    }

    @Test
    void testGetName() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config = AgentConfig.builder().name("MyAgent").modelConfig(modelConfig).build();

        assertEquals("MyAgent", config.getName());
    }

    @Test
    void testGetModelConfig() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertEquals(modelConfig, config.getModelConfig());
    }

    @Test
    void testGetSysPrompt() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        assertEquals("Test prompt", config.getSysPrompt());
    }

    @Test
    void testGetSysPromptWhenNull() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertNull(config.getSysPrompt());
    }

    @Test
    void testValidationWithNullName() {
        ModelConfig modelConfig = createTestModelConfig();
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentConfig.builder().name(null).modelConfig(modelConfig).build());
    }

    @Test
    void testValidationWithEmptyName() {
        ModelConfig modelConfig = createTestModelConfig();
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentConfig.builder().name("").modelConfig(modelConfig).build());
    }

    @Test
    void testValidationWithWhitespaceName() {
        ModelConfig modelConfig = createTestModelConfig();
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentConfig.builder().name("   ").modelConfig(modelConfig).build());
    }

    @Test
    void testValidationWithNullModelConfig() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentConfig.builder().name("TestAgent").modelConfig(null).build());
    }

    @Test
    void testEquals() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config1 =
                AgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("prompt")
                        .build();
        AgentConfig config2 =
                AgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("prompt")
                        .build();
        AgentConfig config3 =
                AgentConfig.builder()
                        .name("DifferentAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("prompt")
                        .build();

        assertEquals(config1, config2);
        assertNotEquals(config1, config3);
    }

    @Test
    void testHashCode() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config1 =
                AgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();
        AgentConfig config2 =
                AgentConfig.builder().name("TestAgent").modelConfig(modelConfig).build();

        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testToString() {
        ModelConfig modelConfig = createTestModelConfig();
        AgentConfig config =
                AgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("TestAgent"));
        assertTrue(str.contains("AgentConfig"));
    }
}
