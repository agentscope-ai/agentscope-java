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
package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ModelException.
 */
@Tag("unit")
@DisplayName("ModelException Unit Tests")
class ModelExceptionTest {

    @Test
    @DisplayName("Should create exception with message only")
    void testConstructorWithMessage() {
        String message = "Model request failed";
        ModelException exception = new ModelException(message);

        assertEquals(message, exception.getMessage());
        assertNull(exception.getModelName());
        assertNull(exception.getProvider());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create exception with message and cause")
    void testConstructorWithMessageAndCause() {
        String message = "API call failed";
        Throwable cause = new RuntimeException("Connection timeout");
        ModelException exception = new ModelException(message, cause);

        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertNull(exception.getModelName());
        assertNull(exception.getProvider());
    }

    @Test
    @DisplayName("Should create exception with message, model name and provider")
    void testConstructorWithModelInfo() {
        String message = "Generation failed";
        String modelName = "gpt-4";
        String provider = "OpenAI";
        ModelException exception = new ModelException(message, modelName, provider);

        assertEquals(message, exception.getMessage());
        assertEquals(modelName, exception.getModelName());
        assertEquals(provider, exception.getProvider());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create exception with all parameters")
    void testConstructorWithAllParameters() {
        String message = "Token limit exceeded";
        Throwable cause = new IllegalArgumentException("Too many tokens");
        String modelName = "qwen-plus";
        String provider = "DashScope";
        ModelException exception = new ModelException(message, cause, modelName, provider);

        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(modelName, exception.getModelName());
        assertEquals(provider, exception.getProvider());
    }

    @Test
    @DisplayName("Should format toString with model name and provider")
    void testToStringWithModelInfo() {
        ModelException exception = new ModelException("Error", "gpt-4", "OpenAI");
        String result = exception.toString();

        assertNotNull(result);
        assertTrue(result.contains("gpt-4"));
        assertTrue(result.contains("OpenAI"));
        assertTrue(result.contains("model="));
        assertTrue(result.contains("provider="));
    }

    @Test
    @DisplayName("Should format toString with model name only")
    void testToStringWithModelNameOnly() {
        ModelException exception = new ModelException("Error", "gpt-4", null);
        String result = exception.toString();

        assertNotNull(result);
        assertTrue(result.contains("gpt-4"));
        assertTrue(result.contains("model="));
    }

    @Test
    @DisplayName("Should format toString without model info")
    void testToStringWithoutModelInfo() {
        ModelException exception = new ModelException("Basic error");
        String result = exception.toString();

        assertNotNull(result);
        assertTrue(result.contains("Basic error"));
    }
}
