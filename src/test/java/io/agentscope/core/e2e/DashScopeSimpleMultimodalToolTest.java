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
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Simple test for DashScope formatter handling of multimodal tool results.
 *
 * <p>This test verifies that DashScope formatters can properly convert multimodal tool results
 * to text descriptions that non-multimodal models can understand. This tests the
 * convertToolResultToString functionality with a regular text model.
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
                "DashScope multimodal tool test requires DASHSCOPE_API_KEY environment variable")
@DisplayName("DashScope Simple Multimodal Tool Test")
class DashScopeSimpleMultimodalToolTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(120);
    private static final String MODEL_NAME = "qwen-turbo";
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("DASHSCOPE_API_KEY");
        assertNotNull(apiKey, "DASHSCOPE_API_KEY must be set");
    }

    /** Simple tools for testing multimodal returns. */
    public static class TestTools {

        @Tool(description = "Get an image URL")
        public ToolResultBlock getImageUrl() {
            return ToolResultBlock.of(
                    List.of(
                            TextBlock.builder()
                                    .text(
                                            "Here is an image of a cat found at:"
                                                    + " https://example.com/cat.jpg")
                                    .build(),
                            ImageBlock.builder()
                                    .source(
                                            URLSource.builder()
                                                    .url("https://example.com/cat.jpg")
                                                    .build())
                                    .build()));
        }

        @Tool(description = "Get audio data")
        public ToolResultBlock getAudioData() throws IOException, InterruptedException {
            // Download real audio file
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(TEST_AUDIO_URL)).build();

            HttpResponse<byte[]> response =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] audioData = response.body();

            System.out.println("Downloaded " + audioData.length + " bytes of audio data");

            return ToolResultBlock.of(
                    List.of(
                            TextBlock.builder().text("Here is some audio data").build(),
                            AudioBlock.builder()
                                    .source(
                                            Base64Source.builder()
                                                    .data(
                                                            Base64.getEncoder()
                                                                    .encodeToString(audioData))
                                                    .mediaType("audio/wav")
                                                    .build())
                                    .build()));
        }
    }

    @Test
    @DisplayName("Should handle tool returning image with text description")
    void testToolReturningImage() {
        System.out.println("\n=== Test: Tool Returning Image ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TestTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When you use tools, analyze what they"
                                        + " return and provide a summary to the user.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Please use the getImageUrl tool and tell me what it returns.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);

        // Verify response is meaningful and not just repeating the input
        assertTrue(
                responseText.length() > 10
                        && !responseText.equals(TestUtils.extractTextContent(input)),
                "Response should be meaningful and different from input");

        // The response should mention something about the tool result
        assertTrue(
                responseText.toLowerCase().contains("image")
                        || responseText.toLowerCase().contains("cat")
                        || responseText.toLowerCase().contains("tool")
                        || responseText.toLowerCase().contains("return"),
                "Response should mention the image or tool result");

        System.out.println("✓ Test passed: Model handled tool returning image data!");
    }

    @Test
    @DisplayName("Should handle tool returning audio with text description")
    void testToolReturningAudio() {
        System.out.println("\n=== Test: Tool Returning Audio ===");

        DashScopeChatModel model =
                DashScopeChatModel.builder().modelName(MODEL_NAME).apiKey(apiKey).stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TestTools());
        InMemoryMemory memory = new InMemoryMemory();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .sysPrompt(
                                "You are a helpful assistant. When you use tools, analyze what they"
                                        + " return and provide a summary to the user.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Please use the getAudioData tool and tell me what it returns.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);

        // Verify response is meaningful and not just repeating the input
        assertTrue(
                responseText.length() > 10
                        && !responseText.equals(TestUtils.extractTextContent(input)),
                "Response should be meaningful and different from input");

        // The response should mention something about the tool result
        assertTrue(
                responseText.toLowerCase().contains("audio")
                        || responseText.toLowerCase().contains("sound")
                        || responseText.toLowerCase().contains("tool")
                        || responseText.toLowerCase().contains("return"),
                "Response should mention the audio or tool result");

        System.out.println("✓ Test passed: Model handled tool returning audio data!");
    }
}
