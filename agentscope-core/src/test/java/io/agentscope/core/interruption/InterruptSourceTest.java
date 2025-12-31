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
package io.agentscope.core.interruption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for InterruptSource enum.
 *
 * Tests cover:
 * - All enum values exist
 * - valueOf method
 * - values method
 * - Enum ordering
 */
@DisplayName("InterruptSource Tests")
class InterruptSourceTest {

    @Test
    @DisplayName("Should have USER enum value")
    void testUserEnumValue() {
        InterruptSource source = InterruptSource.USER;
        assertNotNull(source, "USER enum value should exist");
        assertEquals("USER", source.name(), "USER name should match");
    }

    @Test
    @DisplayName("Should have TOOL enum value")
    void testToolEnumValue() {
        InterruptSource source = InterruptSource.TOOL;
        assertNotNull(source, "TOOL enum value should exist");
        assertEquals("TOOL", source.name(), "TOOL name should match");
    }

    @Test
    @DisplayName("Should have SYSTEM enum value")
    void testSystemEnumValue() {
        InterruptSource source = InterruptSource.SYSTEM;
        assertNotNull(source, "SYSTEM enum value should exist");
        assertEquals("SYSTEM", source.name(), "SYSTEM name should match");
    }

    @Test
    @DisplayName("Should return correct value using valueOf")
    void testValueOf() {
        assertEquals(InterruptSource.USER, InterruptSource.valueOf("USER"));
        assertEquals(InterruptSource.TOOL, InterruptSource.valueOf("TOOL"));
        assertEquals(InterruptSource.SYSTEM, InterruptSource.valueOf("SYSTEM"));
    }

    @Test
    @DisplayName("Should throw exception for invalid valueOf")
    void testValueOfInvalid() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InterruptSource.valueOf("INVALID"),
                "Should throw exception for invalid enum name");
    }

    @Test
    @DisplayName("Should return all values")
    void testValues() {
        InterruptSource[] values = InterruptSource.values();

        assertNotNull(values, "Values array should not be null");
        assertEquals(3, values.length, "Should have exactly 3 enum values");

        // Verify all expected values are present
        InterruptSource[] expected = {
            InterruptSource.USER, InterruptSource.TOOL, InterruptSource.SYSTEM
        };
        assertArrayEquals(expected, values, "Values should match expected order");
    }

    @Test
    @DisplayName("Should maintain consistent enum ordering")
    void testEnumOrdering() {
        InterruptSource[] values = InterruptSource.values();

        assertEquals(InterruptSource.USER, values[0], "USER should be at index 0");
        assertEquals(InterruptSource.TOOL, values[1], "TOOL should be at index 1");
        assertEquals(InterruptSource.SYSTEM, values[2], "SYSTEM should be at index 2");
    }

    @Test
    @DisplayName("Should maintain ordinal values")
    void testOrdinalValues() {
        assertEquals(0, InterruptSource.USER.ordinal(), "USER ordinal should be 0");
        assertEquals(1, InterruptSource.TOOL.ordinal(), "TOOL ordinal should be 1");
        assertEquals(2, InterruptSource.SYSTEM.ordinal(), "SYSTEM ordinal should be 2");
    }

    @Test
    @DisplayName("Should support enum comparison")
    void testEnumComparison() {
        // Test equality
        assertEquals(InterruptSource.USER, InterruptSource.valueOf("USER"));

        // Test compareTo based on ordinal
        assertEquals(
                -1,
                InterruptSource.USER.compareTo(InterruptSource.TOOL),
                "USER should come before TOOL");
        assertEquals(
                2,
                InterruptSource.SYSTEM.compareTo(InterruptSource.USER),
                "SYSTEM should come after USER");
        assertEquals(
                0,
                InterruptSource.TOOL.compareTo(InterruptSource.TOOL),
                "TOOL should equal itself");
    }
}
