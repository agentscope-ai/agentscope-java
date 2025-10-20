/*
 * Copyright 2024-2025 the original author or authors.
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
package io.agentscope.core.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolInterruptedException.
 */
@DisplayName("ToolInterruptedException Tests")
class ToolInterruptedExceptionTest {

    @Test
    @DisplayName("Should create exception with message and USER source")
    void testExceptionWithUserSource() {
        String message = "Tool interrupted by user";
        ToolInterruptedException exception =
                new ToolInterruptedException(message, InterruptSource.USER);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(InterruptSource.USER, exception.getSource());
        assertNull(exception.getToolName());
    }

    @Test
    @DisplayName("Should create exception with message and TOOL source")
    void testExceptionWithToolSource() {
        String message = "Tool interrupted itself";
        ToolInterruptedException exception =
                new ToolInterruptedException(message, InterruptSource.TOOL);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(InterruptSource.TOOL, exception.getSource());
    }

    @Test
    @DisplayName("Should create exception with message and SYSTEM source")
    void testExceptionWithSystemSource() {
        String message = "Tool interrupted by system timeout";
        ToolInterruptedException exception =
                new ToolInterruptedException(message, InterruptSource.SYSTEM);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(InterruptSource.SYSTEM, exception.getSource());
    }

    @Test
    @DisplayName("Should create exception with tool name")
    void testExceptionWithToolName() {
        String message = "Tool execution failed";
        String toolName = "weather_tool";
        ToolInterruptedException exception =
                new ToolInterruptedException(message, InterruptSource.TOOL, toolName);

        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(InterruptSource.TOOL, exception.getSource());
        assertEquals(toolName, exception.getToolName());
    }

    @Test
    @DisplayName("Should get user message for USER source")
    void testGetUserMessageForUserSource() {
        ToolInterruptedException exception =
                new ToolInterruptedException("Interrupted", InterruptSource.USER);

        String userMessage = exception.getUserMessage();
        assertNotNull(userMessage);
        assertTrue(userMessage.contains("user"));
    }

    @Test
    @DisplayName("Should get user message for TOOL source with tool name")
    void testGetUserMessageForToolSource() {
        ToolInterruptedException exception =
                new ToolInterruptedException("Interrupted", InterruptSource.TOOL, "weather_tool");

        String userMessage = exception.getUserMessage();
        assertNotNull(userMessage);
        assertTrue(userMessage.contains("weather_tool"));
        assertTrue(userMessage.contains("interrupted"));
    }

    @Test
    @DisplayName("Should get user message for SYSTEM source")
    void testGetUserMessageForSystemSource() {
        ToolInterruptedException exception =
                new ToolInterruptedException("Timeout", InterruptSource.SYSTEM);

        String userMessage = exception.getUserMessage();
        assertNotNull(userMessage);
        assertTrue(userMessage.contains("system"));
    }

    @Test
    @DisplayName("Should be instance of AgentScopeException")
    void testExceptionHierarchy() {
        ToolInterruptedException exception =
                new ToolInterruptedException("Test", InterruptSource.USER);

        assertTrue(exception instanceof AgentScopeException);
        assertTrue(exception instanceof RuntimeException);
    }

    @Test
    @DisplayName("Should handle null tool name gracefully")
    void testNullToolName() {
        ToolInterruptedException exception =
                new ToolInterruptedException("Interrupted", InterruptSource.TOOL, null);

        assertNotNull(exception);
        assertNull(exception.getToolName());
        assertNotNull(exception.getUserMessage());
    }
}
