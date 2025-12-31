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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consolidated E2E tests for structured output functionality.
 *
 * <p>Tests structured output generation across various scenarios including:
 * <ul>
 *   <li>Basic single-round structured output</li>
 *   <li>Structured output combined with tool calling</li>
 *   <li>Multi-round conversation followed by structured output</li>
 *   <li>Complex nested data structures</li>
 *   <li>Structured output from existing memory without new input</li>
 *   <li>Memory cleanup after structured output generation</li>
 * </ul>
 *
 * <p><b>Requirements:</b> OPENAI_API_KEY and/or DASHSCOPE_API_KEY environment variables
 * must be set. Tests are dynamically enabled based on available API keys and model capabilities.
 */
@Tag("e2e")
@Tag("structured-output")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Structured Output E2E Tests")
class StructuredOutputE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private final Logger LOG = LoggerFactory.getLogger(this.getClass());

    // ==================== Data Structure Definitions ====================

    /** Simple weather information structure. */
    public static class WeatherInfo {
        public String location;
        public String temperature;
        public String condition;

        @Override
        public String toString() {
            return "WeatherInfo{"
                    + "location='"
                    + location
                    + '\''
                    + ", temperature='"
                    + temperature
                    + '\''
                    + ", condition='"
                    + condition
                    + '\''
                    + '}';
        }
    }

    /** Calculation report with inputs and results. */
    public static class CalculationReport {
        public String operation;
        public List<Integer> inputs;
        public int result;
        public String explanation;

        @Override
        public String toString() {
            return "CalculationReport{"
                    + "operation='"
                    + operation
                    + '\''
                    + ", inputs="
                    + inputs
                    + ", result="
                    + result
                    + ", explanation='"
                    + explanation
                    + '\''
                    + '}';
        }
    }

    /** Complex nested product analysis structure. */
    public static class ProductAnalysis {
        public String productName;
        public List<String> features;
        public PriceInfo pricing;
        public Map<String, Integer> ratings;

        @Override
        public String toString() {
            return "ProductAnalysis{"
                    + "productName='"
                    + productName
                    + '\''
                    + ", features="
                    + features
                    + ", pricing="
                    + pricing
                    + ", ratings="
                    + ratings
                    + '}';
        }
    }

    /** Nested price information. */
    public static class PriceInfo {
        public double amount;
        public String currency;

        @Override
        public String toString() {
            return "PriceInfo{" + "amount=" + amount + ", currency='" + currency + '\'' + '}';
        }
    }

    /** User profile for multi-round conversation test. */
    public static class UserProfile {
        public String name;
        public int age;
        public String favoriteColor;
        public List<String> hobbies;

        @Override
        public String toString() {
            return "UserProfile{"
                    + "name='"
                    + name
                    + '\''
                    + ", age="
                    + age
                    + ", favoriteColor='"
                    + favoriteColor
                    + '\''
                    + ", hobbies="
                    + hobbies
                    + '}';
        }
    }

    // ==================== Test Methods ====================

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should return basic structured output in single round")
    void testBasicStructuredOutput(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Basic Structured Output with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("WeatherAgent", toolkit);

        // Ask for weather information with clear structure request
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "What's the weather like in San Francisco today? Please provide the"
                                + " location, temperature, and condition.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        // Request structured output
        Msg response = agent.call(input, WeatherInfo.class).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        System.out.println("Raw response: " + TestUtils.extractTextContent(response));

        // Extract and validate structured data
        WeatherInfo result = response.getStructuredData(WeatherInfo.class);
        assertNotNull(result, "Structured data should be extracted");
        System.out.println("Structured output: " + result);

        // Validate fields are populated
        assertNotNull(result.location, "Location should be populated");
        assertNotNull(result.temperature, "Temperature should be populated");
        assertNotNull(result.condition, "Condition should be populated");
        assertTrue(result.location.length() > 0, "Location should not be empty for " + provider);

        System.out.println("✓ Basic structured output verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should combine tool calling with structured output")
    void testStructuredOutputWithToolCalling(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Structured Output with Tool Calling - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("CalculatorAgent", toolkit);

        // Request calculation with structured output
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please calculate 25 plus 17 using the add tool, then provide a structured"
                                + " report including the operation, inputs, result, and a brief"
                                + " explanation.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        // Request structured output
        Msg response = agent.call(input, CalculationReport.class).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        System.out.println("Raw response: " + TestUtils.extractTextContent(response));

        // Extract and validate structured data
        CalculationReport result = response.getStructuredData(CalculationReport.class);
        assertNotNull(result, "Structured data should be extracted");
        System.out.println("Structured output: " + result);

        // Validate calculation fields
        assertNotNull(result.operation, "Operation should be populated");
        assertNotNull(result.inputs, "Inputs should be populated");
        assertTrue(result.inputs.size() >= 2, "Should have at least 2 inputs");
        assertEquals(42, result.result, "Result should be 42 (25+17)");
        assertNotNull(result.explanation, "Explanation should be populated");

        // Verify tool was called in memory
        List<Msg> memory = agent.getMemory().getMessages();
        boolean hasToolRelatedMessage =
                memory.stream()
                        .anyMatch(
                                m ->
                                        m.getRole() == MsgRole.ASSISTANT
                                                || m.getRole() == MsgRole.TOOL);
        assertTrue(
                hasToolRelatedMessage,
                "Memory should contain tool-related messages for " + provider.getModelName());

        System.out.println(
                "✓ Structured output with tool calling verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should generate structured output after multi-round conversation")
    void testStructuredOutputAfterMultiRound(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Structured Output After Multi-Round - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ProfileAgent", toolkit);

        // Round 1: Collect name
        Msg nameMsg = TestUtils.createUserMessage("User", "My name is Alice.");
        System.out.println("Round 1: " + TestUtils.extractTextContent(nameMsg));
        Msg response1 = agent.call(nameMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        System.out.println("Response 1: " + TestUtils.extractTextContent(response1));

        // Round 2: Collect age
        Msg ageMsg = TestUtils.createUserMessage("User", "I am 28 years old.");
        System.out.println("Round 2: " + TestUtils.extractTextContent(ageMsg));
        Msg response2 = agent.call(ageMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);
        System.out.println("Response 2: " + TestUtils.extractTextContent(response2));

        // Round 3: Collect favorite color
        Msg colorMsg = TestUtils.createUserMessage("User", "My favorite color is blue.");
        System.out.println("Round 3: " + TestUtils.extractTextContent(colorMsg));
        Msg response3 = agent.call(colorMsg).block(TEST_TIMEOUT);
        assertNotNull(response3);
        System.out.println("Response 3: " + TestUtils.extractTextContent(response3));

        // Round 4: Collect hobbies
        Msg hobbiesMsg =
                TestUtils.createUserMessage("User", "I enjoy reading, hiking, and photography.");
        System.out.println("Round 4: " + TestUtils.extractTextContent(hobbiesMsg));
        Msg response4 = agent.call(hobbiesMsg).block(TEST_TIMEOUT);
        assertNotNull(response4);
        System.out.println("Response 4: " + TestUtils.extractTextContent(response4));

        // Final round: Request structured summary
        Msg summaryRequest =
                TestUtils.createUserMessage(
                        "User",
                        "Please create a structured user profile based on the information I"
                                + " provided.");
        System.out.println("Summary request: " + TestUtils.extractTextContent(summaryRequest));

        Msg structuredResponse = agent.call(summaryRequest, UserProfile.class).block(TEST_TIMEOUT);

        assertNotNull(structuredResponse, "Structured response should not be null");
        System.out.println("Raw response: " + TestUtils.extractTextContent(structuredResponse));

        // Extract and validate structured data
        UserProfile profile = structuredResponse.getStructuredData(UserProfile.class);
        assertNotNull(profile, "User profile should be extracted");
        System.out.println("Structured profile: " + profile);

        // Validate profile contains information from conversation
        assertNotNull(profile.name, "Name should be populated");
        assertTrue(profile.age > 0, "Age should be positive");
        assertNotNull(profile.favoriteColor, "Favorite color should be populated");
        assertNotNull(profile.hobbies, "Hobbies should be populated");
        assertTrue(profile.hobbies.size() > 0, "Should have at least one hobby");

        // Validate context retention
        assertTrue(
                profile.name.toLowerCase().contains("alice"),
                "Name should contain 'alice' for " + provider.getModelName());

        System.out.println(
                "✓ Multi-round structured output verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should handle complex nested data structures")
    void testComplexNestedStructure(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        if (provider.getModelName().startsWith("gemini")) {
            // Gemini cannot handle this case well
            return;
        }
        System.out.println(
                "\n=== Test: Complex Nested Structure - " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("AnalystAgent", toolkit);

        // Request complex product analysis
        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Analyze the iPhone 16 Pro. Provide: product name, a list of key features,"
                                + " pricing information (amount and currency), and ratings from"
                                + " different sources (e.g., TechRadar: 90, CNET: 85, Verge: 88).");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        // Request structured output with complex nested structure
        Msg response = agent.call(input, ProductAnalysis.class).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        System.out.println("Raw response: " + TestUtils.extractTextContent(response));

        // Extract and validate structured data
        ProductAnalysis analysis = response.getStructuredData(ProductAnalysis.class);
        assertNotNull(analysis, "Product analysis should be extracted");
        System.out.println("Structured analysis: " + analysis);

        // Validate top-level fields
        assertNotNull(analysis.productName, "Product name should be populated");
        assertNotNull(analysis.features, "Features should be populated");
        assertNotNull(analysis.pricing, "Pricing should be populated");
        // Note: ratings may be null for some models (e.g., OpenAI) as Map types are complex

        // Validate nested structure
        assertTrue(analysis.features.size() > 0, "Should have at least one feature");
        assertTrue(
                analysis.pricing.amount > 0,
                "Price amount should be positive for " + provider.getModelName());
        assertNotNull(analysis.pricing.currency, "Currency should be populated");

        // Validate ratings if present (optional for some models)
        if (analysis.ratings != null) {
            assertTrue(
                    analysis.ratings.size() > 0,
                    "If ratings are provided, should have at least one rating");
            System.out.println("Ratings: " + analysis.ratings);
        } else {
            System.out.println(
                    "Note: Ratings not provided by model (acceptable for complex Map types)");
        }

        System.out.println("✓ Complex nested structure verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should generate structured output from existing memory without new input")
    void testStructuredOutputWithoutNewInput(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Structured Output Without New Input - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("SummaryAgent", toolkit);

        // Pre-populate memory with conversation
        Msg msg1 =
                TestUtils.createUserMessage(
                        "User", "The weather in San Francisco is sunny and 72 degrees.");
        agent.call(msg1).block(TEST_TIMEOUT);

        System.out.println("Memory pre-populated with weather information");
        System.out.println(
                "Memory size before structured output: " + agent.getMemory().getMessages().size());

        // Request structured output without providing new input message
        // Agent should use existing memory to generate structured output
        Msg structuredResponse = agent.call(WeatherInfo.class).block(TEST_TIMEOUT);

        assertNotNull(structuredResponse, "Structured response should not be null");
        System.out.println("Raw response: " + TestUtils.extractTextContent(structuredResponse));

        // Extract and validate structured data
        WeatherInfo info = structuredResponse.getStructuredData(WeatherInfo.class);
        assertNotNull(info, "Weather info should be extracted");
        System.out.println("Structured output: " + info);

        // Validate fields are populated from memory
        assertNotNull(info.location, "Location should be populated from memory");
        assertNotNull(info.temperature, "Temperature should be populated from memory");

        System.out.println(
                "✓ Structured output without new input verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should properly cleanup memory after structured output generation")
    void testStructuredOutputMemoryCleanup(ModelProvider provider) {
        assumeTrue(
                provider.supportsToolCalling(),
                "Skipping test: " + provider.getProviderName() + " does not support tool calling");

        System.out.println(
                "\n=== Test: Memory Cleanup After Structured Output - "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("CleanupAgent", toolkit);

        // Initial conversation
        Msg input =
                TestUtils.createUserMessage(
                        "User", "The temperature in New York is 68 degrees and it's cloudy.");
        System.out.println("Input: " + TestUtils.extractTextContent(input));

        int memorySizeBeforeStructuredOutput = agent.getMemory().getMessages().size();
        System.out.println("Memory size before: " + memorySizeBeforeStructuredOutput);

        // Request structured output
        Msg response = agent.call(input, WeatherInfo.class).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");

        // Check memory after structured output
        int memorySizeAfter = agent.getMemory().getMessages().size();
        System.out.println("Memory size after: " + memorySizeAfter);

        // Memory should contain: user input + final structured output
        // The temporary generate_response tool calls should be cleaned up
        List<Msg> finalMemory = agent.getMemory().getMessages();
        System.out.println("\nFinal memory structure:");
        for (int i = 0; i < finalMemory.size(); i++) {
            Msg msg = finalMemory.get(i);
            System.out.println("  [" + i + "] Role: " + msg.getRole() + ", Name: " + msg.getName());
        }

        // Verify structured output is valid
        WeatherInfo info = response.getStructuredData(WeatherInfo.class);
        assertNotNull(info, "Weather info should be extracted");
        System.out.println("Structured output: " + info);

        // Memory should be reasonably sized (not bloated with temporary tool calls)
        assertTrue(
                memorySizeAfter <= memorySizeBeforeStructuredOutput + 5,
                "Memory should not be excessively bloated for " + provider.getModelName());

        System.out.println("✓ Memory cleanup verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify tool provider availability for structured output tests")
    void testToolProviderAvailability() {
        System.out.println("\n=== Test: Tool Provider Availability ===");

        long enabledToolProviders = ProviderFactory.getEnabledToolProviders().count();

        System.out.println("Enabled tool providers: " + enabledToolProviders);

        // At least one tool provider should be available if API keys are set
        assertTrue(
                enabledToolProviders > 0,
                "At least one tool provider should be enabled (check OPENAI_API_KEY or"
                        + " DASHSCOPE_API_KEY)");

        System.out.println("✓ Tool provider availability verified");
    }
}
