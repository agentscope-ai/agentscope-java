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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for ToolCallParam. */
@DisplayName("ToolCallParam Tests")
class ToolCallParamTest {

    @Nested
    @DisplayName("Builder pattern")
    class BuilderTests {

        @Test
        @DisplayName("Should build with all parameters")
        void testBuildWithAllParameters() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("test-id")
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            Agent mockAgent = mock(Agent.class);
            when(mockAgent.getName()).thenReturn("TestAgent");

            ToolExecutionContext context =
                    ToolExecutionContext.builder().register("testContext").build();

            List<ToolResultBlock> emittedChunks = new ArrayList<>();
            ToolEmitter emitter = emittedChunks::add;

            Map<String, Object> input = new HashMap<>();
            input.put("param1", "value1");

            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(toolUseBlock)
                            .input(input)
                            .agent(mockAgent)
                            .context(context)
                            .emitter(emitter)
                            .build();

            assertNotNull(param);
            assertEquals(toolUseBlock, param.getToolUseBlock());
            assertEquals("value1", param.getInput().get("param1"));
            assertEquals(mockAgent, param.getAgent());
            assertEquals(context, param.getContext());
            assertSame(emitter, param.getEmitter());
        }

        @Test
        @DisplayName("Should build with minimal parameters")
        void testBuildWithMinimalParameters() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();

            assertNotNull(param);
            assertEquals(toolUseBlock, param.getToolUseBlock());
            assertTrue(param.getInput().isEmpty());
            assertNull(param.getAgent());
            assertNull(param.getContext());
        }

        @Test
        @DisplayName("Builder should be chainable")
        void testBuilderChaining() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam.Builder builder = ToolCallParam.builder();

            ToolCallParam param =
                    builder.toolUseBlock(toolUseBlock)
                            .input(Map.of("key", "value"))
                            .agent(null)
                            .context(null)
                            .emitter(null)
                            .build();

            assertNotNull(param);
        }
    }

    @Nested
    @DisplayName("Emitter functionality")
    class EmitterTests {

        @Test
        @DisplayName("getEmitter() should return NoOpToolEmitter when emitter is null")
        void testGetEmitterReturnsNoOpWhenNull() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param =
                    ToolCallParam.builder().toolUseBlock(toolUseBlock).emitter(null).build();

            ToolEmitter emitter = param.getEmitter();
            assertNotNull(emitter);
            assertInstanceOf(NoOpToolEmitter.class, emitter);
            assertSame(NoOpToolEmitter.INSTANCE, emitter);
        }

        @Test
        @DisplayName("getEmitter() should return NoOpToolEmitter when emitter not set")
        void testGetEmitterReturnsNoOpWhenNotSet() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();

            ToolEmitter emitter = param.getEmitter();
            assertNotNull(emitter);
            assertSame(NoOpToolEmitter.INSTANCE, emitter);
        }

        @Test
        @DisplayName("getEmitter() should return custom emitter when set")
        void testGetEmitterReturnsCustomEmitter() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            List<ToolResultBlock> captured = new ArrayList<>();
            ToolEmitter customEmitter = captured::add;

            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(toolUseBlock)
                            .emitter(customEmitter)
                            .build();

            ToolEmitter emitter = param.getEmitter();
            assertNotNull(emitter);
            assertSame(customEmitter, emitter);
        }

        @Test
        @DisplayName("Custom emitter should receive emitted chunks")
        void testCustomEmitterReceivesChunks() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            List<ToolResultBlock> captured = new ArrayList<>();
            ToolEmitter customEmitter = captured::add;

            ToolCallParam param =
                    ToolCallParam.builder()
                            .toolUseBlock(toolUseBlock)
                            .emitter(customEmitter)
                            .build();

            // Simulate tool emitting chunks
            param.getEmitter().emit(ToolResultBlock.text("chunk 1"));
            param.getEmitter().emit(ToolResultBlock.text("chunk 2"));

            assertEquals(2, captured.size());
        }

        @Test
        @DisplayName("getEmitter() should never return null")
        void testGetEmitterNeverReturnsNull() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            // Test without emitter set
            ToolCallParam param1 = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();
            assertNotNull(param1.getEmitter());

            // Test with null emitter
            ToolCallParam param2 =
                    ToolCallParam.builder().toolUseBlock(toolUseBlock).emitter(null).build();
            assertNotNull(param2.getEmitter());
        }
    }

    @Nested
    @DisplayName("Input handling")
    class InputTests {

        @Test
        @DisplayName("Should create defensive copy of input map")
        void testDefensiveCopyOfInput() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            Map<String, Object> originalInput = new HashMap<>();
            originalInput.put("key", "value");

            ToolCallParam param =
                    ToolCallParam.builder().toolUseBlock(toolUseBlock).input(originalInput).build();

            // Modify original input
            originalInput.put("key", "modified");

            // Param should still have original value
            assertEquals("value", param.getInput().get("key"));
        }

        @Test
        @DisplayName("Should return empty map when input is null")
        void testNullInputReturnsEmptyMap() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param =
                    ToolCallParam.builder().toolUseBlock(toolUseBlock).input(null).build();

            assertNotNull(param.getInput());
            assertTrue(param.getInput().isEmpty());
        }
    }

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("getToolUseBlock() should return the tool use block")
        void testGetToolUseBlock() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder()
                            .id("test-id")
                            .name("test_tool")
                            .input(Map.of("key", "value"))
                            .build();

            ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();

            assertEquals(toolUseBlock, param.getToolUseBlock());
            assertEquals("test-id", param.getToolUseBlock().getId());
            assertEquals("test_tool", param.getToolUseBlock().getName());
        }

        @Test
        @DisplayName("getAgent() should return null when not set")
        void testGetAgentNull() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();

            assertNull(param.getAgent());
        }

        @Test
        @DisplayName("getContext() should return null when not set")
        void testGetContextNull() {
            ToolUseBlock toolUseBlock =
                    ToolUseBlock.builder().id("id").name("tool").input(Map.of()).build();

            ToolCallParam param = ToolCallParam.builder().toolUseBlock(toolUseBlock).build();

            assertNull(param.getContext());
        }
    }
}
