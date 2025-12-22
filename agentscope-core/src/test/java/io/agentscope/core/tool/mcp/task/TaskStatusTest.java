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
package io.agentscope.core.tool.mcp.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    void testGetValue() {
        assertEquals("working", TaskStatus.WORKING.getValue());
        assertEquals("input_required", TaskStatus.INPUT_REQUIRED.getValue());
        assertEquals("completed", TaskStatus.COMPLETED.getValue());
        assertEquals("failed", TaskStatus.FAILED.getValue());
        assertEquals("cancelled", TaskStatus.CANCELLED.getValue());
    }

    @Test
    void testFromValue_ValidValues() {
        assertEquals(TaskStatus.WORKING, TaskStatus.fromValue("working"));
        assertEquals(TaskStatus.INPUT_REQUIRED, TaskStatus.fromValue("input_required"));
        assertEquals(TaskStatus.COMPLETED, TaskStatus.fromValue("completed"));
        assertEquals(TaskStatus.FAILED, TaskStatus.fromValue("failed"));
        assertEquals(TaskStatus.CANCELLED, TaskStatus.fromValue("cancelled"));
    }

    @Test
    void testFromValue_InvalidValue() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskStatus.fromValue("invalid_status"),
                "Should throw IllegalArgumentException for unknown status");
    }

    @Test
    void testFromValue_NullValue() {
        // When value is null, the for loop doesn't match anything and throws
        // IllegalArgumentException
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskStatus.fromValue(null),
                "Should throw IllegalArgumentException for null value");
    }

    @Test
    void testIsTerminal_TerminalStates() {
        assertTrue(TaskStatus.COMPLETED.isTerminal(), "COMPLETED should be terminal");
        assertTrue(TaskStatus.FAILED.isTerminal(), "FAILED should be terminal");
        assertTrue(TaskStatus.CANCELLED.isTerminal(), "CANCELLED should be terminal");
    }

    @Test
    void testIsTerminal_NonTerminalStates() {
        assertFalse(TaskStatus.WORKING.isTerminal(), "WORKING should not be terminal");
        assertFalse(
                TaskStatus.INPUT_REQUIRED.isTerminal(), "INPUT_REQUIRED should not be terminal");
    }

    @Test
    void testToString() {
        assertEquals("working", TaskStatus.WORKING.toString());
        assertEquals("input_required", TaskStatus.INPUT_REQUIRED.toString());
        assertEquals("completed", TaskStatus.COMPLETED.toString());
        assertEquals("failed", TaskStatus.FAILED.toString());
        assertEquals("cancelled", TaskStatus.CANCELLED.toString());
    }

    @Test
    void testAllValuesHaveUniqueStrings() {
        TaskStatus[] values = TaskStatus.values();
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                assertNotEquals(
                        values[i].getValue(),
                        values[j].getValue(),
                        "Each status should have a unique value");
            }
        }
    }

    @Test
    void testRoundTrip() {
        // Test that fromValue(getValue()) returns the same enum
        for (TaskStatus status : TaskStatus.values()) {
            assertEquals(status, TaskStatus.fromValue(status.getValue()));
        }
    }
}
