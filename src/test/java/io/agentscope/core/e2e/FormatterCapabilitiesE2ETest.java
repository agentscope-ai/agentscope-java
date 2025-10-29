/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.formatter.FormatterCapabilities;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
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
 * E2E tests for formatter capabilities verification.
 *
 * <p>Coverage:
 * - FormatterCapabilities query and validation
 * - supportsToolsApi() accuracy
 * - supportsVision() accuracy
 * - supportedBlocks() completeness
 * - Cross-provider capability differences
 */
@Tag("e2e")
@Tag("capabilities")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Formatter Capabilities E2E Tests")
class FormatterCapabilitiesE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should report correct tool API support")
    void testToolsApiCapability(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tools API Capability with " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("CapabilityTestAgent", toolkit);

        // Verify formatter capabilities
        // Note: We can't directly access the formatter from the agent,
        // but we can test that tools actually work
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is 25 plus 17?").build()))
                        .build();

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Tool response: " + responseText);

        // If tools API is supported, agent should use tools
        assertTrue(
                ContentValidator.containsKeywords(response, "42"),
                "Tool API support verified - agent used tools for " + provider.getModelName());

        System.out.println("✓ Tools API capability verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should report correct vision support")
    void testVisionCapability(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Vision Capability with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VisionCapabilityAgent", toolkit);

        String imageUrl =
                "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What do you see in this image?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(URLSource.builder().url(imageUrl).build())
                                                .build()))
                        .build();

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Vision response: " + responseText);

        // If vision is supported, agent should describe the image
        assertTrue(
                ContentValidator.mentionsVisualElements(response, "cat", "animal"),
                "Vision support verified - agent processed image for " + provider.getModelName());

        System.out.println("✓ Vision capability verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify DashScope formatter capabilities")
    void testDashScopeFormatterCapabilities() {
        System.out.println("\n=== Test: DashScope Formatter Capabilities ===");

        DashScopeChatFormatter formatter = new DashScopeChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities, "Capabilities should not be null");

        System.out.println("DashScope capabilities: " + capabilities);
        System.out.println("  - Supports Tools API: " + capabilities.supportsToolsApi());
        System.out.println("  - Supports Vision: " + capabilities.supportsVision());
        System.out.println("  - Provider: " + capabilities.getProviderName());
        System.out.println("  - Supported blocks: " + capabilities.getSupportedBlocks().size());

        // DashScope should support tools and vision
        assertTrue(capabilities.supportsToolsApi(), "DashScope should support tools");
        assertTrue(capabilities.supportsVision(), "DashScope should support vision");

        System.out.println("✓ DashScope formatter capabilities verified");
    }

    @Test
    @DisplayName("Should verify OpenAI formatter capabilities")
    void testOpenAIFormatterCapabilities() {
        System.out.println("\n=== Test: OpenAI Formatter Capabilities ===");

        OpenAIChatFormatter formatter = new OpenAIChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        assertNotNull(capabilities, "Capabilities should not be null");

        System.out.println("OpenAI capabilities: " + capabilities);
        System.out.println("  - Supports Tools API: " + capabilities.supportsToolsApi());
        System.out.println("  - Supports Vision: " + capabilities.supportsVision());
        System.out.println("  - Provider: " + capabilities.getProviderName());
        System.out.println("  - Supported blocks: " + capabilities.getSupportedBlocks().size());

        // OpenAI should support tools and vision
        assertTrue(capabilities.supportsToolsApi(), "OpenAI should support tools");
        assertTrue(capabilities.supportsVision(), "OpenAI should support vision");

        System.out.println("✓ OpenAI formatter capabilities verified");
    }

    @Test
    @DisplayName("Should differentiate capabilities across formatters")
    void testCrossFormatterCapabilityDifferences() {
        System.out.println("\n=== Test: Cross-Formatter Capability Differences ===");

        DashScopeChatFormatter dashScopeFormatter = new DashScopeChatFormatter();
        OpenAIChatFormatter openAIFormatter = new OpenAIChatFormatter();

        FormatterCapabilities dashScopeCaps = dashScopeFormatter.getCapabilities();
        FormatterCapabilities openAICaps = openAIFormatter.getCapabilities();

        System.out.println("DashScope provider: " + dashScopeCaps.getProviderName());
        System.out.println("OpenAI provider: " + openAICaps.getProviderName());

        // Providers should have different names
        assertTrue(
                !dashScopeCaps.getProviderName().equals(openAICaps.getProviderName()),
                "Formatters should have different provider names");

        System.out.println("✓ Cross-formatter capability differences verified");
    }

    @Test
    @DisplayName("Should validate capability consistency with actual behavior")
    void testCapabilityConsistency() {
        System.out.println("\n=== Test: Capability Consistency ===");

        // Test that FormatterCapabilities correctly reports what the formatter can do
        DashScopeChatFormatter formatter = new DashScopeChatFormatter();
        FormatterCapabilities capabilities = formatter.getCapabilities();

        // If capabilities say vision is supported, formatter should handle ImageBlock
        if (capabilities.supportsVision()) {
            assertTrue(
                    capabilities.supportsBlockType(io.agentscope.core.message.ImageBlock.class),
                    "Vision support should include ImageBlock");
            System.out.println("✓ Vision capability consistent with ImageBlock support");
        }

        // If capabilities say tools are supported, formatter should handle ToolUseBlock
        if (capabilities.supportsToolsApi()) {
            assertTrue(
                    capabilities.supportsBlockType(io.agentscope.core.message.ToolUseBlock.class),
                    "Tools API support should include ToolUseBlock");
            System.out.println("✓ Tools API capability consistent with ToolUseBlock support");
        }

        System.out.println("✓ Capability consistency verified");
    }
}
