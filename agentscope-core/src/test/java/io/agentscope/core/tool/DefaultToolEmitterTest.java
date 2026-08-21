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

import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for DefaultToolEmitter. */
@DisplayName("DefaultToolEmitter Tests")
class DefaultToolEmitterTest {

    @Test
    @DisplayName("getToolCallId() should return the associated tool call ID")
    void testGetToolCallId() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("call-123").name("test_tool").input(Map.of()).build();
        ToolEmitter emitter = new DefaultToolEmitter(toolUseBlock, null);

        assertEquals("call-123", emitter.getToolCallId());
    }

    @Test
    @DisplayName("getToolCallId() should return null when no tool call is available")
    void testGetToolCallIdWithoutToolUseBlock() {
        ToolEmitter emitter = new DefaultToolEmitter(null, null);

        assertNull(emitter.getToolCallId());
    }
}
