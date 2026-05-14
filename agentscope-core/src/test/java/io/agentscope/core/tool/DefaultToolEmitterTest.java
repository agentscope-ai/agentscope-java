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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for DefaultToolEmitter. */
@DisplayName("DefaultToolEmitter Tests")
class DefaultToolEmitterTest {

    private final ToolUseBlock testToolUseBlock =
            ToolUseBlock.builder()
                    .id("call_test")
                    .name("test_tool")
                    .input(Map.of())
                    .content("{}")
                    .build();

    @Test
    @DisplayName("getToolUseBlock() should return the ToolUseBlock with tool call ID")
    void testGetToolUseBlock() {
        DefaultToolEmitter emitter = new DefaultToolEmitter(testToolUseBlock, null);

        assertNotNull(emitter.getToolUseBlock());
        assertSame(testToolUseBlock, emitter.getToolUseBlock());
        assertEquals("call_test", emitter.getToolUseBlock().getId());
        assertEquals("test_tool", emitter.getToolUseBlock().getName());
    }

    @Test
    @DisplayName("emit() should not throw when callback is null")
    void testEmitWithNullCallback() {
        DefaultToolEmitter emitter = new DefaultToolEmitter(testToolUseBlock, null);
        assertDoesNotThrow(() -> emitter.emit(ToolResultBlock.text("test")));
    }

    @Test
    @DisplayName("emit() should not throw when chunk is null")
    void testEmitWithNullChunk() {
        DefaultToolEmitter emitter =
                new DefaultToolEmitter(
                        testToolUseBlock,
                        (useBlock, chunk) -> {
                            throw new RuntimeException("should not be called");
                        });
        assertDoesNotThrow(() -> emitter.emit(null));
    }

    @Test
    @DisplayName("emit() should invoke callback with correct ToolUseBlock")
    void testEmitWithCallback() {
        ToolUseBlock[] captured = {null};
        DefaultToolEmitter emitter =
                new DefaultToolEmitter(
                        testToolUseBlock, (useBlock, chunk) -> captured[0] = useBlock);

        emitter.emit(ToolResultBlock.text("hello"));
        assertSame(testToolUseBlock, captured[0]);
    }
}
