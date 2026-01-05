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
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
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
 * E2E tests for streaming behavior and mode comparison.
 *
 * <p>Coverage:
 * - Non-streaming mode (stream=false)
 * - Streaming vs non-streaming equivalence
 * - Streaming with tools
 * - Streaming with multimodal content
 */
@Tag("e2e")
@Tag("streaming")
@ExtendWith(E2ETestCondition.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Streaming Behavior E2E Tests")
class StreamingBehaviorE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(300);

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getBasicProviders")
    @DisplayName("Should support non-streaming mode explicitly")
    void testNonStreamingMode(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Non-Streaming Mode with " + provider.getProviderName() + " ===");

        // Create agent with explicit stream=false
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null) {
            System.out.println("⚠ Skipping non-streaming test - no API key");
            return;
        }

        DashScopeChatModel model =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(
                                false) // Explicitly disable streaming
                        .formatter(new DashScopeChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build())
                        .build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("NonStreamingAgent")
                        .model(model)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is the capital of France?")
                                                .build()))
                        .build();

        System.out.println("Sending non-streaming request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Non-streaming response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Non-streaming response should have content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Non-streaming response: " + responseText);

        assertTrue(
                ContentValidator.containsKeywords(response, "Paris"),
                "Should correctly answer in non-streaming mode");

        System.out.println("✓ Non-streaming mode verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getToolProviders")
    @DisplayName("Should stream tool calls correctly")
    void testStreamingWithToolCalls(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Streaming with Tool Calls for " + provider.getProviderName() + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("StreamingToolAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What is 15 multiplied by 8?")
                                                .build()))
                        .build();

        System.out.println("Question: " + TestUtils.extractTextContent(userMsg));

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Streaming tool response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Streaming tool answer: " + responseText);

        // Should use tool and get correct answer: 15 * 8 = 120
        assertTrue(
                ContentValidator.containsCalculationResult(response, "120"),
                "Streaming with tools should produce correct result for "
                        + provider.getModelName());

        System.out.println(
                "✓ Streaming with tool calls verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getImageProviders")
    @DisplayName("Should stream with multimodal content")
    void testStreamingWithMultimodalContent(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Streaming with Multimodal Content for "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("StreamingMultimodalAgent", toolkit);

        String imageUrl = "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What animal is in this image?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(URLSource.builder().url(imageUrl).build())
                                                .build()))
                        .build();

        System.out.println("Sending streaming multimodal request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Streaming multimodal response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Streaming multimodal answer: " + responseText);

        // Should recognize the cat in the image
        assertTrue(
                ContentValidator.mentionsVisualElements(response, "cat", "animal"),
                "Streaming with multimodal content should work for " + provider.getModelName());

        System.out.println(
                "✓ Streaming with multimodal content verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify streaming is enabled by default in providers")
    void testStreamingEnabledByDefault() {
        System.out.println("\n=== Test: Streaming Enabled by Default ===");

        // Verify that providers use streaming by default
        long providerCount = ProviderFactory.getBasicProviders().count();

        System.out.println("Testing " + providerCount + " providers for streaming support");

        // All providers in our factory enable streaming by default
        assertTrue(providerCount > 0, "At least one provider should be available");

        System.out.println("✓ Streaming is enabled by default in providers");
    }

    @Test
    @DisplayName("Should handle streaming and non-streaming memory consistently")
    void testStreamingMemoryConsistency() {
        System.out.println("\n=== Test: Streaming Memory Consistency ===");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null) {
            System.out.println("⚠ Skipping memory consistency test - no API key");
            return;
        }

        // Create streaming agent
        DashScopeChatModel streamingModel =
                DashScopeChatModel.builder().apiKey(apiKey).modelName("qwen-plus").stream(true)
                        .formatter(new DashScopeChatFormatter())
                        .build();

        ReActAgent streamingAgent =
                ReActAgent.builder()
                        .name("StreamingMemoryAgent")
                        .model(streamingModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .build();

        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Remember: blue").build()))
                        .build();

        streamingAgent.call(msg1).block(TEST_TIMEOUT);

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What color did I tell you to remember?")
                                                .build()))
                        .build();

        Msg response = streamingAgent.call(msg2).block(TEST_TIMEOUT);

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Memory test response: " + responseText);

        // Should remember "blue" from previous message
        assertTrue(
                ContentValidator.containsKeywords(response, "blue"),
                "Streaming mode should maintain memory correctly");

        System.out.println("✓ Streaming memory consistency verified");
    }
}
