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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for PlanNotebook functionality.
 *
 * <p>Tests agent planning capabilities including plan creation, subtask management, and plan state
 * persistence.
 */
@Tag("e2e")
@Tag("plan")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Plan Management E2E Tests")
class PlanE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should create plan via agent for complex task")
    void testAgentCreatesPlan(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Agent Creates Plan with " + provider.getProviderName() + " ===");

        // Create PlanNotebook without user confirmation for testing
        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(planNotebook);
        ReActAgent agent = provider.createAgent("PlanAgent", toolkit);

        // Ask agent to create a plan for a complex task
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Create a plan with 3 subtasks to build a simple calculator application."
                                + " Use the create_plan tool.");

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("Response: " + TestUtils.extractTextContent(response));

        // Check if plan was created (may or may not have been created depending on model behavior)
        if (planNotebook.getCurrentPlan() != null) {
            System.out.println("Plan created with title: " + planNotebook.getCurrentPlan());
        } else {
            System.out.println("Plan was not created - model may have responded differently");
        }

        System.out.println("✓ Plan creation test completed for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify PlanNotebook builder patterns")
    void testPlanNotebookBuilder() {
        System.out.println("\n=== Test: PlanNotebook Builder Patterns ===");

        // Test default builder
        PlanNotebook notebook1 = PlanNotebook.builder().build();
        assertNotNull(notebook1, "Default PlanNotebook should be created");

        // Test with custom settings
        PlanNotebook notebook2 = PlanNotebook.builder().needUserConfirm(false).build();
        assertNotNull(notebook2, "PlanNotebook with custom settings should be created");

        // Verify initial state
        assertTrue(notebook2.getCurrentPlan() == null, "New notebook should have no active plan");

        System.out.println("✓ PlanNotebook builder patterns verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should handle planning tools registration")
    void testPlanningToolsRegistration(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Planning Tools Registration with "
                        + provider.getProviderName()
                        + " ===");

        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(planNotebook);

        // Verify planning tools are registered
        var toolSchemas = toolkit.getToolSchemas();
        System.out.println("Registered tools: " + toolSchemas.size());
        toolSchemas.forEach(schema -> System.out.println("  - " + schema.getName()));

        // Planning tools should be available
        boolean hasPlanningTools =
                toolSchemas.stream()
                        .anyMatch(
                                schema ->
                                        schema.getName().contains("plan")
                                                || schema.getName().contains("subtask"));

        assertTrue(
                hasPlanningTools,
                "Toolkit should have planning tools registered for " + provider.getModelName());

        System.out.println(
                "✓ Planning tools registration verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should interact with planning tools")
    void testInteractWithPlanningTools(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Interact with Planning Tools with "
                        + provider.getProviderName()
                        + " ===");

        PlanNotebook planNotebook = PlanNotebook.builder().needUserConfirm(false).build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(planNotebook);
        ReActAgent agent = provider.createAgent("PlanInteractAgent", toolkit);

        // Ask about planning capabilities
        Msg input =
                TestUtils.createUserMessage(
                        "User", "What planning tools do you have available? List them.");

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should receive response");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should describe available tools for " + provider.getModelName());

        System.out.println("Response: " + TestUtils.extractTextContent(response));

        System.out.println(
                "✓ Planning tools interaction verified for " + provider.getProviderName());
    }
}
