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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.providers.ModelProvider;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
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
 * E2E tests for ExecutionConfig (timeout and retry).
 *
 * <p>Tests model execution configuration including timeouts, retries, and backoff strategies.
 */
@Tag("e2e")
@Tag("execution-config")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Execution Config E2E Tests")
class ExecutionConfigE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    // Slower models that need extended timeout
    private static final java.util.Set<String> SLOW_MODELS =
            java.util.Set.of("qwen-omni-turbo", "qwen-omni-turbo-latest");

    /**
     * Get appropriate timeout for the given model.
     * Slower models (e.g., qwen-omni-turbo) get an extended timeout.
     */
    private Duration getTimeoutForModel(String modelName) {
        if (SLOW_MODELS.stream().anyMatch(modelName::contains)) {
            return Duration.ofMinutes(5);
        }
        return TEST_TIMEOUT;
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should complete request within default timeout")
    void testDefaultTimeout(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Default Timeout with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("TimeoutAgent", toolkit);

        Msg input = TestUtils.createUserMessage("User", "What is 1 + 1?");
        Duration timeout = getTimeoutForModel(provider.getModelName());
        System.out.println("Using timeout: " + timeout + " for model: " + provider.getModelName());
        Msg response = agent.call(input).block(timeout);

        assertNotNull(response, "Should complete within timeout");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println("✓ Default timeout verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should use custom model execution config")
    void testCustomModelExecutionConfig(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Custom Model Execution Config with "
                        + provider.getProviderName()
                        + " ===");

        // Create custom execution config with reasonable timeout
        ExecutionConfig customConfig =
                ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(2))
                        .maxAttempts(2)
                        .initialBackoff(Duration.ofSeconds(1))
                        .backoffMultiplier(2.0)
                        .build();

        Toolkit toolkit = new Toolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("CustomConfigAgent")
                        .model(provider.createAgent("temp", toolkit).getModel())
                        .toolkit(toolkit)
                        .modelExecutionConfig(customConfig)
                        .build();

        Msg input = TestUtils.createUserMessage("User", "Tell me a short joke.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should complete with custom config");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println(
                "✓ Custom model execution config verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify ExecutionConfig builder patterns")
    void testExecutionConfigBuilderPatterns() {
        System.out.println("\n=== Test: ExecutionConfig Builder Patterns ===");

        // Test default configs
        ExecutionConfig modelDefaults = ExecutionConfig.MODEL_DEFAULTS;
        assertNotNull(modelDefaults, "MODEL_DEFAULTS should exist");
        System.out.println("MODEL_DEFAULTS timeout: " + modelDefaults.getTimeout());
        System.out.println("MODEL_DEFAULTS maxAttempts: " + modelDefaults.getMaxAttempts());

        ExecutionConfig toolDefaults = ExecutionConfig.TOOL_DEFAULTS;
        assertNotNull(toolDefaults, "TOOL_DEFAULTS should exist");
        System.out.println("TOOL_DEFAULTS timeout: " + toolDefaults.getTimeout());
        System.out.println("TOOL_DEFAULTS maxAttempts: " + toolDefaults.getMaxAttempts());

        // Test custom config
        ExecutionConfig custom =
                ExecutionConfig.builder()
                        .timeout(Duration.ofMinutes(3))
                        .maxAttempts(5)
                        .initialBackoff(Duration.ofMillis(500))
                        .backoffMultiplier(1.5)
                        .build();

        assertNotNull(custom, "Custom config should be created");
        assertTrue(custom.getTimeout().toMinutes() == 3, "Timeout should be 3 minutes");
        assertTrue(custom.getMaxAttempts() == 5, "Max attempts should be 5");

        System.out.println("✓ ExecutionConfig builder patterns verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should handle long-running requests with generous timeout")
    void testLongRunningRequestWithGenerousTimeout(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Long Running Request with " + provider.getProviderName() + " ===");

        // Use a generous timeout for complex requests
        ExecutionConfig generousConfig =
                ExecutionConfig.builder().timeout(Duration.ofMinutes(5)).maxAttempts(1).build();

        Toolkit toolkit = new Toolkit();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("LongRunningAgent")
                        .model(provider.createAgent("temp", toolkit).getModel())
                        .toolkit(toolkit)
                        .modelExecutionConfig(generousConfig)
                        .build();

        // A request that might take longer
        Msg input =
                TestUtils.createUserMessage(
                        "User", "Explain the concept of artificial intelligence in 3 sentences.");
        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Should complete long-running request");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have content for " + provider.getModelName());

        System.out.println(
                "✓ Long-running request with generous timeout verified for "
                        + provider.getProviderName());
    }
}
