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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link ToolMergeMode}. */
class ToolMergeModeTest {

    @Test
    void testAllModesExist() {
        assertNotNull(ToolMergeMode.EXTERNAL_ONLY);
        assertNotNull(ToolMergeMode.AGENT_ONLY);
        assertNotNull(ToolMergeMode.MERGE_EXTERNAL_PRIORITY);
    }

    @Test
    void testModeCount() {
        assertEquals(3, ToolMergeMode.values().length);
    }

    @Test
    void testValueOf() {
        assertEquals(ToolMergeMode.EXTERNAL_ONLY, ToolMergeMode.valueOf("EXTERNAL_ONLY"));
        assertEquals(ToolMergeMode.AGENT_ONLY, ToolMergeMode.valueOf("AGENT_ONLY"));
        assertEquals(
                ToolMergeMode.MERGE_EXTERNAL_PRIORITY,
                ToolMergeMode.valueOf("MERGE_EXTERNAL_PRIORITY"));
    }
}
