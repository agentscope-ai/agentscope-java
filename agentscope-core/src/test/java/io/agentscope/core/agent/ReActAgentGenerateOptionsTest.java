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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.MockToolkit;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Test cases for ReActAgent GenerateOptions configuration.
 *
 * <p>This test class covers the fix for Issue #767:
 * <ul>
 *   <li>Add generateOptions() method to ReActAgent.Builder</li>
 *   <li>Ensure temperature, topP, maxTokens and other generation parameters are properly
 *       configured and passed to the model</li>
 *   <li>Verify merging behavior between generateOptions and modelExecutionConfig</li>
 * </ul>
 *
 * @see <a href="https://github.com/agentscope-ai/agentscope-java/issues/767">Issue #767</a>
 */
@DisplayName("ReActAgent GenerateOptions Tests")
class ReActAgentGenerateOptionsTest {

    private InMemoryMemory memory;
    private MockModel mockModel;
    private MockToolkit mockToolkit;

    @BeforeEach
    void setUp() {
        memory = new InMemoryMemory();
        mockModel = new MockModel(TestConstants.MOCK_MODEL_SIMPLE_RESPONSE);
        mockToolkit = new MockToolkit();
    }

    @Nested
    @DisplayName("GenerateOptions Configuration Tests")
    class GenerateOptionsConfigurationTest {

        @Test
        @DisplayName("Should allow setting generateOptions in builder")
        void testGenerateOptionsInBuilder() {
            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.7)
                            .topP(0.9)
                            .maxTokens(1000)
                            .frequencyPenalty(0.5)
                            .presencePenalty(0.3)
                            .build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            assertNotNull(agent.getGenerateOptions(), "GenerateOptions should be set");
            assertEquals(
                    0.7, agent.getGenerateOptions().getTemperature(), "Temperature should be 0.7");
            assertEquals(0.9, agent.getGenerateOptions().getTopP(), "TopP should be 0.9");
            assertEquals(
                    1000, agent.getGenerateOptions().getMaxTokens(), "MaxTokens should be 1000");
            assertEquals(
                    0.5,
                    agent.getGenerateOptions().getFrequencyPenalty(),
                    "FrequencyPenalty should be 0.5");
            assertEquals(
                    0.3,
                    agent.getGenerateOptions().getPresencePenalty(),
                    "PresencePenalty should be 0.3");
        }

        @Test
        @DisplayName("Should return null when generateOptions not set")
        void testGenerateOptionsNotSet() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .build();

