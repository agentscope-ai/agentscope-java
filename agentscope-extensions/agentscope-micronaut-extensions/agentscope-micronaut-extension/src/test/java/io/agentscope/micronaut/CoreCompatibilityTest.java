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
package io.agentscope.micronaut;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;

/**
 * Test to verify AgentScope core library compatibility with Micronaut environment.
 * This validates that core classes can be instantiated and used without any framework-specific
 * issues.
 */
class CoreCompatibilityTest {

    @Test
    void testCoreClassesCanBeInstantiated() {
        // Test Memory
        assertDoesNotThrow(
                () -> {
                    Memory memory = new InMemoryMemory();
                    assertNotNull(memory, "Memory should be instantiated");
                });

        // Test Toolkit
        assertDoesNotThrow(
                () -> {
                    Toolkit toolkit = new Toolkit();
                    assertNotNull(toolkit, "Toolkit should be instantiated");
                });
    }

    @Test
    void testModelCanBeCreatedProgrammatically() {
        assertDoesNotThrow(
                () -> {
                    Model model =
                            DashScopeChatModel.builder()
                                    .apiKey("test-key-for-validation")
                                    .modelName("qwen-plus")
                                    .build();

                    assertNotNull(model, "Model should be created");
                    assertEquals("qwen-plus", model.getModelName());
                });
    }

    @Test
    void testAgentCanBeCreatedProgrammatically() {
        assertDoesNotThrow(
                () -> {
                    Model model =
                            DashScopeChatModel.builder()
                                    .apiKey("test-key-for-validation")
                                    .modelName("qwen-plus")
                                    .build();

                    Memory memory = new InMemoryMemory();
                    Toolkit toolkit = new Toolkit();

                    ReActAgent agent =
                            ReActAgent.builder()
                                    .name("TestAgent")
                                    .sysPrompt("You are a test assistant")
                                    .model(model)
                                    .memory(memory)
                                    .toolkit(toolkit)
                                    .maxIters(5)
                                    .build();

                    assertNotNull(agent, "Agent should be created");
                    assertEquals("TestAgent", agent.getName());
                });
    }

    @Test
    void testCoreClassesAreThreadSafe() {
        // Verify that creating multiple instances doesn't cause issues
        assertDoesNotThrow(
                () -> {
                    Memory memory1 = new InMemoryMemory();
                    Memory memory2 = new InMemoryMemory();

                    Toolkit toolkit1 = new Toolkit();
                    Toolkit toolkit2 = new Toolkit();

                    assertNotNull(memory1);
                    assertNotNull(memory2);
                    assertNotNull(toolkit1);
                    assertNotNull(toolkit2);
                });
    }
}
