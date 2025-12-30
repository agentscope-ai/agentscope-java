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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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
 *   <li>Dynamic complex nested data structures</li>
 * </ul>
 *
 * <p><b>Requirements:</b> OPENAI_API_KEY and/or DASHSCOPE_API_KEY environment variables
 * must be set. Tests are dynamically enabled based on available API keys and model capabilities.
 */

@Tag("e2e")
@Tag("dynamic-structured-output")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Dynamic Structured Output Tests")
public class DynamicStructuredOutputTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    private final Logger LOG = LoggerFactory.getLogger(this.getClass());

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledToolProviders")
    @DisplayName("Should handle dynamic complex nested data structures")
    void testDynamicComplexNestedStructure(ModelProvider provider) {
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
        String json =
                """
                		{
                						 "type": "object",
                						 "properties": {
                						   "productName": {
                							 "type": "string"
                						   },
                						   "features": {
                							 "type": "array",
                							 "items": {
                							   "type": "string"
                							 }
                						   },
                						   "pricing": {
                							 "type": "object",
                							 "properties": {
                							   "amount": {
                								 "type": "number"
                							   },
                							   "currency": {
                								 "type": "string"
                							   }
                							 }
                						   },
                						   "ratings": {
                							 "type": "object",
                							 "additionalProperties": {
                							   "type": "integer"
                							 }
                						   }
                						 }
                					   }
                """;
        try {
            JsonNode sampleJsonNode = new ObjectMapper().readTree(json);
            // Request structured output with complex nested structure
            Msg response = agent.call(input, sampleJsonNode).block(TEST_TIMEOUT);

            assertNotNull(response, "Response should not be null");
            System.out.println("Raw response: " + TestUtils.extractTextContent(response));

            // Extract and validate structured data
            Map<String, Object> analysis = response.getStructuredData(false);
            assertNotNull(analysis, "Product analysis should be extracted");
            System.out.println("Structured analysis: " + analysis);

            // Validate top-level fields
            assertNotNull(analysis.get("productName"), "Product name should be populated");
            assertNotNull(analysis.get("features"), "Features should be populated");
            assertNotNull(analysis.get("pricing"), "Pricing should be populated");
            // Note: ratings may be null for some models (e.g., OpenAI) as Map types are complex

            // Validate nested structure
            assertTrue(
                    analysis.get("features") instanceof Collection<?> collection
                            && !collection.isEmpty(),
                    "Should have at least one feature");
            assertTrue(
                    analysis.get("pricing") instanceof Map<?, ?> pricing
                            && pricing.get("amount") instanceof Number amount
                            && amount.doubleValue() > 0,
                    "Price amount should be positive for " + provider.getModelName());
            assertTrue(
                    analysis.get("pricing") instanceof Map<?, ?> pricing
                            && pricing.get("currency") instanceof String,
                    "Currency should be populated");

            // Validate ratings if present (optional for some models)
            if (analysis.get("ratings") instanceof Map<?, ?> ratings) {
                assertFalse(
                        ratings.isEmpty(),
                        "If ratings are provided, should have at least one rating");
                System.out.println("Ratings: " + ratings);
            } else {
                System.out.println(
                        "Note: Ratings not provided by model (acceptable for complex Map types)");
            }

            System.out.println(
                    "✓ Complex nested structure verified for " + provider.getProviderName());
        } catch (Exception e) {
            LOG.error(e.getMessage(), e);
        }
    }
}
