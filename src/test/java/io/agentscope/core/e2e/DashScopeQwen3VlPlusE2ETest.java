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
import io.agentscope.core.formatter.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for DashScope qwen3-vl-plus multimodal capabilities.
 *
 * <p>These tests verify image and video functionality with REAL API calls to DashScope
 * using qwen3-vl-plus model.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 *
 * <p><b>Test Model:</b> qwen3-vl-plus (supports both image and video)
 */
@Tag("e2e")
@Tag("integration")
@Tag("dashscope")
@Tag("multimodal")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason =
                "DashScope qwen3-vl-plus E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("DashScope qwen3-vl-plus Multimodal E2E Tests")
class DashScopeQwen3VlPlusE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String MODEL_NAME = "qwen3-vl-plus";

    // Test images - using reliable URLs
    private static final String TEST_IMAGE_URL_1 =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";
    private static final String TEST_IMAGE_URL_2 =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/cat.jpg";

    // Test video - using reliable URLs
    private static final String TEST_VIDEO_URL_1 =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    // Base64 test data (larger image to meet model requirements: at least 10x10 pixels)
    // This is a small 10x10 red square PNG image
    private static final String TEST_BASE64_IMAGE =
            "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAAFUlEQVR42mP8z8BQz0AEYBxVSF+FABJADveWkH6oAAAAAElFTkSuQmCC";

    private ReActAgent multimodalAgent;
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create agent with DashScope qwen3-vl-plus multimodal model
        multimodalAgent =
                ReActAgent.builder()
                        .name("DashScopeQwen3VlPlusTestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant with multimodal understanding"
                                        + " capabilities. Analyze images and videos carefully and"
                                        + " provide accurate descriptions in Chinese.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName(MODEL_NAME)
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== DashScope qwen3-vl-plus Multimodal Test Setup Complete ===");
        System.out.println("Using model: " + MODEL_NAME);
        System.out.println("Test image URL 1: " + TEST_IMAGE_URL_1);
        System.out.println("Test image URL 2: " + TEST_IMAGE_URL_2);
        System.out.println("Test video URL: " + TEST_VIDEO_URL_1);
    }

    @Test
    @DisplayName("Should analyze image using remote URL")
    void testAnalyzeImageWithRemoteUrl() {
        System.out.println("\n=== Test: Analyze Image with Remote URL ===");

        // Create message with image URL
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("请详细描述这张图片的内容").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL_1)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending image request to DashScope qwen3-vl-plus...");

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

            // Validate response contains meaningful content about the image
            assertTrue(responseText.length() > 10, "Response should be meaningful");

            // Check for expected content indicators
            boolean hasRelevantContent =
                    responseText.contains("女孩")
                            || responseText.contains("狗")
                            || responseText.contains("图片")
                            || responseText.contains("image")
                            || responseText.contains("描述");

            assertTrue(
                    hasRelevantContent,
                    "Response should contain relevant image analysis content. Actual: "
                            + responseText.substring(0, Math.min(100, responseText.length())));

            System.out.println("\n✓ Image analysis with remote URL successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Image analysis failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should analyze image using Base64 data")
    void testAnalyzeImageWithBase64() {
        System.out.println("\n=== Test: Analyze Image with Base64 Data ===");

        // Create message with base64 image
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("请描述这个简单的图像").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(TEST_BASE64_IMAGE)
                                                                .mediaType("image/png")
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending base64 image request to DashScope qwen3-vl-plus...");

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Base64 Image Analysis Response ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Validate response
            assertTrue(responseText.length() > 5, "Response should be meaningful");

            System.out.println("\n✓ Image analysis with Base64 data successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Base64 image analysis failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should analyze video using remote URL")
    void testAnalyzeVideoWithRemoteUrl() {
        System.out.println("\n=== Test: Analyze Video with Remote URL ===");

        // Create message with video URL
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("请详细描述这个视频的内容").build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_VIDEO_URL_1)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending video request to DashScope qwen3-vl-plus...");
        System.out.println("Video URL: " + TEST_VIDEO_URL_1);

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Video Analysis Response ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Full response:\n" + responseText);

            // Validate response contains meaningful content about the video
            assertTrue(responseText.length() > 10, "Response should be meaningful");

            // Check for video-related content indicators
            boolean hasVideoContent =
                    responseText.contains("视频")
                            || responseText.contains("足球")
                            || responseText.contains("运动")
                            || responseText.contains("video")
                            || responseText.contains("football")
                            || responseText.contains("场景");

            assertTrue(
                    hasVideoContent,
                    "Response should contain relevant video analysis content. Actual: "
                            + responseText.substring(0, Math.min(100, responseText.length())));

            System.out.println("\n✓ Video analysis with remote URL successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Video analysis failed: " + e.getMessage());

            // Check for common error types
            String errorMessage = e.getMessage().toLowerCase();
            if (errorMessage.contains("video") || errorMessage.contains("multimodal")) {
                System.err.println("💡 This appears to be a video format issue");
            } else if (errorMessage.contains("quota")) {
                System.err.println("💡 This appears to be a quota issue");
            } else if (errorMessage.contains("model")) {
                System.err.println("💡 This appears to be a model issue");
            }
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should handle multiple images in single message")
    void testMultipleImages() {
        System.out.println("\n=== Test: Multiple Images Analysis ===");

        // Create message with one URL image and one Base64 image
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("请分析这两张图片的内容，第一张是URL图片，第二张是Base64图片")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL_1)
                                                                .build())
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(TEST_BASE64_IMAGE)
                                                                .mediaType("image/png")
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending multiple images request to DashScope qwen3-vl-plus...");

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Multiple Images Analysis Response ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Validate response
            assertTrue(
                    responseText.length() > 20,
                    "Response should be meaningful for multiple images");

            // Check that response mentions multiple images
            boolean mentionsMultiple =
                    responseText.contains("第一张")
                            || responseText.contains("第二张")
                            || responseText.contains("两张")
                            || responseText.contains("图片1")
                            || responseText.contains("图片2");

            // Not strictly required but good to have
            if (mentionsMultiple) {
                System.out.println("✓ Response correctly handles multiple images");
            } else {
                System.out.println(
                        "⚠️ Response doesn't explicitly mention multiple images, but this may be"
                                + " acceptable");
            }

            System.out.println("\n✓ Multiple images analysis successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Multiple images analysis failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should handle multimodal conversation with follow-up questions")
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
                                                                    .url(TEST_IMAGE_URL_1)
                                                                    .build())
                                                    .build()))
                            .build();

            Msg response1 = multimodalAgent.call(imageMsg).block(TEST_TIMEOUT);
            assertNotNull(response1);
            String response1Text = TestUtils.extractTextContent(response1);
            System.out.println(
                    "Image response: "
                            + response1Text.substring(0, Math.min(150, response1Text.length()))
                            + "...");

            // Round 2: Follow-up text question about the image
            System.out.println("\nRound 2: Follow-up question");
            Msg followUpMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("基于前面的分析，图片中的人物或动物有什么特点？")
                                                    .build()))
                            .build();

            Msg response2 = multimodalAgent.call(followUpMsg).block(TEST_TIMEOUT);
            assertNotNull(response2);
            String response2Text = TestUtils.extractTextContent(response2);
            System.out.println(
                    "Follow-up response: "
                            + response2Text.substring(0, Math.min(150, response2Text.length()))
                            + "...");

            // Validate conversation continuity
            assertTrue(response1Text.length() > 10, "First response should be meaningful");
            assertTrue(response2Text.length() > 10, "Follow-up response should be meaningful");

            // Check if follow-up references previous analysis (optional but good)
            boolean referencesPrevious =
                    response2Text.contains("前面")
                            || response2Text.contains("上述")
                            || response2Text.contains("图片")
                            || response2Text.contains("根据");

            if (referencesPrevious) {
                System.out.println("✓ Follow-up question shows good conversation continuity");
            } else {
                System.out.println("⚠️ Follow-up could be better at referencing previous analysis");
            }

            System.out.println("\n✓ Multimodal conversation completed successfully!");

        } catch (Exception e) {
            System.err.println("\n❌ Multimodal conversation failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should verify model name routing works correctly")
    void testModelNameRouting() {
        System.out.println("\n=== Test: Model Name Routing Verification ===");

        // Test that qwen3-vl-plus correctly routes to multimodal API
        // This is a meta-test to verify our model name detection logic works

        DashScopeChatModel model = (DashScopeChatModel) multimodalAgent.getModel();

        // Verify the model is correctly configured
        assertNotNull(model, "Model should not be null");
        assertTrue(
                model.getModelName().contains("-vl"),
                "Model name should contain '-vl' to trigger multimodal API: "
                        + model.getModelName());

        System.out.println("✓ Model name routing verification passed: " + model.getModelName());

        // Simple text test to ensure basic functionality
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("你好，请简单介绍一下你的能力").build()))
                        .build();

        try {
            Msg response = multimodalAgent.call(textMsg).block(TEST_TIMEOUT);
            assertNotNull(response, "Text response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Text response should have content");
            assertTrue(responseText.length() > 5, "Text response should be meaningful");

            System.out.println("✓ Basic text functionality works correctly");
            System.out.println(
                    "Text response: "
                            + responseText.substring(0, Math.min(100, responseText.length()))
                            + "...");

        } catch (Exception e) {
            System.err.println("\n❌ Basic text functionality failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
}
