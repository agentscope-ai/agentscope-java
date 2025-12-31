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
package io.agentscope.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link LongTermMemoryMode}. */
class LongTermMemoryModeTest {

    @Test
    void testEnumValues() {
        LongTermMemoryMode[] modes = LongTermMemoryMode.values();
        assertNotNull(modes);
        assertEquals(3, modes.length);
    }

    @Test
    void testAgentControlExists() {
        LongTermMemoryMode mode = LongTermMemoryMode.valueOf("AGENT_CONTROL");
        assertEquals(LongTermMemoryMode.AGENT_CONTROL, mode);
    }

    @Test
    void testStaticControlExists() {
        LongTermMemoryMode mode = LongTermMemoryMode.valueOf("STATIC_CONTROL");
        assertEquals(LongTermMemoryMode.STATIC_CONTROL, mode);
    }

    @Test
    void testBothExists() {
        LongTermMemoryMode mode = LongTermMemoryMode.valueOf("BOTH");
        assertEquals(LongTermMemoryMode.BOTH, mode);
    }

    @Test
    void testValueOfWithValidName() {
        assertEquals(LongTermMemoryMode.AGENT_CONTROL, LongTermMemoryMode.valueOf("AGENT_CONTROL"));
        assertEquals(
                LongTermMemoryMode.STATIC_CONTROL, LongTermMemoryMode.valueOf("STATIC_CONTROL"));
        assertEquals(LongTermMemoryMode.BOTH, LongTermMemoryMode.valueOf("BOTH"));
    }

    @Test
    void testValuesContainsAllModes() {
        LongTermMemoryMode[] modes = LongTermMemoryMode.values();
        boolean hasAgentControl = false;
        boolean hasStaticControl = false;
        boolean hasBoth = false;

        for (LongTermMemoryMode mode : modes) {
            if (mode == LongTermMemoryMode.AGENT_CONTROL) {
                hasAgentControl = true;
            } else if (mode == LongTermMemoryMode.STATIC_CONTROL) {
                hasStaticControl = true;
            } else if (mode == LongTermMemoryMode.BOTH) {
                hasBoth = true;
            }
        }

        assertEquals(true, hasAgentControl);
        assertEquals(true, hasStaticControl);
        assertEquals(true, hasBoth);
    }
}
