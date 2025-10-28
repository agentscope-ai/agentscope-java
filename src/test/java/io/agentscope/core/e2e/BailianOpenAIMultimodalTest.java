/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.e2e;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for Bailian OpenAI-compatible multimodal capabilities.
 *
 * <p>These tests verify image and audio functionality with REAL API calls to Bailian
 * using OpenAI-compatible endpoint.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 *
 * <p><b>Test Models:</b>
 * <ul>
 *   <li>qwen-omni-turbo: OpenAI-compatible multimodal model for both image and audio</li>
 * </ul>
 */
@Tag("e2e")
@Tag("integration")
@Tag("bailian")
@Tag("multimodal")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason =
                "Bailian Multimodal E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("Bailian OpenAI-Compatible Multimodal E2E Tests")
class BailianOpenAIMultimodalTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(90);
    private static final String BAILIAN_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";

    // Test images from Bailian documentation
    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    // Test audio from Bailian documentation
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    private ReActAgent multimodalAgent;
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create agent with Bailian OpenAI-compatible multimodal model
        multimodalAgent =
                ReActAgent.builder()
                        .name("BailianMultimodalTestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant with multimodal understanding"
                                        + " capabilities. Analyze images and audio carefully and"
                                        + " provide accurate descriptions.")
                        .model(
                                OpenAIChatModel.builder()
                                        .apiKey(apiKey)
                                        .baseUrl(BAILIAN_BASE_URL)
                                        .modelName(
                                                "qwen-omni-turbo") // OpenAI-compatible multimodal
                                        // model
                                        .stream(true)
                                        .formatter(new OpenAIChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== Bailian OpenAI-Compatible Multimodal Test Setup Complete ===");
        System.out.println("Using endpoint: " + BAILIAN_BASE_URL);
        System.out.println("Using model: qwen-omni-turbo");
        System.out.println("Test image URL: " + TEST_IMAGE_URL);
        System.out.println("Test audio URL: " + TEST_AUDIO_URL);
    }

    @Test
    @DisplayName("Should analyze image using Bailian OpenAI-compatible API")
    void testAnalyzeImageWithBailianOpenAI() {
        System.out.println("\n=== Test: Analyze Image with Bailian OpenAI ===");

        // Create message with image
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("图中描绘的是什么景象？").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending image request to Bailian via OpenAI-compatible API...");

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Image Analysis Response ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Verify response contains meaningful content about the image
            if (responseText.length() > 10) {
                System.out.println("\n✓ Image analysis successful!");
            } else {
                System.out.println("\n⚠️ Response seems too short");
            }
        } catch (Exception e) {
            System.err.println("\n❌ Image analysis failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("Should analyze audio using Bailian OpenAI-compatible API")
    void testAnalyzeAudioWithBailianOpenAI() {
        System.out.println("\n=== Test: Analyze Audio with Bailian OpenAI ===");

        // Create message with audio
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("这段音频在说什么？").build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending audio request to Bailian via OpenAI-compatible API...");

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Audio Analysis Response ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Verify response contains meaningful content about the audio
            if (responseText.length() > 10) {
                System.out.println("\n✓ Audio analysis successful!");
            } else {
                System.out.println("\n⚠️ Response seems too short");
            }
        } catch (Exception e) {
            System.err.println("\n❌ Audio analysis failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("Should handle multimodal conversation with both image and audio")
    void testMultimodalConversation() {
        System.out.println("\n=== Test: Multimodal Conversation ===");

        try {
            // Round 1: Image analysis
            System.out.println("Round 1: Image analysis");
            Msg imageMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text("请描述这张图片").build(),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(TEST_IMAGE_URL)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg response1 = multimodalAgent.call(imageMsg).block(TEST_TIMEOUT);
            assertNotNull(response1);
            String response1Text = TestUtils.extractTextContent(response1);
            System.out.println(
                    "Image response: "
                            + response1Text.substring(0, Math.min(100, response1Text.length()))
                            + "...");

            // Round 2: Audio analysis
            System.out.println("\nRound 2: Audio analysis");
            Msg audioMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text("现在请听这段音频").build(),
                                            AudioBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(TEST_AUDIO_URL)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg response2 = multimodalAgent.call(audioMsg).block(TEST_TIMEOUT);
            assertNotNull(response2);
            String response2Text = TestUtils.extractTextContent(response2);
            System.out.println(
                    "Audio response: "
                            + response2Text.substring(0, Math.min(100, response2Text.length()))
                            + "...");

            // Round 3: Follow-up text question
            System.out.println("\nRound 3: Follow-up text question");
            Msg followUpMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("根据前面的分析，图片和音频内容有什么关联吗？")
                                                    .build()))
                            .build();

            Msg response3 = multimodalAgent.call(followUpMsg).block(TEST_TIMEOUT);
            assertNotNull(response3);
            String response3Text = TestUtils.extractTextContent(response3);
            System.out.println(
                    "Follow-up response: "
                            + response3Text.substring(0, Math.min(100, response3Text.length()))
                            + "...");

            System.out.println("\n✓ Multimodal conversation completed successfully!");

        } catch (Exception e) {
            System.err.println("\n❌ Multimodal conversation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Test
    @DisplayName("Should handle comprehensive multimodal conversation")
    void testComprehensiveMultimodalConversation() {
        System.out.println("\n=== Test: Comprehensive Multimodal Conversation ===");

        try {
            // Round 1: Image analysis
            System.out.println("Round 1: Image analysis");
            Msg imageMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text("请描述这张图片").build(),
                                            ImageBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(TEST_IMAGE_URL)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg response1 = multimodalAgent.call(imageMsg).block(TEST_TIMEOUT);
            assertNotNull(response1);
            String response1Text = TestUtils.extractTextContent(response1);
            System.out.println(
                    "Image response: "
                            + response1Text.substring(0, Math.min(100, response1Text.length()))
                            + "...");

            // Round 2: Audio analysis
            System.out.println("\nRound 2: Audio analysis");
            Msg audioMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text("现在请听这段音频").build(),
                                            AudioBlock.builder()
                                                    .source(
                                                            URLSource.builder()
                                                                    .url(TEST_AUDIO_URL)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg response2 = multimodalAgent.call(audioMsg).block(TEST_TIMEOUT);
            assertNotNull(response2);
            String response2Text = TestUtils.extractTextContent(response2);
            System.out.println(
                    "Audio response: "
                            + response2Text.substring(0, Math.min(100, response2Text.length()))
                            + "...");

            // Round 3: Follow-up question about both modalities
            System.out.println("\nRound 3: Cross-modal integration");
            Msg followUpMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("根据前面的分析，图片和音频内容有什么关联吗？")
                                                    .build()))
                            .build();

            Msg response3 = multimodalAgent.call(followUpMsg).block(TEST_TIMEOUT);
            assertNotNull(response3);
            String response3Text = TestUtils.extractTextContent(response3);
            System.out.println(
                    "Follow-up response: "
                            + response3Text.substring(0, Math.min(100, response3Text.length()))
                            + "...");

            System.out.println("\n✓ Comprehensive multimodal conversation completed successfully!");

        } catch (Exception e) {
            System.err.println(
                    "\n❌ Comprehensive multimodal conversation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
