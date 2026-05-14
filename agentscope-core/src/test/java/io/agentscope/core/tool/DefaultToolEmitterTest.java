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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultToolEmitterTest {

    @Test
    void testGetToolUseBlock() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder()
                        .id("call_test")
                        .name("test_tool")
                        .input(Map.of())
                        .content("{}")
                        .build();

        DefaultToolEmitter emitter = new DefaultToolEmitter(toolUseBlock, null);

        assertNotNull(emitter.getToolUseBlock());
        assertEquals("call_test", emitter.getToolUseBlock().getId());
        assertEquals("test_tool", emitter.getToolUseBlock().getName());
    }

    @Test
    void testEmitWithNullCallbackDoesNotThrow() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("x").name("y").input(Map.of()).content("{}").build();

        DefaultToolEmitter emitter = new DefaultToolEmitter(toolUseBlock, null);
        emitter.emit(ToolResultBlock.text("should not throw"));
    }

    @Test
    void testEmitWithCallback() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("x").name("y").input(Map.of()).content("{}").build();

        String[] captured = {null};
        DefaultToolEmitter emitter =
                new DefaultToolEmitter(
                        toolUseBlock,
                        (useBlock, chunk) -> captured[0] = useBlock.getId());

        emitter.emit(ToolResultBlock.text("hello"));
        assertEquals("x", captured[0]);
    }

    @Test
    void testEmitNullChunkDoesNotThrow() {
        ToolUseBlock toolUseBlock =
                ToolUseBlock.builder().id("x").name("y").input(Map.of()).content("{}").build();

        DefaultToolEmitter emitter =
                new DefaultToolEmitter(
                        toolUseBlock,
                        (useBlock, chunk) -> {
                            throw new RuntimeException("should not be called");
                        });

        emitter.emit(null);
    }
}
