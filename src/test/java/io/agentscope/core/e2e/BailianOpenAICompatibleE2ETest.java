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
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
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
 * End-to-end tests for Bailian OpenAI-compatible endpoint multimodal capabilities.
 *
 * <p>These tests verify image and audio functionality with REAL API calls to Bailian
 * using OpenAI-compatible endpoint.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 *
 * <p><b>Test Model:</b> qwen-omni-turbo (OpenAI-compatible multimodal model)
 *
 * <p><b>Endpoint:</b> https://dashscope.aliyuncs.com/compatible-mode/v1
 *
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Image: URL and Base64 formats</li>
 *   <li>Audio: URL and Base64 formats</li>
 * </ul>
 */
@Tag("e2e")
@Tag("integration")
@Tag("bailian")
@Tag("openai-compatible")
@Tag("multimodal")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason =
                "Bailian OpenAI-compatible E2E tests require DASHSCOPE_API_KEY environment"
                        + " variable")
@DisplayName("Bailian OpenAI-Compatible Multimodal E2E Tests")
class BailianOpenAICompatibleE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String BAILIAN_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private static final String MODEL_NAME = "qwen-omni-turbo";

    // Test images from Bailian documentation
    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    // Test audio from Bailian documentation
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    // Base64 test data for image (10x10 red square PNG)
    private static final String TEST_BASE64_IMAGE =
            "iVBORw0KGgoAAAANSUhEUgAAAAoAAAAKCAYAAACNMs+9AAAAFUlEQVR42mP8z8BQz0AEYBxVSF+FABJADveWkH6oAAAAAElFTkSuQmCC";

    private ReActAgent multimodalAgent;
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create agent with Bailian OpenAI-compatible multimodal model
        multimodalAgent =
                ReActAgent.builder()
                        .name("BailianOpenAICompatibleTestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant with multimodal understanding"
                                        + " capabilities. Analyze images and audio carefully and"
                                        + " provide accurate descriptions in Chinese.")
                        .model(
                                OpenAIChatModel.builder()
                                        .apiKey(apiKey)
                                        .baseUrl(BAILIAN_BASE_URL)
                                        .modelName(MODEL_NAME)
                                        .stream(true)
                                        .formatter(new OpenAIChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== Bailian OpenAI-Compatible Multimodal Test Setup Complete ===");
        System.out.println("Using endpoint: " + BAILIAN_BASE_URL);
        System.out.println("Using model: " + MODEL_NAME);
        System.out.println("Test image URL: " + TEST_IMAGE_URL);
        System.out.println("Test audio URL: " + TEST_AUDIO_URL);
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

            System.out.println("\n=== Image Analysis Response (Remote URL) ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Validate response contains meaningful content
            assertTrue(responseText.length() > 10, "Response should be meaningful");

            // Check for image-related content
            boolean hasImageContent =
                    responseText.contains("图片")
                            || responseText.contains("女孩")
                            || responseText.contains("狗")
                            || responseText.contains("海滩")
                            || responseText.contains("image");

            assertTrue(
                    hasImageContent,
                    "Response should contain image analysis content. Actual: "
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
                                        TextBlock.builder().text("请描述这个图像").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(TEST_BASE64_IMAGE)
                                                                .mediaType("image/png")
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending base64 image request to Bailian via OpenAI-compatible API...");

        try {
            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Image Analysis Response (Base64) ===");
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
    @DisplayName("Should analyze audio using remote URL")
    void testAnalyzeAudioWithRemoteUrl() {
        System.out.println("\n=== Test: Analyze Audio with Remote URL ===");

        // Create message with audio URL
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

            System.out.println("\n=== Audio Analysis Response (Remote URL) ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Validate response contains meaningful content about the audio
            assertTrue(responseText.length() > 10, "Response should be meaningful");

            // Check for audio-related content
            boolean hasAudioContent =
                    responseText.contains("音频")
                            || responseText.contains("cherry")
                            || responseText.contains("樱桃")
                            || responseText.contains("audio")
                            || responseText.contains("说");

            assertTrue(
                    hasAudioContent,
                    "Response should contain audio analysis content. Actual: "
                            + responseText.substring(0, Math.min(100, responseText.length())));

            System.out.println("\n✓ Audio analysis with remote URL successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Audio analysis failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should analyze audio using Base64 data")
    void testAnalyzeAudioWithBase64() {
        System.out.println("\n=== Test: Analyze Audio with Base64 Data ===");

        try {
            // Download the real audio file and convert to Base64
            System.out.println("Downloading audio file: " + TEST_AUDIO_URL);
            java.net.URL url = new java.net.URL(TEST_AUDIO_URL);
            java.io.InputStream inputStream = url.openStream();
            byte[] audioBytes = inputStream.readAllBytes();
            inputStream.close();

            String base64Audio =
                    "data:;base64," + java.util.Base64.getEncoder().encodeToString(audioBytes);
            System.out.println(
                    "Downloaded "
                            + audioBytes.length
                            + " bytes, base64 length: "
                            + base64Audio.length());

            // Create message with base64 audio
            Msg userMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder().text("这段音频在说什么？").build(),
                                            AudioBlock.builder()
                                                    .source(
                                                            Base64Source.builder()
                                                                    .data(base64Audio)
                                                                    .mediaType("audio/wav")
                                                                    .build())
                                                    .build()))
                            .build();

            System.out.println(
                    "Sending base64 audio request to Bailian via OpenAI-compatible API...");
            System.out.println(
                    "Using Bailian-compatible format: data:;base64,{base64} with format field");

            // Send request and get response
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // Verify response
            assertNotNull(response, "Response should not be null");

            String responseText = TestUtils.extractTextContent(response);
            assertNotNull(responseText, "Response should have text content");

            System.out.println("\n=== Audio Analysis Response (Base64) ===");
            System.out.println("Response length: " + responseText.length() + " characters");
            System.out.println("Response: " + responseText);

            // Validate response
            assertTrue(responseText.length() > 5, "Response should be meaningful");

            // Check for audio-related content
            boolean hasAudioContent =
                    responseText.contains("音频")
                            || responseText.contains("cherry")
                            || responseText.contains("樱桃")
                            || responseText.contains("audio")
                            || responseText.contains("说");

            if (hasAudioContent) {
                System.out.println("✓ Response contains audio-related content");
            }

            System.out.println("\n✓ Audio analysis with Base64 data successful!");

        } catch (Exception e) {
            System.err.println("\n❌ Base64 audio analysis failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }

    @Test
    @DisplayName("Should verify mixed modality limitation (image + audio)")
    void testMixedMultimodalContent() {
        System.out.println("\n=== Test: Mixed Multimodal Content (Image + Audio) ===");
        System.out.println(
                "Testing qwen-omni-turbo limitation: mixed modality inputs should fail with"
                        + " expected error");

        // Create message with both image and audio (mixed modality)
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("请分析这张图片和这段音频").build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_AUDIO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending mixed modality request (should fail)...");

        try {
            // Attempt to send request - should fail
            Msg response = multimodalAgent.call(userMsg).block(TEST_TIMEOUT);

            // If we get here without exception, the API behavior has changed
            System.err.println(
                    "⚠️ WARNING: Mixed modality request succeeded unexpectedly! API behavior may"
                            + " have changed.");
            System.err.println("Response: " + TestUtils.extractTextContent(response));

            // This is actually unexpected - mixed modality should fail
            throw new AssertionError(
                    "Expected mixed modality to fail, but got successful response. "
                            + "API behavior may have changed.");

        } catch (RuntimeException e) {
            // Check if this is the expected API error about mixed modality
            String errorMessage = e.getMessage();

            if (errorMessage != null && errorMessage.contains("Failed to stream OpenAI API")) {
                // Expected: API should reject mixed modality
                System.out.println("\n=== Expected Error Received ===");
                System.out.println("Error type: " + e.getClass().getSimpleName());
                System.out.println("Error message: " + errorMessage);

                // Verify it's the expected error about mixed modality
                boolean isMixedModalityError =
                        errorMessage.contains("mixed modality")
                                || errorMessage.contains("Multiple inputs")
                                || errorMessage.contains("omni model");

                assertTrue(
                        isMixedModalityError,
                        "Expected mixed modality error, but got different API error: "
                                + errorMessage);

                System.out.println("✓ Mixed modality limitation verified correctly!");
                System.out.println(
                        "💡 Recommendation: Use separate requests for image and audio, or use a"
                                + " different model that supports mixed modality.");
            } else {
                // Unexpected error (network, config, authentication, etc.) - fail the test
                System.err.println(
                        "\n❌ Unexpected error type (not API rejection): " + errorMessage);
                throw e;
            }
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
                                                                    .url(TEST_IMAGE_URL)
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
                            + response2Text.substring(0, Math.min(150, response2Text.length()))
                            + "...");

            // Round 3: Follow-up text question
            System.out.println("\nRound 3: Follow-up question");
            Msg followUpMsg =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .content(
                                    List.of(
                                            TextBlock.builder()
                                                    .text("根据前面的分析，图片和音频各自的主要内容是什么？")
                                                    .build()))
                            .build();

            Msg response3 = multimodalAgent.call(followUpMsg).block(TEST_TIMEOUT);
            assertNotNull(response3);
            String response3Text = TestUtils.extractTextContent(response3);
            System.out.println(
                    "Follow-up response: "
                            + response3Text.substring(0, Math.min(150, response3Text.length()))
                            + "...");

            // Validate conversation continuity
            assertTrue(response1Text.length() > 10, "First response should be meaningful");
            assertTrue(response2Text.length() > 10, "Second response should be meaningful");
            assertTrue(response3Text.length() > 10, "Follow-up response should be meaningful");

            System.out.println("\n✓ Multimodal conversation completed successfully!");

        } catch (Exception e) {
            System.err.println("\n❌ Multimodal conversation failed: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Test failed", e);
        }
    }
}
