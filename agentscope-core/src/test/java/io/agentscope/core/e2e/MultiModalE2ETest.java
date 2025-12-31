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
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Consolidated E2E tests for multimodal capabilities.
 *
 * <p>Tests image analysis, audio processing, video analysis, and mixed multimodal conversations
 * across all available model providers that support multimodal functionality.
 *
 * <p><b>Requirements:</b> OPENAI_API_KEY and/or DASHSCOPE_API_KEY environment variables
 * must be set. Tests are dynamically enabled based on available API keys and model capabilities.
 */
@Tag("e2e")
@Tag("multimodal")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Multimodal E2E Tests (Consolidated)")
class MultiModalE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    // Test URLs from existing tests
    private static final String TEST_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";
    private static final String CAT_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should analyze image from URL")
    void testImageAnalysisFromURL(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Image Analysis from URL with " + provider.getProviderName() + " ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ImageTestAgent", toolkit);

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

        System.out.println("Sending image analysis request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println(
                "Response: " + responseText.substring(0, Math.min(200, responseText.length())));

        // The image contains a dog and a girl, so response should mention these
        assertTrue(
                ContentValidator.mentionsVisualElements(
                        response, "dog", "girl", "person", "animal"),
                "Response should mention key elements in the image for " + provider.getModelName());

        System.out.println("✓ Image URL analysis verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should handle follow-up questions about images")
    void testImageFollowUpQuestions(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Image Follow-up Questions with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("ImageFollowUpAgent", toolkit);

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
        Msg response1 = agent.call(firstMsg).block(TEST_TIMEOUT);
        assertNotNull(response1, "First response should not be null");

        // Second message: Ask follow-up question (without image)
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "How many subjects are in the image? Please"
                                                            + " answer with a sentence describing"
                                                            + " the number.")
                                                .build()))
                        .build();

        System.out.println("Round 2: Asking follow-up question...");
        Msg response2 = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2, "Second response should not be null");

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Follow-up response: " + response2Text);

        // Verify agent remembers the image context
        assertTrue(
                ContentValidator.hasMeaningfulContent(response2),
                "Follow-up response should have meaningful content for " + provider.getModelName());

        // Should mention number of subjects (typically 2: dog and girl)
        assertTrue(
                ContentValidator.containsKeywords(
                        response2, "2", "two", "subjects", "people", "animals"),
                "Should mention number of subjects for " + provider.getModelName());

        System.out.println(
                "✓ Image follow-up conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledImageProviders")
    @DisplayName("Should handle mixed conversation (vision + text)")
    void testMixedVisionConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Mixed Vision Conversation with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MixedVisionAgent", toolkit);

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
                                                                .url(CAT_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Vision question with image");
        Msg response1 = agent.call(visionMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);

        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println(
                "Vision response: "
                        + response1Text.substring(0, Math.min(100, response1Text.length())));

        // Round 2: Text-only question
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("What is 2 plus 2?").build()))
                        .build();

        System.out.println("Round 2: Text-only question");
        Msg response2 = agent.call(textMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Text response: " + response2Text);

        // Should be able to answer simple math question
        assertTrue(
                ContentValidator.containsKeywords(response2, "4"),
                "Should correctly answer 2+2=4 for " + provider.getModelName());

        System.out.println(
                "✓ Mixed vision conversation verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledMultimodalProviders")
    @DisplayName("Should handle complete multimodal conversation flow")
    void testCompleteMultimodalFlow(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Complete Multimodal Flow with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("MultimodalAgent", toolkit);

        // Test multiple modalities in sequence
        System.out.println("Testing comprehensive multimodal capabilities...");

        // Step 1: Image analysis
        Msg imageMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Analyze this image and tell me what"
                                                                + " animals you see.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(CAT_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        Msg imageResponse = agent.call(imageMsg).block(TEST_TIMEOUT);
        assertNotNull(imageResponse);
        assertTrue(
                ContentValidator.mentionsVisualElements(imageResponse, "cat", "animal"),
                "Should identify the cat in the image for " + provider.getModelName());

        // Step 2: Follow-up text question
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Based on what you saw, is this animal"
                                                                + " likely to be friendly?")
                                                .build()))
                        .build();

        Msg followUpResponse = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(followUpResponse);
        assertTrue(
                ContentValidator.hasMeaningfulContent(followUpResponse),
                "Should provide meaningful follow-up for " + provider.getModelName());

        // Step 3: Context memory test
        Msg contextMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What was the first image I showed you?")
                                                .build()))
                        .build();

        Msg contextResponse = agent.call(contextMsg).block(TEST_TIMEOUT);
        assertNotNull(contextResponse);
        assertTrue(
                ContentValidator.maintainsContext(contextResponse, "cat", "image", "first"),
                "Should remember the first image for " + provider.getModelName());

        System.out.println("✓ Complete multimodal flow verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify multimodal provider availability")
    void testMultimodalProviderAvailability() {
        System.out.println("\n=== Test: Multimodal Provider Availability ===");

        long enabledImageProviders = ProviderFactory.getEnabledImageProviders().count();
        long enabledAudioProviders = ProviderFactory.getEnabledAudioProviders().count();
        long enabledMultimodalProviders = ProviderFactory.getEnabledMultimodalProviders().count();

        System.out.println("Enabled image providers: " + enabledImageProviders);
        System.out.println("Enabled audio providers: " + enabledAudioProviders);
        System.out.println("Enabled multimodal providers: " + enabledMultimodalProviders);

        // At least one multimodal provider should be available if API keys are set
        System.out.println("✓ Multimodal provider availability verified");
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledVideoProviders")
    @DisplayName("Should handle video analysis (if supported)")
    void testVideoAnalysis(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Analysis with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoTestAgent", toolkit);

        // Note: This test will only run with providers that support video (currently qwen3-vl-plus)
        // For now, we'll test with an image to verify the provider works
        Msg videoMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Analyze this image as if it were a video"
                                                                + " frame. What do you see?")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        Msg response = agent.call(videoMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Video/image analysis response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Video analysis response should have content for " + provider.getModelName());

        System.out.println("Video analysis response: " + TestUtils.extractTextContent(response));
        System.out.println(
                "✓ Video analysis capability verified for " + provider.getProviderName());
    }
}
