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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class ToolRegistryTest {

    private ToolRegistry registry;
    private AgentTool mockTool1;
    private AgentTool mockTool2;
    private RegisteredToolFunction registered1;
    private RegisteredToolFunction registered2;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();

        // Create mock tools
        mockTool1 = createMockTool("tool1", "Description 1");
        mockTool2 = createMockTool("tool2", "Description 2");

        // Create registered wrappers
        registered1 = new RegisteredToolFunction(mockTool1, "group1", null, null);
        registered2 = new RegisteredToolFunction(mockTool2, null, null, "mcpClient1");
    }

    private AgentTool createMockTool(String name, String description) {
        return new AgentTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return description;
            }

            @Override
            public Map<String, Object> getParameters() {
                return new HashMap<>();
            }

            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.text("result"));
            }
        };
    }

    @Test
    void testRegisterTool() {
        // Act
        registry.registerTool("tool1", mockTool1, registered1);

        // Assert
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(registered1, registry.getRegisteredTool("tool1"));
        assertTrue(registry.getToolNames().contains("tool1"));
    }

    @Test
    void testRegisterMultipleTools() {
        // Act
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Assert
        assertEquals(2, registry.getToolNames().size());
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(mockTool2, registry.getTool("tool2"));
    }

    @Test
    void testGetToolNotFound() {
        // Act
        AgentTool result = registry.getTool("nonexistent");

        // Assert
        assertNull(result);
    }

    @Test
    void testGetRegisteredToolNotFound() {
        // Act
        RegisteredToolFunction result = registry.getRegisteredTool("nonexistent");

        // Assert
        assertNull(result);
    }

    @Test
    void testGetToolNames() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        Set<String> names = registry.getToolNames();

        // Assert
        assertEquals(2, names.size());
        assertTrue(names.contains("tool1"));
        assertTrue(names.contains("tool2"));
    }

    @Test
    void testGetToolNamesEmpty() {
        // Act
        Set<String> names = registry.getToolNames();

        // Assert
        assertTrue(names.isEmpty());
    }

    @Test
    void testGetAllRegisteredTools() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        Map<String, RegisteredToolFunction> allTools = registry.getAllRegisteredTools();

        // Assert
        assertEquals(2, allTools.size());
        assertEquals(registered1, allTools.get("tool1"));
        assertEquals(registered2, allTools.get("tool2"));
    }

    @Test
    void testGetAllRegisteredToolsReturnsNewMap() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act
        Map<String, RegisteredToolFunction> map1 = registry.getAllRegisteredTools();
        Map<String, RegisteredToolFunction> map2 = registry.getAllRegisteredTools();

        // Assert
        assertNotSame(map1, map2, "Should return a new map each time");
        assertEquals(map1, map2, "But maps should be equal");
    }

    @Test
    void testRemoveTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);

        // Act
        registry.removeTool("tool1");

        // Assert
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getRegisteredTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
        assertTrue(registry.getToolNames().contains("tool2"));
    }

    @Test
    void testRemoveNonexistentTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act & Assert - should not throw
        assertDoesNotThrow(() -> registry.removeTool("nonexistent"));

        // Verify original tool still exists
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testRemoveTools() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        registry.registerTool("tool2", mockTool2, registered2);
        AgentTool mockTool3 = createMockTool("tool3", "Description 3");
        RegisteredToolFunction registered3 =
                new RegisteredToolFunction(mockTool3, null, null, null);
        registry.registerTool("tool3", mockTool3, registered3);

        // Act
        registry.removeTools(Set.of("tool1", "tool3"));

        // Assert
        assertNull(registry.getTool("tool1"));
        assertNull(registry.getTool("tool3"));
        assertEquals(mockTool2, registry.getTool("tool2"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithEmptySet() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act
        registry.removeTools(Set.of());

        // Assert
        assertEquals(mockTool1, registry.getTool("tool1"));
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testRemoveToolsWithNonexistentNames() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);

        // Act & Assert
        assertDoesNotThrow(() -> registry.removeTools(Set.of("nonexistent1", "nonexistent2")));

        // Verify original tool still exists
        assertEquals(mockTool1, registry.getTool("tool1"));
    }

    @Test
    void testOverwriteTool() {
        // Arrange
        registry.registerTool("tool1", mockTool1, registered1);
        AgentTool newTool = createMockTool("tool1", "New Description");
        RegisteredToolFunction newRegistered =
                new RegisteredToolFunction(newTool, "group2", null, null);

        // Act
        registry.registerTool("tool1", newTool, newRegistered);

        // Assert
        assertEquals(newTool, registry.getTool("tool1"));
        assertEquals(newRegistered, registry.getRegisteredTool("tool1"));
        assertEquals("New Description", registry.getTool("tool1").getDescription());
        assertEquals(1, registry.getToolNames().size());
    }

    @Test
    void testConcurrentAccess() {
        // This tests that ConcurrentHashMap is being used correctly
        // Register tools from multiple threads
        Thread t1 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t1_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null, null);
                                registry.registerTool("tool_t1_" + i, tool, reg);
                            }
                        });

        Thread t2 =
                new Thread(
                        () -> {
                            for (int i = 0; i < 100; i++) {
                                AgentTool tool = createMockTool("tool_t2_" + i, "Desc " + i);
                                RegisteredToolFunction reg =
                                        new RegisteredToolFunction(tool, null, null, null);
                                registry.registerTool("tool_t2_" + i, tool, reg);
                            }
                        });

        // Act
        t1.start();
        t2.start();

        // Wait for threads to complete
        assertDoesNotThrow(
                () -> {
                    t1.join();
                    t2.join();
                });

        // Assert
        assertEquals(200, registry.getToolNames().size());
    }
}
