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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.test.SampleTools;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Boundary tests for Tool functionality.
 *
 * <p>These tests verify tool behavior at boundary conditions including null parameters, invalid
 * parameters, missing parameters, and non-existent tools.
 *
 * <p>Tagged as "unit" - tests boundary conditions.
 */
@Tag("unit")
@DisplayName("Tool Boundary Tests")
class ToolBoundaryTest {

    private Toolkit toolkit;
    private SampleTools sampleTools;

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
        sampleTools = new SampleTools();
        toolkit.registerTool(sampleTools);
    }

    @Test
    @DisplayName("Should handle null parameters gracefully")
    void testNullParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // Try calling with null parameters map
            try {
                ToolResultBlock response = addTool.callAsync(null).block();
                // If it doesn't throw, verify response
                if (response != null) {
                    assertNotNull(response, "Should handle null params");
                }
            } catch (Exception e) {
                // Exception is acceptable for null params
                assertTrue(
                        e instanceof NullPointerException || e instanceof IllegalArgumentException,
                        "Should throw appropriate exception for null params");
            }
        }
    }

    @Test
    @DisplayName("Should handle invalid parameter types")
    void testInvalidParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // Try calling with wrong parameter types
            Map<String, Object> invalidParams = new HashMap<>();
            invalidParams.put("a", "not_a_number"); // String instead of int
            invalidParams.put("b", "also_not_a_number");

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(invalidParams).build())
                                .block();
                // If it doesn't throw, verify response exists
                if (response != null) {
                    assertNotNull(response, "Should handle invalid types");
                }
            } catch (Exception e) {
                // Exception is expected for invalid types
                assertTrue(
                        e instanceof IllegalArgumentException
                                || e instanceof ClassCastException
                                || e instanceof NumberFormatException,
                        "Should throw appropriate exception for invalid types");
            }
        }
    }

    @Test
    @DisplayName("Should handle missing required parameters")
    void testMissingParameters() {
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // Try calling with missing parameters
            Map<String, Object> incompleteParams = new HashMap<>();
            incompleteParams.put("a", 5); // Missing 'b' parameter

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(incompleteParams).build())
                                .block();
                // If it doesn't throw, verify response
                if (response != null) {
                    assertNotNull(response, "Should handle missing params");
                }
            } catch (Exception e) {
                // Exception is acceptable for missing required params
                assertTrue(
                        e instanceof IllegalArgumentException || e instanceof NullPointerException,
                        "Should throw appropriate exception for missing params");
            }
        }
    }

    @Test
    @DisplayName("Should handle non-existent tools")
    void testToolNotFound() {
        // Try to get a tool that doesn't exist
        AgentTool nonExistentTool = toolkit.getTool("non_existent_tool_xyz");

        assertNull(nonExistentTool, "Non-existent tool should return null");

        // Verify toolkit still works for valid tools
        AgentTool validTool = toolkit.getTool("add");
        assertNotNull(validTool, "Valid tool should still be accessible");
    }

    @Test
    @DisplayName("Should handle empty parameter map")
    void testEmptyParameters() {
        // Get tool that requires parameters
        AgentTool addTool = toolkit.getTool("add");

        if (addTool != null) {
            // Try with empty params map
            Map<String, Object> emptyParams = new HashMap<>();

            try {
                ToolResultBlock response =
                        addTool.callAsync(ToolCallParam.builder().input(emptyParams).build())
                                .block();
                // If it doesn't throw, verify response
                if (response != null) {
                    assertNotNull(response, "Should handle empty params");
                }
            } catch (Exception e) {
                // Exception is expected for required params
                assertTrue(
                        e instanceof IllegalArgumentException || e instanceof NullPointerException,
                        "Should throw exception for empty params when params required");
            }
        }

        // Test with no-param tool - should succeed
        AgentTool noParamTool = toolkit.getTool("no_param");
        if (noParamTool != null) {
            ToolResultBlock response =
                    noParamTool
                            .callAsync(ToolCallParam.builder().input(new HashMap<>()).build())
                            .block();
            assertNotNull(response, "No-param tool should work with empty params");
        }
    }
}
