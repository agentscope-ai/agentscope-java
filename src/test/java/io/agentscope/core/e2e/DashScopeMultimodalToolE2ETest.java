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
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for DashScope formatter handling of multimodal tool results.
 *
 * <p>These tests verify that DashScope formatters can properly convert multimodal tool results
 * to text descriptions that non-multimodal models can understand, ensuring the
 * convertToolResultToString functionality works correctly.
 *
 * <p><b>Requirements:</b> DASHSCOPE_API_KEY environment variable must be set
 *
 * <p><b>Test Model:</b> qwen-turbo (non-multimodal model with tool support)
 */
@Tag("e2e")
@Tag("integration")
@Tag("dashscope")
@Tag("multimodal")
@Tag("tool")
@EnabledIfEnvironmentVariable(
        named = "DASHSCOPE_API_KEY",
        matches = ".+",
        disabledReason =
                "DashScope multimodal tool E2E tests require DASHSCOPE_API_KEY environment"
                        + " variable")
@DisplayName("DashScope Multimodal Tool Return E2E Tests")
class DashScopeMultimodalToolE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String MODEL_NAME = "qwen-turbo";

    // Test image URL
    private static final String TEST_IMAGE_URL =
            "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/480px-Cat03.jpg";

    // Test audio URL - using a simple audio file
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    // Test video URL
    private static final String TEST_VIDEO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");
    }

    /** Multimodal tools for testing. */
    public static class MultimodalTools {

        private static final Map<String, String> cache = new ConcurrentHashMap<>();

        @Tool(description = "Get an image of a cat")
        public ToolResultBlock getImage() {
            try {
                // Return a cat image with description
                return ToolResultBlock.of(List.of(
                        TextBlock.builder().text("Here is a cute cat image").build(),
                        ImageBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url(
                                                        "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/480px-Cat03.jpg")
                                                .build())
                                .build()));
            } catch (Exception e) {
                return ToolResultBlock.of(
                        TextBlock.builder()
                                .text("Failed to load image: " + e.getMessage())
                                .build());
            }
        }

        @Tool(description = "Get audio data from a real file")
        public ToolResultBlock getAudio() throws IOException {
            try {
                // Download real audio file
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request =
                        HttpRequest.newBuilder().uri(URI.create(TEST_AUDIO_URL)).build();

                HttpResponse<byte[]> response =
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                byte[] audioData = response.body();

                System.out.println(
                        "Downloaded "
                                + audioData.length
                                + " bytes, base64 length: "
                                + Base64.getEncoder().encodeToString(audioData).length());

                // Return audio with description
                return ToolResultBlock.of(List.of(
                        TextBlock.builder().text("Here is test audio data from real file.").build(),
                        AudioBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .data(Base64.getEncoder().encodeToString(audioData))
                                                .mediaType("audio/wav")
                                                .build())
                                .build()));
            } catch (Exception e) {
                return ToolResultBlock.of(
                        TextBlock.builder()
                                .text("Failed to load audio: " + e.getMessage())
                                .build());
            }
        }

        @Tool(description = "Get both image and audio content")
        public ToolResultBlock getMultimodalContent() {
            try {
                // Return both image and audio
                return ToolResultBlock.of(List.of(
                        TextBlock.builder().text("Here is both image and audio content").build(),
                        ImageBlock.builder()
                                .source(
                                        URLSource.builder()
                                                .url(
                                                        "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/480px-Cat03.jpg")
                                                .build())
                                .build(),
                        AudioBlock.builder()
                                .source(
                                        Base64Source.builder()
                                                .data(
                                                        cache.computeIfAbsent(
                                                                "audio_base64",
                                                                k -> {
                                                                    try {
                                                                        HttpClient client =
                                                                                HttpClient
                                                                                        .newHttpClient();
                                                                        HttpRequest request =
                                                                                HttpRequest
                                                                                        .newBuilder()
                                                                                        .uri(
                                                                                                URI
                                                                                                        .create(
                                                                                                                TEST_AUDIO_URL))
                                                                                        .build();

                                                                        HttpResponse<byte[]>
                                                                                response =
                                                                                        client.send(
                                                                                                request,
                                                                                                HttpResponse
                                                                                                        .BodyHandlers
                                                                                                        .ofByteArray());

                                                                        byte[] audioData =
                                                                                response.body();
                                                                        return Base64.getEncoder()
                                                                                .encodeToString(
                                                                                        audioData);
                                                                    } catch (Exception e) {
                                                                        throw new RuntimeException(
                                                                                e);
                                                                    }
                                                                }))
                                                .mediaType("audio/wav")
                                                .build())
                                .build()));
            } catch (Exception e) {
                return ToolResultBlock.of(
                        TextBlock.builder()
                                .text("Failed to load multimodal content: " + e.getMessage())
                                .build());
            }
        }

        @Tool(description = "Get video content")
        public ToolResultBlock getVideo() {
            try {
                // Return video with description
                return ToolResultBlock.of(List.of(
                        TextBlock.builder().text("Here is a video of a person").build(),
                        VideoBlock.builder()
                                .source(URLSource.builder().url(TEST_VIDEO_URL).build())
                                .build()));
            } catch (Exception e) {
                return ToolResultBlock.of(
                        TextBlock.builder()
                                .text("Failed to load video: " + e.getMessage())
                                .build());
            }
        }
    }

    @Test
    @DisplayName("Should handle tool returning image URL")
    void testToolReturningImageURL() {
        System.out.println("\n=== Test: Tool Returning Image URL ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultimodalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When tools return file paths or URLs,"
                                        + " you MUST summarize and include them in your response.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getImage tool to get a cat image, then summarize the tool's"
                                + " response including any file paths or URLs returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(responseText.length() > 10, "Should have meaningful response");

        // Extract image URL from tool result
        String imageUrl = null;
        for (Msg msg : memory.getMessages()) {
            if (msg.getRole() == MsgRole.TOOL && msg.hasContentBlocks(ToolResultBlock.class)) {
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if ("getImage".equals(toolResult.getName())) {
                    List<ContentBlock> outputs = toolResult.getOutput();
                    for (ContentBlock block : outputs) {
                        if (block instanceof ImageBlock imageBlock) {
                            if (imageBlock.getSource() instanceof URLSource urlSource) {
                                imageUrl = urlSource.getUrl();
                            }
                        }
                    }
                }
            }
        }

        assertNotNull(imageUrl, "Tool should have returned image URL");

        // Verify model's response includes information about the returned file
        assertTrue(
                responseText.toLowerCase().contains("image")
                        || responseText.toLowerCase().contains("cat")
                        || responseText.toLowerCase().contains("url")
                        || responseText.toLowerCase().contains("found")
                        || responseText.toLowerCase().contains("http")
                        || responseText.toLowerCase().contains(".jpg"),
                "Response should mention the image or URL returned by the tool");

        System.out.println("Tool returned image URL: " + imageUrl);
        System.out.println("✓ Tool returning image URL verified - model summarized the file info!");
    }

    @Test
    @DisplayName("Should handle tool returning audio Base64")
    void testToolReturningAudioBase64() {
        System.out.println("\n=== Test: Tool Returning Audio Base64 ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultimodalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When tools return file paths or URLs,"
                                        + " you MUST summarize and include them in your response.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getAudio tool to get test audio, then summarize the tool's"
                                + " response including any file paths or URLs returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(responseText.length() > 10, "Should have meaningful response");

        // Verify model's response includes information about the returned file
        // The model should have received the file path from the tool and mentioned it
        assertTrue(
                responseText.toLowerCase().contains("audio")
                        || responseText.toLowerCase().contains("file")
                        || responseText.toLowerCase().contains("found")
                        || responseText.toLowerCase().contains("wav")
                        || responseText.toLowerCase().contains("path")
                        || responseText.toLowerCase().contains("/tmp/")
                        || responseText.toLowerCase().contains("agentscope_"),
                "Response should mention the audio file information returned by the tool");

        System.out.println(
                "✓ Tool returning audio Base64 verified - model summarized the file info!");
    }

    @Test
    @DisplayName("Should handle tool returning mixed multimodal content")
    void testToolReturningMixedMultimodalContent() {
        System.out.println("\n=== Test: Tool Returning Mixed Multimodal Content ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultimodalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When tools return file paths or URLs,"
                                        + " you MUST summarize and include them in your response.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getMultimodalContent tool to get both image and audio, then"
                                + " summarize the tool's response including any file paths or URLs"
                                + " returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(responseText.length() > 10, "Should have meaningful response");

        // Verify model's response mentions both image and audio file information
        // The model should have received both image URL and audio file path and mentioned them
        boolean mentionsImage =
                responseText.toLowerCase().contains("image")
                        || responseText.toLowerCase().contains("cat")
                        || responseText.toLowerCase().contains("url")
                        || responseText.toLowerCase().contains("http")
                        || responseText.toLowerCase().contains(".jpg");

        boolean mentionsAudio =
                responseText.toLowerCase().contains("audio")
                        || responseText.toLowerCase().contains("file")
                        || responseText.toLowerCase().contains("wav")
                        || responseText.toLowerCase().contains("path")
                        || responseText.toLowerCase().contains("found")
                        || responseText.toLowerCase().contains("/tmp/")
                        || responseText.toLowerCase().contains("agentscope_");

        assertTrue(mentionsImage, "Response should mention the image information");
        assertTrue(mentionsAudio, "Response should mention the audio information");

        System.out.println(
                "✓ Tool returning mixed multimodal content verified - model summarized both file"
                        + " infos!");
    }

    @Test
    @DisplayName("Should handle tool returning video content")
    void testToolReturningVideoContent() {
        System.out.println("\n=== Test: Tool Returning Video Content ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new MultimodalTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultimodalToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When tools return file paths or URLs,"
                                        + " you MUST summarize and include them in your response.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Use the getVideo tool to get video content, then summarize the tool's"
                                + " response including any file paths or URLs returned.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);
        assertTrue(responseText.length() > 10, "Should have meaningful response");

        // Verify model's response mentions video information
        assertTrue(
                responseText.toLowerCase().contains("video")
                        || responseText.toLowerCase().contains("file")
                        || responseText.toLowerCase().contains("url")
                        || responseText.toLowerCase().contains("found")
                        || responseText.toLowerCase().contains("mp4")
                        || responseText.toLowerCase().contains("person"),
                "Response should mention the video information returned by the tool");

        System.out.println(
                "✓ Tool returning video content verified - model summarized the file info!");
    }
}
