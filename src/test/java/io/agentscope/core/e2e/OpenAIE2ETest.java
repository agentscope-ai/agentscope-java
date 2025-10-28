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
import io.agentscope.core.formatter.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * End-to-end tests for OpenAI API.
 *
 * <p>These tests verify all OpenAI capabilities including Chat, Tool Use, and Multimodal (Image +
 * Audio) with REAL API calls to OpenAI.
 *
 * <p><b>Requirements:</b>
 *
 * <ul>
 *   <li>OPENAI_API_KEY environment variable must be set (required)
 *   <li>OPENAI_BASE_URL environment variable (optional, defaults to OpenAI official endpoint)
 * </ul>
 *
 * <p><b>Models Used:</b>
 *
 * <ul>
 *   <li>Chat: gpt-5-mini
 *   <li>Image: gpt-5-image-mini
 *   <li>Audio: gpt-4o-audio-preview
 * </ul>
 */
@Tag("e2e")
@Tag("integration")
@Tag("openai")
@EnabledIfEnvironmentVariable(
        named = "OPENAI_API_KEY",
        matches = ".+",
        disabledReason = "OpenAI E2E tests require OPENAI_API_KEY environment variable")
@DisplayName("OpenAI E2E Tests")
class OpenAIE2ETest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);

    // Model names
    private static final String CHAT_MODEL = "openai/gpt-5-mini";
    private static final String IMAGE_MODEL = "openai/gpt-5-image-mini";
    private static final String AUDIO_MODEL = "openai/gpt-4o-audio-preview";

    // Test image URL - public test image
    private static final String TEST_IMAGE_URL =
            "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/480px-Cat03.jpg";

    // Test audio URL - public test audio
    private static final String TEST_AUDIO_URL =
            "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20250211/tixcef/cherry.wav";

    // Small 1x1 red pixel PNG in Base64
    private static final String TEST_IMAGE_BASE64 =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==";

    private String apiKey;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("OPENAI_API_KEY");
        baseUrl = System.getenv("OPENAI_BASE_URL");

        assertNotNull(apiKey, "OPENAI_API_KEY must be set");

        System.out.println("=== OpenAI E2E Test Setup Complete ===");
        System.out.println("Base URL: " + (baseUrl != null ? baseUrl : "OpenAI default"));
        System.out.println("Chat Model: " + CHAT_MODEL);
        System.out.println("Image Model: " + IMAGE_MODEL);
        System.out.println("Audio Model: " + AUDIO_MODEL);
    }

    // ========== Chat Tests ==========

    @Test
    @DisplayName("Should work with basic chat in streaming mode")
    void testBasicChatStreaming() {
        System.out.println("\n=== Test: Basic Chat Streaming ===");

        Model model = createChatModel(true);
        ReActAgent agent = createBasicAgent("ChatStreamingAgent", model);

        Msg input = TestUtils.createUserMessage("User", "What is the capital of France?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text content");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.toLowerCase().contains("paris"), "Should mention Paris");
    }

    @Test
    @DisplayName("Should work with basic chat in non-streaming mode")
    void testBasicChatNonStreaming() {
        System.out.println("\n=== Test: Basic Chat Non-Streaming ===");

        Model model = createChatModel(false);
        ReActAgent agent = createBasicAgent("ChatNonStreamingAgent", model);

        Msg input = TestUtils.createUserMessage("User", "What is 2 + 2?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have text content");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.contains("4"), "Should answer 4");
    }

    @Test
    @DisplayName("Should handle multi-turn conversation")
    void testMultiTurnConversation() {
        System.out.println("\n=== Test: Multi-Turn Conversation ===");

        Model model = createChatModel(true);
        ReActAgent agent = createBasicAgent("ConversationAgent", model);

        // Turn 1
        Msg input1 = TestUtils.createUserMessage("User", "My favorite color is blue.");
        System.out.println("Turn 1: " + TestUtils.extractTextContent(input1));
        Msg response1 = agent.call(input1).block(TEST_TIMEOUT);
        System.out.println("Response 1: " + TestUtils.extractTextContent(response1));
        assertNotNull(response1);

        // Turn 2
        Msg input2 = TestUtils.createUserMessage("User", "What is my favorite color?");
        System.out.println("Turn 2: " + TestUtils.extractTextContent(input2));
        Msg response2 = agent.call(input2).block(TEST_TIMEOUT);
        System.out.println("Response 2: " + TestUtils.extractTextContent(response2));

        assertNotNull(response2);
        String answer2 = TestUtils.extractTextContent(response2);
        assertTrue(
                answer2.toLowerCase().contains("blue"),
                "Should remember favorite color from previous turn");

        System.out.println("✓ Multi-turn conversation verified!");
    }

    // ========== Tool Use Tests ==========

    @Test
    @DisplayName("Should work with tool calling in streaming mode")
    void testToolCallingStreaming() {
        System.out.println("\n=== Test: Tool Calling Streaming ===");

        Model model = createChatModel(true);
        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant that MUST use tools for any"
                                    + " calculations. Always use the appropriate math tools (add,"
                                    + " multiply, factorial) instead of calculating directly.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please use the multiply tool to calculate: What is 15 multiplied by 8?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        assertTrue(response.hasContentBlocks(TextBlock.class), "Should have final answer");

        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.contains("120"), "Answer should contain 120");

        // Verify tool was called
        verifyToolCall(memory, "multiply");
        System.out.println("✓ Tool calling verified!");
    }

    @Test
    @DisplayName("Should work with tool calling in non-streaming mode")
    void testToolCallingNonStreaming() {
        System.out.println("\n=== Test: Tool Calling Non-Streaming ===");

        Model model = createChatModel(false);
        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant that MUST use tools for any"
                                    + " calculations. Always use the appropriate math tools (add,"
                                    + " multiply, factorial) instead of calculating directly.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User", "Please use the add tool to calculate: What is 25 plus 17?");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);
        assertTrue(answerText.contains("42"), "Answer should contain 42");

        // Verify tool was called
        verifyToolCall(memory, "add");
        System.out.println("✓ Tool calling verified!");
    }

    @Test
    @DisplayName("Should handle multiple tool calls")
    void testMultipleToolCalls() {
        System.out.println("\n=== Test: Multiple Tool Calls ===");

        Model model = createChatModel(true);
        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("MultiToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant that MUST use tools for any"
                                    + " calculations. Always use the appropriate math tools (add,"
                                    + " multiply, factorial) instead of calculating directly.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(5)
                        .build();

        Msg input =
                TestUtils.createUserMessage(
                        "User",
                        "Please use the available tools: First use the add tool to add 10 and 20,"
                                + " then use the multiply tool to multiply the result by 3.");
        System.out.println("Question: " + TestUtils.extractTextContent(input));

        Msg response = agent.call(input).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String answerText = TestUtils.extractTextContent(response);
        System.out.println("Answer: " + answerText);

        // Expected: (10+20)*3 = 90
        assertTrue(
                answerText.contains("90") || answerText.contains("ninety"),
                "Answer should contain 90");

        System.out.println("✓ Multiple tool calls verified!");
    }

    // ========== Image Multimodal Tests ==========

    @Test
    @DisplayName("Should analyze image from URL")
    void testImageAnalysisFromURL() {
        System.out.println("\n=== Test: Image Analysis from URL ===");
        System.out.println("Image URL: " + TEST_IMAGE_URL);

        Model model = createImageModel(true);
        ReActAgent agent = createBasicAgent("ImageURLAgent", model);

        Msg userMsg =
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

        System.out.println("Sending image URL request...");
        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should have text content");
        assertTrue(responseText.length() > 10, "Response should have meaningful content");

        System.out.println(
                "Response: " + responseText.substring(0, Math.min(200, responseText.length())));
        System.out.println("✓ Image URL analysis verified!");
    }

    @Test
    @DisplayName("Should handle image analysis with tool calling")
    void testImageWithToolCalling() {
        System.out.println("\n=== Test: Image with Tool Calling ===");

        Model model = createImageModel(true);
        Toolkit toolkit = E2ETestUtils.createTestToolkit();
        Memory memory = new InMemoryMemory();
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ImageToolAgent")
                        .sysPrompt(
                                "You are a helpful assistant with vision and tool capabilities. For"
                                        + " any calculations, you MUST use the available math tools"
                                        + " instead of calculating directly.")
                        .model(model)
                        .toolkit(toolkit)
                        .memory(memory)
                        .maxIters(3)
                        .build();

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text(
                                                        "First analyze the image briefly, then you"
                                                            + " MUST use the add tool to calculate"
                                                            + " 5 + 7. Do not calculate directly.")
                                                .build(),
                                        ImageBlock.builder()
                                                .source(
                                                        URLSource.builder()
                                                                .url(TEST_IMAGE_URL)
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending image + tool request...");
        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        System.out.println("Response: " + responseText);

        // Should have used the tool
        assertTrue(responseText.contains("12"), "Should contain result 12");

        System.out.println("✓ Image with tool calling verified!");
    }

    // ========== Audio Multimodal Tests ==========
    @Test
    @DisplayName("Should analyze audio from Base64")
    void testAudioAnalysisFromBase64() throws IOException {
        System.out.println("\n=== Test: Audio Analysis from Base64 ===");

        Model model = createAudioModel(true);
        ReActAgent agent = createBasicAgent("AudioBase64Agent", model);

        // Download the real audio file and convert to Base64
        System.out.println("Downloading audio file: " + TEST_AUDIO_URL);
        URL url = new URL(TEST_AUDIO_URL);
        var inputStream = url.openStream();
        byte[] audioBytes = inputStream.readAllBytes();
        inputStream.close();

        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
        System.out.println(
                "Downloaded "
                        + audioBytes.length
                        + " bytes, base64 length: "
                        + base64Audio.length());

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder().text("Analyze this audio.").build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(base64Audio)
                                                                .mediaType("wav")
                                                                .build())
                                                .build()))
                        .build();

        System.out.println("Sending audio Base64 request...");
        Msg response = agent.call(userMsg).block(TEST_TIMEOUT);

        assertNotNull(response, "Response should not be null");
        String responseText = TestUtils.extractTextContent(response);
        assertNotNull(responseText, "Response should have text content");
        assertTrue(responseText.length() > 5, "Response should have content");

        System.out.println("Response: " + responseText);
        System.out.println("✓ Audio Base64 analysis verified!");
    }

    // ========== Mixed Multimodal Test ==========

    @Test
    @DisplayName("Should handle mixed multimodal conversation (image + audio + text)")
    void testMixedMultimodalConversation() throws IOException {
        System.out.println("\n=== Test: Mixed Multimodal Conversation ===");

        Model imageModel = createImageModel(true);
        ReActAgent imageAgent = createBasicAgent("MixedAgent", imageModel);

        // Round 1: Image
        System.out.println("Round 1: Image analysis");
        Msg imageMsg =
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

        Msg response1 = imageAgent.call(imageMsg).block(TEST_TIMEOUT);
        assertNotNull(response1);
        String response1Text = TestUtils.extractTextContent(response1);
        System.out.println(
                "Image response: "
                        + response1Text.substring(0, Math.min(100, response1Text.length())));

        // Round 2: Text follow-up
        System.out.println("\nRound 2: Text follow-up");
        Msg textMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What was in the image I just showed you?")
                                                .build()))
                        .build();

        Msg response2 = imageAgent.call(textMsg).block(TEST_TIMEOUT);
        assertNotNull(response2);
        String response2Text = TestUtils.extractTextContent(response2);
        System.out.println(
                "Text response: "
                        + response2Text.substring(0, Math.min(100, response2Text.length())));

        // Round 3: Audio
        System.out.println("\nRound 3: Audio analysis");
        Model audioModel = createAudioModel(true);
        ReActAgent audioAgent = createBasicAgent("AudioAgent", audioModel);


        // Download the real audio file and convert to Base64
        System.out.println("Downloading audio file: " + TEST_AUDIO_URL);
        URL url = new URL(TEST_AUDIO_URL);
        var inputStream = url.openStream();
        byte[] audioBytes = inputStream.readAllBytes();
        inputStream.close();

        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
        System.out.println(
                "Downloaded "
                        + audioBytes.length
                        + " bytes, base64 length: "
                        + base64Audio.length());


        Msg audioMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(
                                List.of(
                                        TextBlock.builder()
                                                .text("What do you hear in this audio?")
                                                .build(),
                                        AudioBlock.builder()
                                                .source(
                                                        Base64Source.builder()
                                                                .data(base64Audio)
                                                                .mediaType("wav")
                                                                .build())
                                                .build()))
                        .build();

        Msg response3 = audioAgent.call(audioMsg).block(TEST_TIMEOUT);
        assertNotNull(response3);
        String response3Text = TestUtils.extractTextContent(response3);
        System.out.println(
                "Audio response: "
                        + response3Text.substring(0, Math.min(100, response3Text.length())));

        System.out.println("\n✓ Mixed multimodal conversation verified!");
    }

    // ========== Helper Methods ==========

    private Model createChatModel(boolean stream) {
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(CHAT_MODEL).stream(stream)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private Model createImageModel(boolean stream) {
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(IMAGE_MODEL).stream(stream)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private Model createAudioModel(boolean stream) {
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName(AUDIO_MODEL).stream(stream)
                        .formatter(new OpenAIChatFormatter())
                        .defaultOptions(GenerateOptions.builder().build());

        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }

        return builder.build();
    }

    private ReActAgent createBasicAgent(String name, Model model) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt("You are a helpful AI assistant.")
                .model(model)
                .toolkit(new Toolkit())
                .memory(new InMemoryMemory())
                .build();
    }

    private void verifyToolCall(Memory memory, String toolName) {
        boolean foundToolUse = false;
        boolean foundToolResult = false;

        for (Msg msg : memory.getMessages()) {
            if (msg.getRole() == MsgRole.ASSISTANT && msg.hasContentBlocks(ToolUseBlock.class)) {
                ToolUseBlock toolUse = msg.getFirstContentBlock(ToolUseBlock.class);
                if (toolName.equals(toolUse.getName())) {
                    foundToolUse = true;
                    System.out.println("Tool call found: " + toolName);
                }
            }

            if (msg.getRole() == MsgRole.TOOL && msg.hasContentBlocks(ToolResultBlock.class)) {
                ToolResultBlock toolResult = msg.getFirstContentBlock(ToolResultBlock.class);
                if (toolName.equals(toolResult.getName())) {
                    foundToolResult = true;
                    List<ContentBlock> outputs = toolResult.getOutput();
                    if (!outputs.isEmpty() && outputs.get(0) instanceof TextBlock tb) {
                        System.out.println("Tool result: " + tb.getText());
                    }
                }
            }
        }

        assertTrue(foundToolUse, "Memory should contain ToolUseBlock for " + toolName);
        assertTrue(foundToolResult, "Memory should contain ToolResultBlock for " + toolName);
    }
}
