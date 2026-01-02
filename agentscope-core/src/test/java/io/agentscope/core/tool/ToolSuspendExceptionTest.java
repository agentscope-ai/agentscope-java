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
package io.agentscope.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
@DisplayName("ToolSuspendException Tests")
class ToolSuspendExceptionTest {

    @Test
    @DisplayName("Should create exception with default message")
    void testDefaultConstructor() {
        ToolSuspendException exception = new ToolSuspendException();

        assertEquals("Tool execution suspended", exception.getMessage());
        assertNull(exception.getReason());
    }

    @Test
    @DisplayName("Should create exception with custom reason")
    void testCustomReason() {
        String reason = "Awaiting external API call";
        ToolSuspendException exception = new ToolSuspendException(reason);

        assertEquals(reason, exception.getMessage());
        assertEquals(reason, exception.getReason());
    }

    @Test
    @DisplayName("Should create exception with null reason")
    void testNullReason() {
        ToolSuspendException exception = new ToolSuspendException(null);

        assertEquals("Tool execution suspended", exception.getMessage());
        assertNull(exception.getReason());
    }

    @Test
    @DisplayName("Should create pending ToolResultBlock from exception")
    void testPendingToolResultBlock() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-1")
                        .name("external_api")
                        .input(Map.of("url", "https://api.example.com"))
                        .build();

        ToolSuspendException exception = new ToolSuspendException("Waiting for API response");

        ToolResultBlock pending = ToolResultBlock.pending(toolUse, exception);

        assertEquals("tool-1", pending.getId());
        assertEquals("external_api", pending.getName());
        assertTrue(pending.isPending());
        assertEquals(1, pending.getOutput().size());
    }

    @Test
    @DisplayName("Should create pending ToolResultBlock with default message")
    void testPendingToolResultBlockDefaultMessage() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-2")
                        .name("database_query")
                        .input(Map.of("sql", "SELECT * FROM users"))
                        .build();

        ToolResultBlock pending = ToolResultBlock.pending(toolUse);

        assertEquals("tool-2", pending.getId());
        assertEquals("database_query", pending.getName());
        assertTrue(pending.isPending());
    }

    @Test
    @DisplayName("Should correctly identify non-pending result")
    void testNonPendingResult() {
        ToolResultBlock normalResult = ToolResultBlock.text("Success");

        assertTrue(!normalResult.isPending());
    }
}
