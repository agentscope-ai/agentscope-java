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

package io.agentscope.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for ToolChoice sealed interface and its implementations. */
class ToolChoiceTest {

    @Test
    void testAutoCreation() {
        ToolChoice.Auto auto = new ToolChoice.Auto();
        assertNotNull(auto);
        assertTrue(auto instanceof ToolChoice);
    }

    @Test
    void testNoneCreation() {
        ToolChoice.None none = new ToolChoice.None();
        assertNotNull(none);
        assertTrue(none instanceof ToolChoice);
    }

    @Test
    void testRequiredCreation() {
        ToolChoice.Required required = new ToolChoice.Required();
        assertNotNull(required);
        assertTrue(required instanceof ToolChoice);
    }

    @Test
    void testSpecificCreationWithValidToolName() {
        ToolChoice.Specific specific = new ToolChoice.Specific("my_tool");
        assertNotNull(specific);
        assertTrue(specific instanceof ToolChoice);
        assertEquals("my_tool", specific.toolName());
    }

    @Test
    void testSpecificCreationWithNullToolNameThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolChoice.Specific(null),
                "Tool name must not be null or empty");
    }

    @Test
    void testSpecificCreationWithEmptyToolNameThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolChoice.Specific(""),
                "Tool name must not be null or empty");
    }

    @Test
    void testSpecificCreationWithWhitespaceToolNameThrowsException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolChoice.Specific("   "),
                "Tool name must not be null or empty");
    }

    @Test
    void testAutoEquality() {
        ToolChoice.Auto auto1 = new ToolChoice.Auto();
        ToolChoice.Auto auto2 = new ToolChoice.Auto();
        assertEquals(auto1, auto2);
        assertEquals(auto1.hashCode(), auto2.hashCode());
    }

    @Test
    void testNoneEquality() {
        ToolChoice.None none1 = new ToolChoice.None();
        ToolChoice.None none2 = new ToolChoice.None();
        assertEquals(none1, none2);
        assertEquals(none1.hashCode(), none2.hashCode());
    }

    @Test
    void testRequiredEquality() {
        ToolChoice.Required required1 = new ToolChoice.Required();
        ToolChoice.Required required2 = new ToolChoice.Required();
        assertEquals(required1, required2);
        assertEquals(required1.hashCode(), required2.hashCode());
    }

    @Test
    void testSpecificEquality() {
        ToolChoice.Specific specific1 = new ToolChoice.Specific("tool1");
        ToolChoice.Specific specific2 = new ToolChoice.Specific("tool1");
        ToolChoice.Specific specific3 = new ToolChoice.Specific("tool2");

        assertEquals(specific1, specific2);
        assertEquals(specific1.hashCode(), specific2.hashCode());
        assertNotEquals(specific1, specific3);
    }

    @Test
    void testDifferentTypesNotEqual() {
        ToolChoice.Auto auto = new ToolChoice.Auto();
        ToolChoice.None none = new ToolChoice.None();
        ToolChoice.Required required = new ToolChoice.Required();
        ToolChoice.Specific specific = new ToolChoice.Specific("tool");

        assertNotEquals(auto, none);
        assertNotEquals(auto, required);
        assertNotEquals(auto, specific);
        assertNotEquals(none, required);
        assertNotEquals(none, specific);
        assertNotEquals(required, specific);
    }

    @Test
    void testInstanceOfChecks() {
        ToolChoice auto = new ToolChoice.Auto();
        ToolChoice none = new ToolChoice.None();
        ToolChoice required = new ToolChoice.Required();
        ToolChoice specific = new ToolChoice.Specific("tool");

        assertTrue(auto instanceof ToolChoice.Auto);
        assertFalse(auto instanceof ToolChoice.None);
        assertFalse(auto instanceof ToolChoice.Required);
        assertFalse(auto instanceof ToolChoice.Specific);

        assertTrue(none instanceof ToolChoice.None);
        assertTrue(required instanceof ToolChoice.Required);
        assertTrue(specific instanceof ToolChoice.Specific);
    }

    @Test
    void testSpecificToolNameAccessor() {
        ToolChoice.Specific specific = new ToolChoice.Specific("generate_response");
        assertEquals("generate_response", specific.toolName());
    }
}
