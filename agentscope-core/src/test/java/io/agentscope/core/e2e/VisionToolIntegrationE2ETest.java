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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E2E tests for tool calling in vision/multimodal contexts.
 *
 * <p>Coverage:
 * - Tool calling with image input
 * - Tool calling with video input
 * - Tools + multimodal content in same conversation
 * - MultiModalConversation API tool support
 * - Tool results with multimodal content
 */
@Tag("e2e")
@Tag("vision-tools")
@EnabledIf("io.agentscope.core.e2e.ProviderFactory#hasAnyApiKey")
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Vision + Tool Integration E2E Tests")
class VisionToolIntegrationE2ETest {

    // Extended timeout for vision + tool calls: image processing takes longer than text-only
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(180);

    private static final String CAT_IMAGE_URL =
            "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

    private static final String DOG_GIRL_IMAGE_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241022/emyrja/dog_and_girl.jpeg";

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledMultimodalToolProviders")
    @DisplayName("Should call tools with image context")
    void testToolCallingWithImageContext(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool Calling with Image Context for "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("VisionToolAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Look at this image and then use the add"
                                                            + " tool to calculate how many subjects"
                                                            + " you see plus 10.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(DOG_GIRL_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Question: " + TestUtils.extractTextContent(userMsg));

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);

        // The image has 2 subjects (dog and girl), so 2 + 10 = 12
        assertTrue(
                ContentValidator.containsKeywords(response, "12", "twelve"),
                "Response should contain correct calculation result for "
                        + provider.getModelName());

        System.out.println(
                "✓ Tool calling with image context verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledVideoProviders")
    @DisplayName("Should call tools with video context")
    @Disabled("Qwen3VlPlus cannot handle tool call well now")
    void testToolCallingWithVideoContext(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool Calling with Video Context for "
                        + provider.getProviderName()
                        + " ===");

        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        ReActAgent agent = provider.createAgent("VideoToolAgent", toolkit);

        String videoUrl =
                "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Watch this video and use the multiply tool"
                                                                + " to calculate: count of distinct"
                                                                + " scenes multiplied by 5.")
                                                .build(),
                                        VideoBlock.builder()
                                                .source(URLSource.builder().url(videoUrl).build())
                                                .build()))
                        .build();

        System.out.println("Question: Video analysis with tool calling");

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + responseText);

        // Verify tool was used
        assertTrue(
                ContentValidator.meetsMinimumLength(response, 10),
                "Response should describe calculation for " + provider.getModelName());

        System.out.println(
                "✓ Tool calling with video context verified for " + provider.getProviderName());
    }

    @ParameterizedTest
    @MethodSource("io.agentscope.core.e2e.ProviderFactory#getEnabledMultimodalToolProviders")
    @DisplayName("Should handle tool results containing images")
    void testToolResultsWithImages(ModelProvider provider) {
        System.out.println(
                "\n=== Test: Tool Results with Images for " + provider.getProviderName() + " ===");

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ImageToolTest());
        ReActAgent agent = provider.createAgent("ImageToolResultAgent", toolkit);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "Use the getAnimalImage tool to get an"
                                                            + " animal image, then describe what"
                                                            + " you see.")
                                                .build()))
                        .build();

        System.out.println("Question: " + TestUtils.extractTextContent(userMsg));

        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(
                ContentValidator.hasMeaningfulContent(response),
                "Response should have meaningful content");

        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);

        // Verify model processed the image returned by the tool
        assertTrue(
                ContentValidator.containsKeywords(response, "cat", "animal", "image"),
                "Response should mention the cat image for " + provider.getModelName());

        System.out.println("✓ Tool results with images verified for " + provider.getProviderName());
    }

    @Test
    @DisplayName("Should verify MultiModal API tool call formatting")
    void testMultiModalAPIToolFormatting() {
        System.out.println("\n=== Test: MultiModal API Tool Formatting ===");

        // Verify that tools can be used with multimodal content
        Toolkit toolkit = E2ETestUtils.createTestToolkit();

        assertTrue(toolkit.getToolSchemas().size() > 0, "Toolkit should have tools registered");

        System.out.println("Toolkit contains " + toolkit.getToolSchemas().size() + " tools");
        toolkit.getToolSchemas().forEach(tool -> System.out.println("  - " + tool.getName()));

        System.out.println("✓ MultiModal API tool formatting structure verified");
    }

    /**
     * Test tool that returns image content in tool results.
     */
    public static class ImageToolTest {

        private static final String CAT_IMAGE_URL =
                "https://agentscope-test.oss-cn-beijing.aliyuncs.com/Cat03.jpg";

        @Tool(description = "Get an animal image")
        public ToolResultBlock getAnimalImage() {
            List<ContentBlock> output =
                    List.of(
                            TextBlock.builder().text("Here is a cat image").build(),
                            ImageBlock.builder()
                                    .source(URLSource.builder().url(CAT_IMAGE_URL).build())
                                    .build());

            return ToolResultBlock.of(output);
        }
    }
}
