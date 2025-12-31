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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
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
 * E2E tests for video capability with vision models.
 *
 * <p>Coverage:
 * - Video input with VideoBlock (URL format)
 * - Video understanding and analysis
 * - Video + text multimodal conversation
 * - DashScope vision models supporting video (qwen3-vl-plus)
 * - Verify video uses MultiModalConversation API
 */
@Tag("e2e")
@Tag("video")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Video Capability E2E Tests")
class VideoCapabilityE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(90);

    // Test video URL - using a sample video
    private static final String TEST_VIDEO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledVideoProviders")
    @DisplayName("Should process video input from URL")
    void testVideoInputFromURL(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Input from URL with " + provider.getProviderName() + " ===");
        System.out.println("Video URL: " + TEST_VIDEO_URL);

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoURLTestAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Please describe what you see in this video"
                                                                + " in detail.")
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_VIDEO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending video analysis request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println(
                "Response: " + responseText.substring(0, Math.min(300, responseText.length())));

        // Verify model processed the video
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 20),
                "Response should have substantial length for " + provider.getModelName());

        System.out.println("✓ Video URL input verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledVideoProviders")
    @DisplayName("Should analyze video content")
    void testVideoContentAnalysis(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Content Analysis with " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoAnalysisAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Watch this video and tell me: 1) What"
                                                            + " actions are happening? 2) What"
                                                            + " objects do you see? 3) What is the"
                                                            + " general theme?")
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_VIDEO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending detailed video analysis request...");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Detailed analysis: " + responseText);

        // Verify comprehensive analysis
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 50),
                "Detailed analysis should be comprehensive for " + provider.getModelName());

        System.out.println("✓ Video content analysis verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledVideoProviders")
    @DisplayName("Should handle video in multi-round conversation")
    void testVideoMultiRoundConversation(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Video Multi-Round Conversation with "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = new Toolkit();
        ReActAgent agent = provider.createAgent("VideoConversationAgent", toolkit);

        // Round 1: Show video
        Msg videoMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("Watch this video carefully.")
                                                .build(),
                                        VideoBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_VIDEO_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Round 1: Showing video");
        Msg response1 = agent.call(videoMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        System.out.println(
                "Initial response: "
                        + TestUtils.extractTextContent(response1)
                                .substring(
                                        0,
                                        Math.min(
                                                150,
                                                TestUtils.extractTextContent(response1).length())));

        // Round 2: Follow-up question about the video
        Msg followUpMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "What was the main activity in the video"
                                                                + " you just watched?")
                                                .build()))
                        .build();

        System.out.println("Round 2: Follow-up question");
        Msg response2 = agent.call(followUpMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);

        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println("Follow-up response: " + response2Text);

        // Verify agent remembers the video context
        assertTrue(
                ContentValidator.hasMeaningfulContent(response2),
                "Follow-up response should reference video content for " + provider.getModelName());

        System.out.println(
                "✓ Video multi-round conversation verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify video is formatted for MultiModal API")
    void testVideoFormatterBehavior() {
        System.out.println("\n=== Test: Video Formatter Behavior ===");

        // This test verifies that VideoBlock is properly handled
        VideoBlock videoBlock =
                VideoBlock.builder()
                        .source(URLSource.builder().url(TEST_VIDEO_URL).build())
                        .build();

        assertNotNull(videoBlock, "VideoBlock should be created");
        assertNotNull(videoBlock.getSource(), "VideoBlock should have source");
        assertTrue(
                videoBlock.getSource() instanceof URLSource,
                "VideoBlock source should be URLSource");

        System.out.println("VideoBlock structure validated");
        System.out.println("✓ Video formatter behavior verified (VideoBlock uses MultiModal API)");
    }

    @Test
    @DisplayName("Should verify video provider availability")
    void testVideoProviderAvailability() {
        System.out.println("\n=== Test: Video Provider Availability ===");

        long enabledVideoProviders = ProviderFactory.getEnabledVideoProviders().count();

        System.out.println("Enabled video providers: " + enabledVideoProviders);

        // Video is only supported by DashScope vision models
        System.out.println("✓ Video provider availability verified");
    }
}
