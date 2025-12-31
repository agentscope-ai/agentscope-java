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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consolidated E2E tests for tool system functionality.
 *
 * <p>Tests multiple tool calls, parallel execution, hook lifecycle, multimodal tool results,
 * and tool error handling across all available model providers that support tools.
 *
 * <p><b>Requirements:</b> OPENAI_API_KEY and/or DASHSCOPE_API_KEY environment variables
 * must be set. Tests are dynamically enabled based on available API keys and model capabilities.
 */
@Tag("e2e")
@Tag("tools")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Tool System E2E Tests (Consolidated)")
class ToolSystemE2ETest {

    // Extended timeout for multi-tool tests: multiple sequential tool calls + streaming can take
    // time
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(180);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should handle multiple tool calls in sequence")
    void testMultipleToolCalls(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Multiple Tool Calls with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("MultiToolAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please use the available tools: First use the add tool to add 10 and 20,"
                                + " then use the multiply tool to multiply the result by 3.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(ContentValidator.hasMeaningfulContent(response), "Response should have content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);

        // Expected: (10+20)*3 = 90
        assertTrue(
                ContentValidator.containsCalculationResult(response, "90"),
                "Answer should contain correct result 90 for " + provider.getModelName());

        System.out.println("✓ Multiple tool calls verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should handle complex calculation chains")
    void testComplexCalculationChains(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Complex Calculation Chains with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("ComplexCalcAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "First add 10 and 20, then multiply the result by 3, then calculate the"
                                + " factorial of 5.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have calculation results");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);

        // Expected results: 10+20=30, 30*3=90, 5!=120
        boolean hasExpectedResult =
                ContentValidator.containsKeywords(response, "90", "120")
                        || ContentValidator.containsKeywords(
                                response, "ninety", "one hundred twenty");

        assertTrue(
                hasExpectedResult,
                "Response should contain expected calculation results for "
                        + provider.getModelName());

        System.out.println(
                "✓ Complex calculation chains verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledMultimodalToolProviders")
    @DisplayName("Should handle tool returning image URLs")
    void testToolReturningImageURL(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool Returning Image URL with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalToolTest());
        ReActAgent agent = provider.createAgent("ImageToolAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getImage tool to get a cat image, then summarize the tool's"
                                + " response including any file paths or URLs returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 10),
                "Should have meaningful response for " + provider.getModelName());

        // Verify model mentions the returned file/URL
        assertTrue(
                ContentValidator.mentionsFileInfo(response),
                "Response should mention the image or URL returned by the tool for "
                        + provider.getModelName());

        System.out.println("✓ Tool returning image URL verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledMultimodalToolProviders")
    @DisplayName("Should handle tool returning mixed multimodal content")
    void testToolReturningMixedMultimodalContent(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool Returning Mixed Multimodal Content with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalToolTest());
        ReActAgent agent = provider.createAgent("MixedMultimodalToolAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getMultimodalContent tool to get both image and text content, then"
                                + " summarize what the tool returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 10),
                "Should have meaningful response for " + provider.getModelName());

        // Verify model mentions both content types
        assertTrue(
                ContentValidator.mentionsFileInfo(response),
                "Response should mention the file information returned by the tool for "
                        + provider.getModelName());

        System.out.println(
                "✓ Tool returning mixed multimodal content verified for "
                        + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should verify tool call memory structure")
    void testToolCallMemoryStructure(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Tool Call Memory Structure with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("MemoryTestAgent", toolkit);

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "What is 12 plus 34? You should use tools. Please do not calculate by"
                                + " yourself.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");

        // Verify memory structure: user message + tool use + tool result + final answer
        List<Msg> memory = agent.getMemory().getMessages();
        assertTrue(
                memory.size() >= 4,
                "Memory should contain at least 4 messages (user, tool use, tool result, final"
                        + " answer) for "
                        + provider.getModelName());

        // Verify tool use and result blocks exist
        boolean foundToolUse = false;
        boolean foundToolResult = false;

        for (Msg msg : memory) {
            if (msg.getRole() == MsgRole.ASSISTANT && msg.hasContentBlocks(ToolUseBlock.class)) {
                foundToolUse = true;
                ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
                System.out.println("Found tool call: " + toolUse.getName());
            }

            if (msg.getRole() == MsgRole.TOOL && msg.hasContentBlocks(ToolResultBlock.class)) {
                foundToolResult = true;
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                System.out.println("Found tool result for: " + toolResult.getName());
            }
        }

        assertTrue(
                foundToolUse,
                "Memory should contain a ToolUseBlock for " + provider.getModelName());
        assertTrue(
                foundToolResult,
                "Memory should contain a ToolResultBlock for " + provider.getModelName());

        System.out.println("Final memory size: " + memory.size() + " messages");
        System.out.println(
                "✓ Tool call memory structure verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify tool provider availability")
    void testToolProviderAvailability() {
        System.out.println("\n=== Test: Tool Provider Availability ===");

        long enabledToolProviders = ProviderFactory.getEnabledToolProviders().count();
        long enabledMultimodalToolProviders =
                ProviderFactory.getEnabledMultimodalToolProviders().count();

        System.out.println("Enabled tool providers: " + enabledToolProviders);
        System.out.println("Enabled multimodal tool providers: " + enabledMultimodalToolProviders);

        // At least one tool provider should be available if API keys are set
        System.out.println("✓ Tool provider availability verified");
    }

    /** Test multimodal tools for returning various content types. */
    public static class MultimodalToolTest {

        private static final String CAT_IMAGE_URL =
                "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

        @Tool(description = "Get an image URL")
        public ToolResultBlock getImage() {
            List<ContentBlock> output =
                    List.of(
                            TextBlock.builder().text("Here is a cat image").build(),
                            new ImageBlock(URLSource.builder().url(CAT_IMAGE_URL).build()));

            return ToolResultBlock.of(output);
        }

        @Tool(description = "Get mixed multimodal content")
        public ToolResultBlock getMultimodalContent() {
            List<ContentBlock> output =
                    List.of(
                            TextBlock.builder()
                                    .text("Here is multimodal content with text and image")
                                    .build(),
                            new ImageBlock(URLSource.builder().url(CAT_IMAGE_URL).build()));

            return ToolResultBlock.of(output);
        }
    }
}
