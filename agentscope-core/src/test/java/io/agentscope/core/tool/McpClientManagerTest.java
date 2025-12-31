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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpClientManagerTest {

    private McpClientManager manager;
    private Method shouldRegisterToolMethod;

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry();
        ToolGroupManager groupManager = new ToolGroupManager();

        // Create manager with a no-op callback
        manager =
                new McpClientManager(
                        toolRegistry,
                        groupManager,
                        (tool, groupName, mcpClientName, presetParams) -> {
                            // no-op callback for testing
                        });

        // Get the private method using reflection
        shouldRegisterToolMethod =
                McpClientManager.class.getDeclaredMethod(
                        "shouldRegisterTool", String.class, List.class, List.class);
        shouldRegisterToolMethod.setAccessible(true);
    }

    private boolean invokeShouldRegisterTool(
            String toolName, List<String> enableTools, List<String> disableTools) throws Exception {
        return (boolean)
                shouldRegisterToolMethod.invoke(manager, toolName, enableTools, disableTools);
    }

    // ==================== Tests for null/empty lists ====================

    @Test
    void testShouldRegisterTool_BothListsNull_ReturnsTrue() throws Exception {
        // When both lists are null, all tools should be registered
        assertTrue(invokeShouldRegisterTool("anyTool", null, null));
    }

    @Test
    void testShouldRegisterTool_BothListsEmpty_ReturnsTrue() throws Exception {
        // When both lists are empty, all tools should be registered
        assertTrue(
                invokeShouldRegisterTool(
                        "anyTool", Collections.emptyList(), Collections.emptyList()));
    }

    // ==================== Tests for disableTools only ====================

    @Test
    void testShouldRegisterTool_DisableToolsContainsTool_ReturnsFalse() throws Exception {
        // When tool is in disableTools, it should not be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool2", null, disableTools));
    }

    @Test
    void testShouldRegisterTool_DisableToolsDoesNotContainTool_ReturnsTrue() throws Exception {
        // When tool is NOT in disableTools, it should be registered
        List<String> disableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool4", null, disableTools));
    }

    // ==================== Tests for enableTools only ====================

    @Test
    void testShouldRegisterTool_EnableToolsContainsTool_ReturnsTrue() throws Exception {
        // When tool is in enableTools, it should be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, null));
    }

    @Test
    void testShouldRegisterTool_EnableToolsDoesNotContainTool_ReturnsFalse() throws Exception {
        // When tool is NOT in enableTools, it should not be registered
        List<String> enableTools = Arrays.asList("tool1", "tool2", "tool3");
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, null));
    }

    // ==================== Tests for both lists specified ====================

    @Test
    void testShouldRegisterTool_BothListsSpecified_EnableToolsTakesPrecedence() throws Exception {
        // enableTools is checked last, so it takes precedence
        List<String> enableTools = Arrays.asList("tool1", "tool2");
        List<String> disableTools = Arrays.asList("tool2", "tool3");

        // tool1: not in disableTools, in enableTools -> true
        assertTrue(invokeShouldRegisterTool("tool1", enableTools, disableTools));

        // tool2: in disableTools (would be false), but in enableTools -> true (enableTools wins)
        assertTrue(invokeShouldRegisterTool("tool2", enableTools, disableTools));

        // tool3: in disableTools, not in enableTools -> false
        assertFalse(invokeShouldRegisterTool("tool3", enableTools, disableTools));

        // tool4: not in either list, but enableTools is specified -> false
        assertFalse(invokeShouldRegisterTool("tool4", enableTools, disableTools));
    }
}
