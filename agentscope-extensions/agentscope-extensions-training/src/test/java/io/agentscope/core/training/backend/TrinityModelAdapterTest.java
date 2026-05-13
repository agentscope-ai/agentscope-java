/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.training.runner.RunExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for TrinityModelAdapter.
 */
@DisplayName("TrinityModelAdapter Tests")
class TrinityModelAdapterTest {

    @Test
    @DisplayName("Should build TrinityModelAdapter with all parameters")
    void shouldBuildWithAllParameters() {
        RunExecutionContext context = RunExecutionContext.create("task-1", "0");

        TrinityModelAdapter adapter =
                TrinityModelAdapter.builder()
                        .baseUrl("http://localhost:8080/v1")
                        .modelName("test-model")
                        .apiKey("test-api-key")
                        .executionContext(context)
                        .build();

        assertNotNull(adapter);
        assertEquals("test-model", adapter.getModelName());
        assertEquals(context, adapter.getExecutionContext());
    }

    @Test
    @DisplayName("Should build TrinityModelAdapter with default apiKey")
    void shouldBuildWithDefaultApiKey() {
        TrinityModelAdapter adapter =
                TrinityModelAdapter.builder()
                        .baseUrl("http://localhost:8080/v1")
                        .modelName("test-model")
                        .build();

        assertNotNull(adapter);
        assertEquals("test-model", adapter.getModelName());
    }

    @Test
    @DisplayName("Should build TrinityModelAdapter without execution context")
    void shouldBuildWithoutExecutionContext() {
        TrinityModelAdapter adapter =
                TrinityModelAdapter.builder()
                        .baseUrl("http://localhost:8080/v1")
                        .modelName("test-model")
                        .apiKey("api-key")
                        .build();

        assertNotNull(adapter);
        assertNull(adapter.getExecutionContext());
    }

    @Test
    @DisplayName("Should return correct model name")
    void shouldReturnCorrectModelName() {
        TrinityModelAdapter adapter =
                TrinityModelAdapter.builder()
                        .baseUrl("http://localhost:8080/v1")
                        .modelName("custom-model-name")
                        .build();

        assertEquals("custom-model-name", adapter.getModelName());
    }

    @Test
    @DisplayName("Should return execution context when set")
    void shouldReturnExecutionContextWhenSet() {
        RunExecutionContext context = RunExecutionContext.create("task-abc", "2");

        TrinityModelAdapter adapter =
                TrinityModelAdapter.builder()
                        .baseUrl("http://localhost:8080/v1")
                        .modelName("model")
                        .executionContext(context)
                        .build();

        RunExecutionContext retrievedContext = adapter.getExecutionContext();

        assertNotNull(retrievedContext);
        assertEquals("task-abc", retrievedContext.getTaskId());
        assertEquals("2", retrievedContext.getRunId());
    }

    @Test
    @DisplayName("Should create builder successfully")
    void shouldCreateBuilderSuccessfully() {
        TrinityModelAdapter.Builder builder = TrinityModelAdapter.builder();
        assertNotNull(builder);
    }
}
