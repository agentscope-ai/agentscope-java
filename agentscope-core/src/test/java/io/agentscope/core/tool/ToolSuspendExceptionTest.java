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
import io.agentscope.core.message.ToolResultState;
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
    @DisplayName("Should create suspended ToolResultBlock from exception")
    void testSuspendedToolResultBlock() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-1")
                        .name("external_api")
                        .input(Map.of("url", "https://api.example.com"))
                        .build();

        ToolSuspendException exception = new ToolSuspendException("Waiting for API response");

        ToolResultBlock suspended = ToolResultBlock.suspended(toolUse, exception);

        assertEquals("tool-1", suspended.getId());
        assertEquals("external_api", suspended.getName());
        assertTrue(suspended.isSuspended());
        assertEquals(ToolResultState.SUSPENDED, suspended.getState());
        assertTrue(suspended.getOutput().isEmpty(), "suspended result must not fabricate output");
        assertEquals("Waiting for API response", suspended.getSuspendReason());
    }

    @Test
    @DisplayName("Should create suspended ToolResultBlock with default message")
    void testSuspendedToolResultBlockDefaultMessage() {
        ToolUseBlock toolUse =
                ToolUseBlock.builder()
                        .id("tool-2")
                        .name("database_query")
                        .input(Map.of("sql", "SELECT * FROM users"))
                        .build();

        ToolResultBlock suspended = ToolResultBlock.suspended(toolUse);

        assertEquals("tool-2", suspended.getId());
        assertEquals("database_query", suspended.getName());
        assertTrue(suspended.isSuspended());
        assertEquals(ToolResultState.SUSPENDED, suspended.getState());
        assertTrue(suspended.getOutput().isEmpty(), "suspended result must not fabricate output");
        assertNull(suspended.getSuspendReason());
    }

    @Test
    @DisplayName("Should recognise suspended results by state and by legacy metadata")
    @SuppressWarnings("deprecation")
    void testSuspendedRecognitionAcceptsStateAndLegacyMetadata() {
        ToolResultBlock canonical =
                ToolResultBlock.builder()
                        .id("tool-3")
                        .name("external_api")
                        .state(ToolResultState.SUSPENDED)
                        .build();
        assertTrue(canonical.isSuspended(), "the SUSPENDED state is the canonical encoding");
        assertNull(canonical.getSuspendReason());

        ToolResultBlock legacy =
                ToolResultBlock.builder()
                        .id("tool-3")
                        .name("external_api")
                        .metadata(Map.of(ToolResultBlock.METADATA_SUSPENDED, true))
                        .build();
        assertEquals(
                ToolResultState.RUNNING,
                legacy.getState(),
                "legacy blocks deserialize without a state and default to RUNNING");
        assertTrue(legacy.isSuspended(), "the legacy wire marker must stay recognised");
    }

    @Test
    @DisplayName("Should correctly identify non-suspended result")
    void testNonSuspendedResult() {
        ToolResultBlock normalResult = ToolResultBlock.text("Success");

        assertTrue(!normalResult.isSuspended());
    }
}
