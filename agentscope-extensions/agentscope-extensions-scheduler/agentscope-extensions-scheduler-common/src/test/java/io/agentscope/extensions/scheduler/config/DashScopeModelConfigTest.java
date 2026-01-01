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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DashScopeModelConfig}. */
class DashScopeModelConfigTest {

    @Test
    void testBuilderWithRequiredFields() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        assertNotNull(config);
        assertEquals("test-key", config.getApiKey());
        assertEquals("qwen-max", config.getModelName());
        assertTrue(config.isStream());
    }

    @Test
    void testBuilderWithAllFields() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").stream(
                                false)
                        .enableThinking(true)
                        .baseUrl("http://custom.url")
                        .build();

        assertNotNull(config);
        assertEquals("test-key", config.getApiKey());
        assertEquals("qwen-max", config.getModelName());
        assertFalse(config.isStream());
        assertTrue(config.getEnableThinking());
        assertEquals("http://custom.url", config.getBaseUrl());
    }

    @Test
    void testGetModelName() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-turbo").build();

        assertEquals("qwen-turbo", config.getModelName());
    }

    @Test
    void testCreateModel() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        Model model = config.createModel();
        assertNotNull(model);
    }

    @Test
    void testGetApiKey() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("my-api-key").modelName("qwen-max").build();

        assertEquals("my-api-key", config.getApiKey());
    }

    @Test
    void testIsStream() {
        DashScopeModelConfig config1 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").stream(true)
                        .build();
        assertTrue(config1.isStream());

        DashScopeModelConfig config2 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").stream(
                                false)
                        .build();
        assertFalse(config2.isStream());
    }

    @Test
    void testGetEnableThinking() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder()
                        .apiKey("test-key")
                        .modelName("qwen-max")
                        .enableThinking(true)
                        .build();

        assertTrue(config.getEnableThinking());
    }

    @Test
    void testGetBaseUrl() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder()
                        .apiKey("test-key")
                        .modelName("qwen-max")
                        .baseUrl("http://test.url")
                        .build();

        assertEquals("http://test.url", config.getBaseUrl());
    }

    @Test
    void testValidationWithNullApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeModelConfig.builder().apiKey(null).modelName("qwen-max").build());
    }

    @Test
    void testValidationWithEmptyApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeModelConfig.builder().apiKey("").modelName("qwen-max").build());
    }

    @Test
    void testValidationWithNullModelName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeModelConfig.builder().apiKey("test-key").modelName(null).build());
    }

    @Test
    void testValidationWithEmptyModelName() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DashScopeModelConfig.builder().apiKey("test-key").modelName("").build());
    }

    @Test
    void testEquals() {
        DashScopeModelConfig config1 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        DashScopeModelConfig config2 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        DashScopeModelConfig config3 =
                DashScopeModelConfig.builder()
                        .apiKey("different-key")
                        .modelName("qwen-max")
                        .build();

        assertEquals(config1, config2);
        assertNotEquals(config1, config3);
    }

    @Test
    void testHashCode() {
        DashScopeModelConfig config1 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        DashScopeModelConfig config2 =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    void testToString() {
        DashScopeModelConfig config =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("qwen-max"));
        assertTrue(str.contains("DashScopeModelConfig"));
    }
}
