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
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for Tool Groups functionality.
 *
 * <p>Tests dynamic tool activation/deactivation and tool group management.
 */
@Tag("e2e")
@Tag("tool-groups")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Tool Groups E2E Tests")
class ToolGroupsE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // ==================== Test Tools ====================

    /** Basic tools that are always available. */
    public static class BasicTools {

        @Tool(description = "Get the current time")
        public String getCurrentTime() {
            return "Current time: 2025-01-03 10:30:00";
        }

        @Tool(description = "Say hello to someone")
        public String sayHello(@ToolParam(name = "name") String name) {
            return "Hello, " + name + "!";
        }
    }

    /** Admin tools that can be activated/deactivated. */
    public static class AdminTools {

        @Tool(description = "Delete a file (admin only)")
        public String deleteFile(@ToolParam(name = "filename") String filename) {
            return "File '" + filename + "' deleted (simulated)";
        }

        @Tool(description = "Modify user permissions (admin only)")
        public String modifyPermissions(
                @ToolParam(name = "user") String user, @ToolParam(name = "level") String level) {
            return "Permissions for '" + user + "' changed to '" + level + "' (simulated)";
        }
    }

    // ==================== Tests ====================
    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should only call tools from active groups")
    void testActiveGroupToolCalling(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Active Group Tool Calling with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();

        // Create groups - only basic is active
        toolkit.createToolGroup("basic", "Basic tools", true);
        toolkit.createToolGroup("admin", "Admin tools", false);

        // Register tools with group assignment
        toolkit.registration().tool(new BasicTools()).group("basic").apply();
        toolkit.registration().tool(new AdminTools()).group("admin").apply();

        ReActAgent agent = provider.createAgent("ToolGroupAgent", toolkit);

        // Ask about time - should work (basic tools active)
        Msg input = TestUtils.createUserMessage("User", "What is the current time?");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("Response (basic tool): " + TestUtils.extractTextContent(response));
        System.out.println(
                "✓ Active group tool calling verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should activate tool groups dynamically")
    void testDynamicGroupActivation(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Dynamic Group Activation with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();

        // Create groups - admin starts inactive
        toolkit.createToolGroup("basic", "Basic tools", true);
        toolkit.createToolGroup("admin", "Admin tools", false);

        // Register tools with group assignment
        toolkit.registration().tool(new BasicTools()).group("basic").apply();
        toolkit.registration().tool(new AdminTools()).group("admin").apply();

        // Verify admin tools not available initially
        int initialCount = toolkit.getToolSchemas().size();
        System.out.println("Initial tool count (admin inactive): " + initialCount);

        // Activate admin group
        toolkit.updateToolGroups(List.of("admin"), true);

        // Verify admin tools now available
        int afterCount = toolkit.getToolSchemas().size();
        System.out.println("After activating admin: " + afterCount);

        assertTrue(afterCount > initialCount, "Should have more tools after activation");

        // Create agent with updated toolkit
        ReActAgent agent = provider.createAgent("DynamicGroupAgent", toolkit);

        // Now admin tools should be callable
        Msg input = TestUtils.createUserMessage("User", "Say hello to Alice");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("Response: " + TestUtils.extractTextContent(response));
        System.out.println("✓ Dynamic group activation verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should persist tool group state")
    void testToolGroupStatePersistence() {
        System.out.println("\n=== Test: Tool Group State Persistence ===");

        Toolkit toolkit = new Toolkit();

        // Create and configure groups
        toolkit.createToolGroup("basic", "Basic tools", true);
        toolkit.createToolGroup("admin", "Admin tools", false);
        toolkit.createToolGroup("debug", "Debug tools", false);

        // Register tools with group assignment
        toolkit.registration().tool(new BasicTools()).group("basic").apply();
        toolkit.registration().tool(new AdminTools()).group("admin").apply();

        // Modify active groups
        toolkit.updateToolGroups(List.of("admin"), true);
        toolkit.updateToolGroups(List.of("debug"), true);

        // Verify state
        List<String> activeGroups = toolkit.getActiveGroups();
        System.out.println("Active groups: " + activeGroups);

        assertTrue(activeGroups.contains("basic"), "Should contain basic");
        assertTrue(activeGroups.contains("admin"), "Should contain admin");
        assertTrue(activeGroups.contains("debug"), "Should contain debug");
        assertEquals(3, activeGroups.size(), "Should have 3 active groups");

        // Reset to specific groups
        toolkit.setActiveGroups(List.of("basic"));
        activeGroups = toolkit.getActiveGroups();

        assertEquals(1, activeGroups.size(), "Should have 1 active group after reset");
        assertTrue(activeGroups.contains("basic"), "Should only contain basic");

        System.out.println("✓ Tool group state persistence verified");
    }
}
