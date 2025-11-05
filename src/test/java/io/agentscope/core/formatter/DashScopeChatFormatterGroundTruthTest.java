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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.dashscope.common.MultiModalMessage;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for DashScopeChatFormatter.
 * This test validates that the formatter output matches the expected DashScope API format
 * exactly as defined in the Python version.
 */
class DashScopeChatFormatterGroundTruthTest {

    private static DashScopeChatFormatter formatter;
    private static String imagePath;
    private static String mockAudioPath;

    // Test messages
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;

    // Ground truth
    private static List<MultiModalMessage> groundTruthChat;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new DashScopeChatFormatter();

        // Create a temporary image file (matching Python test setup)
        // Use unique filename to avoid conflicts with other test classes
        imagePath = "./image_chat_formatter.png";
        File imageFile = new File(imagePath);
        Files.write(imageFile.toPath(), "fake image content".getBytes());

        // Mock audio path (matching Python test)
        mockAudioPath = "/var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav";

        // Build test messages
        buildTestMessages();

        // Build ground truth
        buildGroundTruth();
    }

    @AfterAll
    static void tearDown() {
        // Clean up the temporary image file
        File imageFile = new File(imagePath);
        if (imageFile.exists()) {
            try {
                Files.delete(imageFile.toPath());
            } catch (IOException e) {
                // Ignore deletion errors
            }
        }
    }

    private static void buildTestMessages() {
        // System messages
        msgsSystem =
                List.of(
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("You're a helpful assistant.")
                                                        .build()))
                                .role(MsgRole.SYSTEM)
                                .build());

        // Conversation messages with multimodal content
        msgsConversation =
                List.of(
                        // User message with text and image
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of France?")
                                                        .build(),
                                                ImageBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(imagePath)
                                                                        .build())
                                                        .build()))
                                .role(MsgRole.USER)
                                .build(),
                        // Assistant response
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of France is Paris.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // User message with text and audio
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Germany?")
                                                        .build(),
                                                AudioBlock.builder()
                                                        .source(
                                                                URLSource.builder()
                                                                        .url(
                                                                                "https://example.com/audio1.mp3")
                                                                        .build())
                                                        .build()))
                                .role(MsgRole.USER)
                                .build(),
                        // Assistant response
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Germany is Berlin.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // User text-only message
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("What is the capital of Japan?")
                                                        .build()))
                                .role(MsgRole.USER)
                                .build());

        // Tool messages
        Map<String, Object> toolInput = new HashMap<>();
        toolInput.put("country", "Japan");

        msgsTools =
                List.of(
                        // Assistant with tool call
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(toolInput)
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // Tool result
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .output(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "The capital"
                                                                                            + " of Japan"
                                                                                            + " is Tokyo.")
                                                                                .build(),
                                                                        ImageBlock.builder()
                                                                                .source(
                                                                                        URLSource
                                                                                                .builder()
                                                                                                .url(
                                                                                                        imagePath)
                                                                                                .build())
                                                                                .build(),
                                                                        AudioBlock.builder()
                                                                                .source(
                                                                                        Base64Source
                                                                                                .builder()
                                                                                                .mediaType(
                                                                                                        "audio/wav")
                                                                                                .data(
                                                                                                        "ZmFrZSBhdWRpbyBjb250ZW50")
                                                                                                .build())
                                                                                .build()))
                                                        .build()))
                                .role(MsgRole.TOOL)
                                .build(),
                        // Assistant final response
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("The capital of Japan is Tokyo.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build());
    }

    private static void buildGroundTruth() {
        groundTruthChat = new ArrayList<>();
        File imageFile = new File(imagePath);
        String absoluteImagePath = "file://" + imageFile.getAbsolutePath();

        // Message 1: System message
        Map<String, Object> systemContent = new HashMap<>();
        systemContent.put("text", "You're a helpful assistant.");
        groundTruthChat.add(
                MultiModalMessage.builder().role("system").content(List.of(systemContent)).build());

        // Message 2: User with text and image
        Map<String, Object> userText1 = new HashMap<>();
        userText1.put("text", "What is the capital of France?");
        Map<String, Object> userImage1 = new HashMap<>();
        userImage1.put("image", absoluteImagePath);
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("user")
                        .content(List.of(userText1, userImage1))
                        .build());

        // Message 3: Assistant response
        Map<String, Object> assistantContent1 = new HashMap<>();
        assistantContent1.put("text", "The capital of France is Paris.");
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantContent1))
                        .build());

        // Message 4: User with text and audio
        Map<String, Object> userText2 = new HashMap<>();
        userText2.put("text", "What is the capital of Germany?");
        Map<String, Object> userAudio = new HashMap<>();
        userAudio.put("audio", "https://example.com/audio1.mp3");
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("user")
                        .content(List.of(userText2, userAudio))
                        .build());

        // Message 5: Assistant response
        Map<String, Object> assistantContent2 = new HashMap<>();
        assistantContent2.put("text", "The capital of Germany is Berlin.");
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantContent2))
                        .build());

        // Message 6: User text-only
        Map<String, Object> userText3 = new HashMap<>();
        userText3.put("text", "What is the capital of Japan?");
        groundTruthChat.add(
                MultiModalMessage.builder().role("user").content(List.of(userText3)).build());

        // Message 7: Assistant with tool call
        Map<String, Object> assistantEmptyContent = new HashMap<>();
        assistantEmptyContent.put("text", null);

        ToolCallFunction toolCall = new ToolCallFunction();
        toolCall.setId("1");
        toolCall.setType("function");
        ToolCallFunction.CallFunction function = toolCall.new CallFunction();
        function.setName("get_capital");
        function.setArguments("{\"country\": \"Japan\"}");
        toolCall.setFunction(function);

        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // Message 8: Tool result
        String toolResultContent =
                "- The capital of Japan is Tokyo.\n"
                        + "- The returned image can be found at: "
                        + imagePath
                        + "\n"
                        + "- The returned audio can be found at: "
                        + mockAudioPath;
        Map<String, Object> toolContent = new HashMap<>();
        toolContent.put("text", toolResultContent);
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(List.of(toolContent))
                        .build());

        // Message 9: Assistant final response
        Map<String, Object> assistantFinal = new HashMap<>();
        assistantFinal.put("text", "The capital of Japan is Tokyo.");
        groundTruthChat.add(
                MultiModalMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantFinal))
                        .build());
    }

    @Test
    void testChatFormatter_FullHistory() {
        // Combine all messages: system + conversation + tools
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);

        List<MultiModalMessage> result = formatter.formatMultiModal(allMessages);

        assertMultiModalMessagesEqual(groundTruthChat, result);
    }

    @Test
    void testChatFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<MultiModalMessage> result = formatter.formatMultiModal(messages);

        // Ground truth without first message (system)
        List<MultiModalMessage> expected = groundTruthChat.subList(1, groundTruthChat.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<MultiModalMessage> result = formatter.formatMultiModal(messages);

        // Ground truth: first message + last 3 messages (tools)
        List<MultiModalMessage> expected = new ArrayList<>();
        expected.add(groundTruthChat.get(0));
        expected.addAll(
                groundTruthChat.subList(
                        groundTruthChat.size() - msgsTools.size(), groundTruthChat.size()));

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_WithoutTools() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<MultiModalMessage> result = formatter.formatMultiModal(messages);

        // Ground truth without last 3 messages (tools)
        List<MultiModalMessage> expected =
                groundTruthChat.subList(0, groundTruthChat.size() - msgsTools.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testChatFormatter_EmptyMessages() {
        List<MultiModalMessage> result = formatter.formatMultiModal(List.of());

        assertEquals(0, result.size());
    }

    /**
     * Deep comparison of two lists of MultiModalMessage.
     * This ensures the formatter output exactly matches the ground truth.
     */
    private void assertMultiModalMessagesEqual(
            List<MultiModalMessage> expected, List<MultiModalMessage> actual) {
        assertEquals(
                expected.size(),
                actual.size(),
                "Number of messages should match. Expected: "
                        + expected.size()
                        + ", Actual: "
                        + actual.size());

        for (int i = 0; i < expected.size(); i++) {
            MultiModalMessage expectedMsg = expected.get(i);
            MultiModalMessage actualMsg = actual.get(i);

            // Compare role
            assertEquals(
                    expectedMsg.getRole(), actualMsg.getRole(), "Role should match at index " + i);

            // Compare content
            assertContentEqual(
                    expectedMsg.getContent(), actualMsg.getContent(), "at message index " + i);

            // Compare tool calls
            assertToolCallsEqual(
                    expectedMsg.getToolCalls(), actualMsg.getToolCalls(), "at message index " + i);

            // Compare tool call id
            assertEquals(
                    expectedMsg.getToolCallId(),
                    actualMsg.getToolCallId(),
                    "Tool call id should match at index " + i);

            // Compare name
            assertEquals(
                    expectedMsg.getName(), actualMsg.getName(), "Name should match at index " + i);
        }
    }

    private void assertContentEqual(
            List<Map<String, Object>> expected, List<Map<String, Object>> actual, String context) {
        if (expected == null && actual == null) {
            return;
        }
        assertNotNull(expected, "Expected content should not be null " + context);
        assertNotNull(actual, "Actual content should not be null " + context);
        assertEquals(
                expected.size(),
                actual.size(),
                "Content size should match "
                        + context
                        + ". Expected: "
                        + expected.size()
                        + ", Actual: "
                        + actual.size());

        for (int i = 0; i < expected.size(); i++) {
            Map<String, Object> expectedContent = expected.get(i);
            Map<String, Object> actualContent = actual.get(i);

            // Compare keys
            assertEquals(
                    expectedContent.keySet(),
                    actualContent.keySet(),
                    "Content keys should match " + context + " at content index " + i);

            // Compare values for each key
            for (String key : expectedContent.keySet()) {
                Object expectedValue = expectedContent.get(key);
                Object actualValue = actualContent.get(key);

                // Special handling for image URLs (file:// protocol)
                if (key.equals("image")
                        && expectedValue instanceof String
                        && actualValue instanceof String) {
                    String expectedUrl = (String) expectedValue;
                    String actualUrl = (String) actualValue;
                    if (expectedUrl.startsWith("file://") && actualUrl.startsWith("file://")) {
                        // Both use file protocol, consider them equal if they point to the same
                        // file
                        assertTrue(
                                actualUrl.contains(imagePath.replace("./", "")),
                                "Image URL should point to "
                                        + imagePath
                                        + " but was "
                                        + actualUrl
                                        + " "
                                        + context);
                    } else {
                        assertEquals(
                                expectedValue,
                                actualValue,
                                "Content value for key '"
                                        + key
                                        + "' should match "
                                        + context
                                        + " at content index "
                                        + i);
                    }
                } else if (key.equals("text")
                        && expectedValue instanceof String
                        && actualValue instanceof String) {
                    // Special handling for tool result text containing temporary file paths
                    String expectedText = (String) expectedValue;
                    String actualText = (String) actualValue;

                    // If text contains temp file paths (audio/image from base64), normalize
                    // them
                    if (expectedText.contains("The returned")
                            && expectedText.contains("can be found at:")) {
                        // Normalize both texts by replacing temp file paths
                        String normalizedExpected = normalizeTempFilePaths(expectedText);
                        String normalizedActual = normalizeTempFilePaths(actualText);
                        assertEquals(
                                normalizedExpected,
                                normalizedActual,
                                "Content value for key '"
                                        + key
                                        + "' should match "
                                        + context
                                        + " at content index "
                                        + i);
                    } else {
                        assertEquals(
                                expectedValue,
                                actualValue,
                                "Content value for key '"
                                        + key
                                        + "' should match "
                                        + context
                                        + " at content index "
                                        + i);
                    }
                } else {
                    assertEquals(
                            expectedValue,
                            actualValue,
                            "Content value for key '"
                                    + key
                                    + "' should match "
                                    + context
                                    + " at content index "
                                    + i);
                }
            }
        }
    }

    private void assertToolCallsEqual(
            List<ToolCallBase> expected, List<ToolCallBase> actual, String context) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null || actual == null) {
            assertEquals(
                    expected, actual, "Tool calls should both be null or both non-null " + context);
            return;
        }

        assertEquals(expected.size(), actual.size(), "Tool calls size should match " + context);

        for (int i = 0; i < expected.size(); i++) {
            ToolCallBase expectedCall = expected.get(i);
            ToolCallBase actualCall = actual.get(i);

            assertTrue(
                    expectedCall instanceof ToolCallFunction,
                    "Expected tool call should be ToolCallFunction");
            assertTrue(
                    actualCall instanceof ToolCallFunction,
                    "Actual tool call should be ToolCallFunction");

            ToolCallFunction expectedFunc = (ToolCallFunction) expectedCall;
            ToolCallFunction actualFunc = (ToolCallFunction) actualCall;

            assertEquals(
                    expectedFunc.getId(),
                    actualFunc.getId(),
                    "Tool call id should match " + context);
            assertEquals(
                    expectedFunc.getType(),
                    actualFunc.getType(),
                    "Tool call type should match " + context);

            if (expectedFunc.getFunction() != null && actualFunc.getFunction() != null) {
                assertEquals(
                        expectedFunc.getFunction().getName(),
                        actualFunc.getFunction().getName(),
                        "Function name should match " + context);
                // Note: Arguments comparison might need normalization (whitespace, order)
                assertJsonEqual(
                        expectedFunc.getFunction().getArguments(),
                        actualFunc.getFunction().getArguments(),
                        "Function arguments should match " + context);
            }
        }
    }

    private void assertJsonEqual(String expected, String actual, String context) {
        // Simple JSON comparison - could be enhanced with proper JSON parsing
        // For now, just check they're not null and contain the same key-value pairs
        if (expected == null && actual == null) {
            return;
        }
        assertNotNull(expected, "Expected JSON should not be null " + context);
        assertNotNull(actual, "Actual JSON should not be null " + context);

        // Remove whitespace for comparison
        String normalizedExpected = expected.replaceAll("\\s+", "");
        String normalizedActual = actual.replaceAll("\\s+", "");
        assertEquals(normalizedExpected, normalizedActual, context);
    }

    /**
     * Normalize temporary file paths in tool result text.
     * This replaces actual temp file paths with a placeholder to allow comparison.
     *
     * @param text The text containing temp file paths
     * @return Normalized text with temp paths replaced
     */
    private String normalizeTempFilePaths(String text) {
        // Pattern for lines like "- The returned audio can be found at:
        // /var/folders/.../tmpXXX.wav"
        // Replace the actual temp path with a placeholder
        return text.replaceAll(
                        "(The returned (audio|image|video) can be found at: )/[^\\n]+",
                        "$1<TEMP_FILE>")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
