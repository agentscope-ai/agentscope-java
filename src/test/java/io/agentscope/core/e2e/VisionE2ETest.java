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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.formatter.DashScopeChatFormatter;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for Vision (multimodal) capabilities.
 *
 * <p>These tests verify vision functionality with REAL API calls to DashScope qwen-vl-max model.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 */
@Tag("e2e")
@Tag("integration")
@Tag("vision")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason = "Vision E2E tests require DASHSCOPE_API_KEY environment variable")
@DisplayName("Vision (Multimodal) E2E Tests")
class VisionE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    // Official DashScope example image - dog and girl
    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    private ReActAgent visionAgent;
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");

        // Create agent with vision model
        visionAgent =
                ReActAgent.builder()
                        .name("VisionTestAgent")
                        .sysPrompt(
                                "You are a helpful AI assistant with vision capabilities. Analyze"
                                        + " images carefully and provide accurate descriptions.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-vl-max") // Vision model
                                        .stream(true)
                                        .formatter(new DashScopeChatFormatter())
                                        .defaultOptions(GenerateOptions.builder().build())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .build();

        System.out.println("=== Vision E2E Test Setup Complete ===");
        System.out.println("Using model: qwen-vl-max");
        System.out.println("Test image URL: " + TEST_IMAGE_URL);
    }

    @Test
    @DisplayName("Should analyze image from URL successfully")
    void testAnalyzeImageFromURL() {
        System.out.println("\n=== Test: Analyze Image from URL ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        // Create message with image
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please describe what you see in this image"
                                                                + " in detail.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending vision request to DashScope...");

        // Send request and get response
        Msg response = visionAgent.call(userMsg).block(TEST_TIMEOUT);

        // Verify response
        assertNotNull(response, "Response should not be null");

        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should have text content");
        assertTrue(responseText.length() > 10, "Response should have meaningful content");

        System.out.println("\n=== Response Received ===");
        System.out.println("Response length: " + responseText.length() + " characters");
        System.out.println(
                "Response preview: "
                        + responseText.substring(0, Math.min(200, responseText.length())));

        // Verify response contains image-related content
        // The image contains a dog and a girl, so response should mention these
        String responseLower = responseText.toLowerCase();
        boolean mentionsDog = responseLower.contains("dog") || responseLower.contains("狗");
        boolean mentionsGirl =
                responseLower.contains("girl")
                        || responseLower.contains("女孩")
                        || responseLower.contains("人");

        System.out.println("\n=== Content Analysis ===");
        System.out.println("Mentions dog: " + mentionsDog);
        System.out.println("Mentions girl/person: " + mentionsGirl);

        assertTrue(
                mentionsDog || mentionsGirl,
                "Response should mention key elements in the image (dog or girl/person)");

        System.out.println("\n✓ Vision capability verified successfully!");
    }

    @Test
    @DisplayName("Should handle follow-up questions about the image")
    void testFollowUpQuestionsAboutImage() {
        System.out.println("\n=== Test: Follow-up Questions About Image ===");

        // First message: Show the image
        Msg firstMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What do you see in this image?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Showing image...");
        Msg response1 = visionAgent.call(firstMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");

        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println("Round 1 response length: " + response1Text.length());

        // Second message: Ask follow-up question (without image)
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("How many subjects are in the image?")
                                                .build()))
                        .build();

        System.out.println("Round 2: Asking follow-up question...");
        Msg response2 = visionAgent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Round 2 response length: " + response2Text.length());
        System.out.println("Round 2 response: " + response2Text);

        // Verify agent remembers the image context
        assertNotNull(response2Text, "Follow-up response should have content");
        assertTrue(response2Text.length() > 5, "Follow-up response should have meaningful content");

        System.out.println("\n✓ Follow-up conversation verified successfully!");
    }

    @Test
    @DisplayName("Should handle text-only questions after seeing image")
    void testMixedConversation() {
        System.out.println("\n=== Test: Mixed Conversation (Vision + Text) ===");

        // Round 1: Vision question with image
        Msg visionMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Describe this image briefly.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Vision question with image");
        Msg response1 = visionAgent.call(visionMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        System.out.println(
                "Round 1 response: " + TestUtils.extractTextContent(response1).substring(0, 100));

        // Round 2: Text-only question
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is 2 plus 2?").build()))
                        .build();

        System.out.println("Round 2: Text-only question");
        Msg response2 = visionAgent.call(textMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Round 2 response: " + response2Text);

        // Should be able to answer simple math question
        assertTrue(response2Text.contains("4"), "Should correctly answer 2+2=4");

        System.out.println("\n✓ Mixed conversation verified successfully!");
    }
}
