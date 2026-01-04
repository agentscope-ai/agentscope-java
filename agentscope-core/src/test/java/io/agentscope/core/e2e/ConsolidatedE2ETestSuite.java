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

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Test suite configuration and validation for consolidated E2E tests.
 */
@Tag("e2e")
@DisplayName("Consolidated E2E Test Suite Configuration")
class ConsolidatedE2ETestSuite {

    @Test
    @DisplayName("Should validate test environment configuration")
    @EnabledIfEnvironmentVariable(
            named = "DASHSCOPE_API_KEY",
            matches = ".+",
            disabledReason = "At least one API key must be set for E2E tests")
    void validateTestEnvironment() {
        System.out.println("\n=== Consolidated E2E Test Suite Configuration ===");

        // Check environment variables
        boolean hasOpenAIKey =
                System.getenv("OPENAI_API_KEY") != null
                        && !System.getenv("OPENAI_API_KEY").isEmpty();
        boolean hasDashScopeKey =
                System.getenv("DASHSCOPE_API_KEY") != null
                        && !System.getenv("DASHSCOPE_API_KEY").isEmpty();

        System.out.println("Environment Configuration:");
        System.out.println("  OPENAI_API_KEY: " + (hasOpenAIKey ? "✓ Available" : "✗ Not set"));
        System.out.println(
                "  DASHSCOPE_API_KEY: " + (hasDashScopeKey ? "✓ Available" : "✗ Not set"));

        // Count enabled providers
        long basicProviders = ProviderFactory.getEnabledBasicProviders().count();
        long toolProviders = ProviderFactory.getEnabledToolProviders().count();
        long imageProviders = ProviderFactory.getEnabledImageProviders().count();
        long audioProviders = ProviderFactory.getEnabledAudioProviders().count();
        long multimodalProviders = ProviderFactory.getEnabledMultimodalProviders().count();
        long thinkingProviders = ProviderFactory.getEnabledThinkingProviders().count();
        long videoProviders = ProviderFactory.getEnabledVideoProviders().count();

        System.out.println("\nAvailable Provider Coverage:");
        System.out.println("  Basic Providers: " + basicProviders);
        System.out.println("  Tool Providers: " + toolProviders);
        System.out.println("  Image Providers: " + imageProviders);
        System.out.println("  Audio Providers: " + audioProviders);
        System.out.println("  Multimodal Providers: " + multimodalProviders);
        System.out.println("  Thinking Providers: " + thinkingProviders);
        System.out.println("  Video Providers: " + videoProviders);

        // Provider breakdown
        System.out.println("\nEnabled Providers:");
        ProviderFactory.getEnabledBasicProviders()
                .forEach(
                        provider ->
                                System.out.println(
                                        "  - "
                                                + provider.getProviderName()
                                                + " ("
                                                + provider.getModelName()
                                                + ")"));

        // Validate minimum requirements
        assert basicProviders > 0 : "At least one basic provider must be available";
        assert hasDashScopeKey || hasOpenAIKey : "At least one API key must be configured";

        System.out.println("\n✓ Test environment configuration validated successfully");
    }