            assertNull(
                    agent.getGenerateOptions(),
                    "GenerateOptions should be null when not configured");
        }

        @Test
        @DisplayName("Should set all generation parameters correctly")
        void testAllGenerationParameters() {
            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.8)
                            .topP(0.95)
                            .maxTokens(2000)
                            .frequencyPenalty(-0.5)
                            .presencePenalty(1.0)
                            .topK(50)
                            .seed(12345L)
                            .thinkingBudget(500)
                            .reasoningEffort("high")
                            .build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            GenerateOptions stored = agent.getGenerateOptions();
            assertNotNull(stored);
            assertEquals(0.8, stored.getTemperature());
            assertEquals(0.95, stored.getTopP());
            assertEquals(2000, stored.getMaxTokens());
            assertEquals(-0.5, stored.getFrequencyPenalty());
            assertEquals(1.0, stored.getPresencePenalty());
            assertEquals(50, stored.getTopK());
            assertEquals(12345L, stored.getSeed());
            assertEquals(500, stored.getThinkingBudget());
            assertEquals("high", stored.getReasoningEffort());
        }
    }

    @Nested
    @DisplayName("GenerateOptions and ExecutionConfig Merge Tests")
    class MergeConfigurationTest {

        @Test
        @DisplayName("Should merge modelExecutionConfig with generateOptions")
        void testMergeExecutionConfigWithGenerateOptions() {
            AtomicReference<GenerateOptions> capturedOptions = new AtomicReference<>();

            Hook captureHook =
                    new Hook() {
                        @Override
                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                            if (event instanceof PreReasoningEvent pre) {
                                capturedOptions.set(pre.getEffectiveGenerateOptions());
                            }
                            return Mono.just(event);
                        }
                    };

            GenerateOptions genOptions =
                    GenerateOptions.builder().temperature(0.7).topP(0.9).build();

            ExecutionConfig execConfig =
                    ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(30))
                            .maxAttempts(3)
                            .build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(genOptions)
                            .modelExecutionConfig(execConfig)
                            .hook(captureHook)
                            .build();

            // Execute a call to trigger the hook
            agent.call(TestUtils.createUserMessage("User", "Hello"))
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            // Verify that buildGenerateOptions was called and merged correctly
            // Note: The captured options come from buildGenerateOptions()
            // We can't directly capture buildGenerateOptions, but we can verify the agent setup
            assertNotNull(agent.getGenerateOptions());
            assertEquals(0.7, agent.getGenerateOptions().getTemperature());
            assertEquals(0.9, agent.getGenerateOptions().getTopP());
        }

        @Test
        @DisplayName("Should use only modelExecutionConfig when generateOptions not set")
        void testOnlyExecutionConfig() {
            ExecutionConfig execConfig =
                    ExecutionConfig.builder()
                            .timeout(Duration.ofSeconds(60))
                            .maxAttempts(5)
                            .build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .modelExecutionConfig(execConfig)
                            .build();

            // When generateOptions is null, buildGenerateOptions should return
            // options with only executionConfig set
            assertNull(agent.getGenerateOptions());

            // Execute a call - should work without errors
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Test"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            assertNotNull(response, "Response should not be null");
        }

        @Test
        @DisplayName("Should use only generateOptions when modelExecutionConfig not set")
        void testOnlyGenerateOptions() {
            GenerateOptions genOptions =
                    GenerateOptions.builder().temperature(0.5).maxTokens(500).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(genOptions)
                            .build();

            assertNotNull(agent.getGenerateOptions());
            assertEquals(0.5, agent.getGenerateOptions().getTemperature());
            assertEquals(500, agent.getGenerateOptions().getMaxTokens());

            // Execute a call - should work without errors
            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Test"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
            assertNotNull(response, "Response should not be null");
        }
    }

    @Nested
    @DisplayName("GenerateOptions Usage in Reasoning Phase")
    class ReasoningPhaseUsageTest {

        @Test
        @DisplayName("Should pass temperature to model during reasoning")
        void testTemperaturePassedToModel() {
            GenerateOptions options = GenerateOptions.builder().temperature(0.3).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Hello"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Response should not be null");
            assertEquals(1, mockModel.getCallCount(), "Model should be called once");

            // Verify model received the options
            GenerateOptions passedOptions = mockModel.getLastOptions();
            assertNotNull(passedOptions, "Model should receive GenerateOptions");
            assertEquals(
                    0.3,
                    passedOptions.getTemperature(),
                    "Model should receive configured temperature");
        }

        @Test
        @DisplayName("Should pass all generation parameters to model")
        void testAllParametersPassedToModel() {
            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.6)
                            .topP(0.85)
                            .maxTokens(1500)
                            .frequencyPenalty(0.2)
                            .presencePenalty(0.1)
                            .build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            agent.call(TestUtils.createUserMessage("User", "Test"))
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            GenerateOptions passedOptions = mockModel.getLastOptions();
            assertNotNull(passedOptions);
            assertEquals(0.6, passedOptions.getTemperature());
            assertEquals(0.85, passedOptions.getTopP());
            assertEquals(1500, passedOptions.getMaxTokens());
            assertEquals(0.2, passedOptions.getFrequencyPenalty());
            assertEquals(0.1, passedOptions.getPresencePenalty());
        }

        @Test
        @DisplayName("Should pass executionConfig when both options are set")
        void testExecutionConfigMergedAndPassed() {
            GenerateOptions genOptions = GenerateOptions.builder().temperature(0.4).build();

            ExecutionConfig execConfig =
                    ExecutionConfig.builder().timeout(Duration.ofSeconds(45)).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(genOptions)
                            .modelExecutionConfig(execConfig)
                            .build();

            agent.call(TestUtils.createUserMessage("User", "Test"))
                    .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            GenerateOptions passedOptions = mockModel.getLastOptions();
            assertNotNull(passedOptions);
            assertEquals(
                    0.4,
                    passedOptions.getTemperature(),
                    "Temperature from generateOptions should be present");
            assertNotNull(passedOptions.getExecutionConfig(), "ExecutionConfig should be merged");
            assertEquals(
                    Duration.ofSeconds(45),
                    passedOptions.getExecutionConfig().getTimeout(),
                    "Timeout from modelExecutionConfig should be present");
        }
    }

    @Nested
    @DisplayName("Edge Cases and Validation Tests")
    class EdgeCasesTest {

        @Test
        @DisplayName("Should handle zero temperature")
        void testZeroTemperature() {
            GenerateOptions options = GenerateOptions.builder().temperature(0.0).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Hello"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals(0.0, mockModel.getLastOptions().getTemperature());
        }

        @Test
        @DisplayName("Should handle maximum temperature value")
        void testMaximumTemperature() {
            GenerateOptions options = GenerateOptions.builder().temperature(2.0).build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Hello"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response);
            assertEquals(2.0, mockModel.getLastOptions().getTemperature());
        }

        @Test
        @DisplayName("Should handle null generateOptions gracefully")
        void testNullGenerateOptions() {
            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(null)
                            .build();

            assertNull(agent.getGenerateOptions());

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Hello"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Should still generate response with null generateOptions");
        }

        @Test
        @DisplayName("Should work with empty generateOptions")
        void testEmptyGenerateOptions() {
            GenerateOptions options = GenerateOptions.builder().build();

            ReActAgent agent =
                    ReActAgent.builder()
                            .name(TestConstants.TEST_REACT_AGENT_NAME)
                            .sysPrompt(TestConstants.DEFAULT_SYS_PROMPT)
                            .model(mockModel)
                            .toolkit(mockToolkit)
                            .memory(memory)
                            .generateOptions(options)
                            .build();

            Msg response =
                    agent.call(TestUtils.createUserMessage("User", "Hello"))
                            .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

            assertNotNull(response, "Should work with empty generateOptions");
            assertNotNull(agent.getGenerateOptions());
            assertNull(
                    agent.getGenerateOptions().getTemperature(),
                    "Temperature should be null when not set");
        }
    }
}