    @Test
    @DisplayName("Should report test coverage summary")
    void reportTestCoverageSummary() {
        System.out.println("\n=== Consolidated E2E Test Coverage Summary ===");

        System.out.println("Test Classes and Their Coverage:");
        System.out.println("  1. CoreAgentE2ETest");
        System.out.println("     - Basic conversation flows");
        System.out.println("     - Multi-round conversations");
        System.out.println("     - Mixed tool/text conversations");
        System.out.println("     - Simple tool calling");
        System.out.println("     - Tool error handling");
        System.out.println("     - Memory management");

        System.out.println("\n  2. MultiModalE2ETest");
        System.out.println("     - Image analysis from URLs");
        System.out.println("     - Follow-up questions about images");
        System.out.println("     - Mixed vision/text conversations");
        System.out.println("     - Complete multimodal workflows");
        System.out.println("     - Video analysis (when available)");

        System.out.println("\n  3. ToolSystemE2ETest");
        System.out.println("     - Multiple sequential tool calls");
        System.out.println("     - Complex calculation chains");
        System.out.println("     - Multimodal tool results");
        System.out.println("     - Tool call memory structure validation");
        System.out.println("     - Hook lifecycle verification");

        System.out.println("\n  4. CrossModelCompatibilityE2ETest");
        System.out.println("     - Equivalent questions across providers");
        System.out.println("     - Tool call consistency");
        System.out.println("     - Streaming vs non-streaming equivalence");
        System.out.println("     - Multimodal content consistency");
        System.out.println("     - Error handling consistency");

        System.out.println("\n  5. SpecializedFeaturesE2ETest");
        System.out.println("     - DashScope thinking mode");
        System.out.println("     - Thinking mode with budgets");
        System.out.println("     - Video analysis capabilities");
        System.out.println("     - Complex multimodal reasoning");
        System.out.println("     - Performance with large tool chains");
        System.out.println("     - Tool + thinking combination");

        System.out.println("\nProvider Types Supported:");
        System.out.println(
                "  - OpenAI Native (gpt-5-mini, gpt-5-image-mini, gpt-4o-audio-preview, gpt-4o)");
        System.out.println("  - DashScope Compatible (qwen-plus, qwen-turbo, qwen3-omni-flash)");
        System.out.println("  - Bailian (qwen-omni-turbo)");
        System.out.println(
                "  - DashScope Native (qwen-plus, qwen-vl-max, qwen3-vl-plus, qwen-turbo)");

        System.out.println("\nOriginal E2E Tests Consolidated:");
        System.out.println("  ✓ ReActE2ETest → CoreAgentE2ETest");
        System.out.println("  ✓ AgentE2ETest → CoreAgentE2ETest");
        System.out.println("  ✓ VisionE2ETest → MultiModalE2ETest");
        System.out.println("  ✓ VisionLocalFileE2ETest → MultiModalE2ETest");
        System.out.println("  ✓ AudioE2ETest → MultiModalE2ETest");
        System.out.println("  ✓ DashScopeQwen3VlPlusE2ETest → MultiModalE2ETest");
        System.out.println("  ✓ OpenAICompatibleE2ETest → CrossModelCompatibilityE2ETest");
        System.out.println("  ✓ OpenAIE2ETest → CrossModelCompatibilityE2ETest");
        System.out.println("  ✓ BailianOpenAICompatibleE2ETest → CrossModelCompatibilityE2ETest");
        System.out.println("  ✓ MultipleToolCallsE2ETest → ToolSystemE2ETest");
        System.out.println("  ✓ DashScopeMultimodalToolE2ETest → ToolSystemE2ETest");
        System.out.println("  ✓ DashScopeThinkingE2ETest → SpecializedFeaturesE2ETest");

        System.out.println("\nImprovements Made:");
        System.out.println("  ✓ Reduced from 13 test files to 5 consolidated files");
        System.out.println("  ✓ Eliminated redundant test scenarios");
        System.out.println("  ✓ Dynamic provider configuration based on API keys");
        System.out.println("  ✓ Enhanced input/output validation with intelligent checking");
        System.out.println("  ✓ Better organization by functionality rather than model/API");
        System.out.println("  ✓ Comprehensive cross-model compatibility testing");

        System.out.println("\n✓ Test coverage summary completed");
    }

    /**
     * Utility method to get all available providers for debugging.
     */
    public static void debugAvailableProviders() {
        System.out.println("\n=== Debug: Available Providers ===");

        Stream.of(
                        "Basic Providers: " + ProviderFactory.getEnabledBasicProviders().count(),
                        "Tool Providers: " + ProviderFactory.getEnabledToolProviders().count(),
                        "Image Providers: " + ProviderFactory.getEnabledImageProviders().count(),
                        "Audio Providers: " + ProviderFactory.getEnabledAudioProviders().count(),
                        "Multimodal Providers: "
                                + ProviderFactory.getEnabledMultimodalProviders().count(),
                        "Thinking Providers: "
                                + ProviderFactory.getEnabledThinkingProviders().count(),
                        "Video Providers: " + ProviderFactory.getEnabledVideoProviders().count(),
                        "Multimodal Tool Providers: "
                                + ProviderFactory.getEnabledMultimodalToolProviders().count())
                .forEach(System.out::println);
    }
}
