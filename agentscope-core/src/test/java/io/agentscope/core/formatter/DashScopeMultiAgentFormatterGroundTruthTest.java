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
package io.agentscope.core.formatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeContentPart;
import io.agentscope.core.formatter.dashscope.dto.DashScopeFunction;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.formatter.dashscope.dto.DashScopeToolCall;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for DashScopeMultiAgentFormatter.
 * This test validates that the formatter output matches the expected DashScope API format
 * exactly as defined in the Python version.
 */
class DashScopeMultiAgentFormatterGroundTruthTest {

    private static DashScopeMultiAgentFormatter formatter;
    private static String imagePath;
    private static String mockAudioPath;

    // Test messages
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;
    private static List<Msg> msgsConversation2;
    private static List<Msg> msgsTools2;

    // Ground truth
    private static List<DashScopeMessage> groundTruthMultiagent;
    private static List<DashScopeMessage> groundTruthMultiagentWithoutFirstConversation;
    private static List<DashScopeMessage> groundTruthMultiagent2;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new DashScopeMultiAgentFormatter();

        // Create a temporary image file (matching Python test setup)
        // Use unique filename to avoid conflicts with other test classes
        imagePath = "./image_multiagent_formatter.png";
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
        File imageFile = new File(imagePath);
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

        // Second conversation
        msgsConversation2 =
                List.of(
                        Msg.builder()
                                .name("user")
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(
                                                                "What is the capital of South"
                                                                        + " Korea?")
                                                        .build()))
                                .role(MsgRole.USER)
                                .build());

        // Second tool messages
        Map<String, Object> toolInput2 = new HashMap<>();
        toolInput2.put("country", "South Korea");

        msgsTools2 =
                List.of(
                        // Assistant with tool call
                        Msg.builder()
                                .name("assistant")
                                .content(
                                        List.of(
                                                ToolUseBlock.builder()
                                                        .id("1")
                                                        .name("get_capital")
                                                        .input(toolInput2)
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build(),
                        // Tool result (note: different tool_call_id "2" in Python test)
                        Msg.builder()
                                .name("system")
                                .content(
                                        List.of(
                                                ToolResultBlock.builder()
                                                        .id("2")
                                                        .name("get_capital")
                                                        .output(
                                                                List.of(
                                                                        TextBlock.builder()
                                                                                .text(
                                                                                        "The capital"
                                                                                            + " of South"
                                                                                            + " Korea"
                                                                                            + " is Seoul.")
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
                                                        .text(
                                                                "The capital of South Korea is"
                                                                        + " Seoul.")
                                                        .build()))
                                .role(MsgRole.ASSISTANT)
                                .build());
    }

    private static void buildGroundTruth() {
        File imageFile = new File(imagePath);
        String absoluteImagePath = "file://" + imageFile.getAbsolutePath();

        // Build groundTruthMultiagent: system + conversation + tools
        groundTruthMultiagent = new ArrayList<>();

        // Message 1: System message
        DashScopeContentPart systemContent =
                DashScopeContentPart.text("You're a helpful assistant.");
        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // Message 2: User with conversation history
        String historyText =
                "# Conversation History\n"
                        + "The content between <history></history> tags contains your conversation"
                        + " history\n"
                        + "<history>\n"
                        + "user: What is the capital of France?";

        List<DashScopeContentPart> userContent = new ArrayList<>();
        userContent.add(DashScopeContentPart.text(historyText));
        userContent.add(DashScopeContentPart.image(absoluteImagePath));
        userContent.add(
                DashScopeContentPart.text(
                        "assistant: The capital of France is Paris.\n"
                                + "user: What is the capital of Germany?"));
        userContent.add(DashScopeContentPart.audio("https://example.com/audio1.mp3"));
        userContent.add(
                DashScopeContentPart.text(
                        "assistant: The capital of Germany is Berlin.\nuser: What is the capital of"
                                + " Japan?\n</history>"));

        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("user").content(userContent).build());

        // Message 3: Assistant with tool call
        DashScopeContentPart assistantEmptyContent = DashScopeContentPart.text(null);

        DashScopeFunction function = new DashScopeFunction();
        function.setName("get_capital");
        function.setArguments("{\"country\": \"Japan\"}");
        DashScopeToolCall toolCall = new DashScopeToolCall();
        toolCall.setId("1");
        toolCall.setType("function");
        toolCall.setFunction(function);

        groundTruthMultiagent.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // Message 4: Tool result
        String toolResultContent =
                "- The capital of Japan is Tokyo.\n"
                        + "- The returned image can be found at: "
                        + imagePath
                        + "\n"
                        + "- The returned audio can be found at: "
                        + mockAudioPath;
        DashScopeContentPart toolContent = DashScopeContentPart.text(toolResultContent);
        groundTruthMultiagent.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(List.of(toolContent))
                        .build());

        // Message 5: User with assistant response in history
        DashScopeContentPart finalUserContent =
                DashScopeContentPart.text(
                        "<history>\nassistant: The capital of Japan is Tokyo.\n</history>");
        groundTruthMultiagent.add(
                DashScopeMessage.builder().role("user").content(List.of(finalUserContent)).build());

        // Build groundTruthMultiagentWithoutFirstConversation: system + tools
        groundTruthMultiagentWithoutFirstConversation = new ArrayList<>();

        // Message 1: System
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // Message 2: Assistant with tool call (same as above)
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // Message 3: Tool result (same as above)
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(List.of(toolContent))
                        .build());

        // Message 4: User with history
        DashScopeContentPart historyContent =
                DashScopeContentPart.text(
                        "# Conversation History\n"
                                + "The content between <history></history> tags contains your"
                                + " conversation history\n"
                                + "<history>\n"
                                + "assistant: The capital of Japan is Tokyo.\n"
                                + "</history>");
        groundTruthMultiagentWithoutFirstConversation.add(
                DashScopeMessage.builder().role("user").content(List.of(historyContent)).build());

        // Build groundTruthMultiagent2: system + conversation + tools + conversation2 + tools2
        groundTruthMultiagent2 = new ArrayList<>();

        // Message 1: System
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("system").content(List.of(systemContent)).build());

        // Message 2: User with first conversation history (same as groundTruthMultiagent)
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("user").content(userContent).build());

        // Message 3: Assistant with tool call (same as above)
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall))
                        .build());

        // Message 4: Tool result (same as above)
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("1")
                        .name("get_capital")
                        .content(List.of(toolContent))
                        .build());

        // Message 5: User with updated history including second conversation
        DashScopeContentPart updatedHistoryContent =
                DashScopeContentPart.text(
                        "<history>\n"
                                + "assistant: The capital of Japan is Tokyo.\n"
                                + "user: What is the capital of South Korea?\n"
                                + "</history>");
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("user")
                        .content(List.of(updatedHistoryContent))
                        .build());

        // Message 6: Assistant with second tool call
        DashScopeFunction function2 = new DashScopeFunction();
        function2.setName("get_capital");
        function2.setArguments("{\"country\": \"South Korea\"}");
        DashScopeToolCall toolCall2 = new DashScopeToolCall();
        toolCall2.setId("1");
        toolCall2.setType("function");
        toolCall2.setFunction(function2);

        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("assistant")
                        .content(List.of(assistantEmptyContent))
                        .toolCalls(List.of(toolCall2))
                        .build());

        // Message 7: Second tool result (note: tool_call_id is "2")
        String toolResultContent2 =
                "- The capital of South Korea is Seoul.\n"
                        + "- The returned image can be found at: "
                        + imagePath
                        + "\n"
                        + "- The returned audio can be found at: "
                        + mockAudioPath;
        DashScopeContentPart toolContent2 = DashScopeContentPart.text(toolResultContent2);
        groundTruthMultiagent2.add(
                DashScopeMessage.builder()
                        .role("tool")
                        .toolCallId("2")
                        .name("get_capital")
                        .content(List.of(toolContent2))
                        .build());

        // Message 8: Final user with last response in history
        DashScopeContentPart finalHistory =
                DashScopeContentPart.text(
                        "<history>\nassistant: The capital of South Korea is Seoul.\n</history>");
        groundTruthMultiagent2.add(
                DashScopeMessage.builder().role("user").content(List.of(finalHistory)).build());
    }

    @Test
    void testMultiAgentFormatter_FullHistory() {
        // system + conversation + tools + conversation2 + tools2
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);
        allMessages.addAll(msgsConversation2);
        allMessages.addAll(msgsTools2);

        List<DashScopeMessage> result = formatter.formatMultiModal(allMessages);

        assertMultiModalMessagesEqual(groundTruthMultiagent2, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSecondRound() {
        // system + conversation + tools + conversation2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // Expected: groundTruthMultiagent2 without last 3 messages (tools2)
        List<DashScopeMessage> expected =
                groundTruthMultiagent2.subList(
                        0, groundTruthMultiagent2.size() - msgsTools2.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_SystemConversationTools() {
        // system + conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        assertMultiModalMessagesEqual(groundTruthMultiagent, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // Expected: groundTruthMultiagent without first message (system)
        List<DashScopeMessage> expected =
                groundTruthMultiagent.subList(1, groundTruthMultiagent.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        assertMultiModalMessagesEqual(groundTruthMultiagentWithoutFirstConversation, result);
    }

    @Test
    void testMultiAgentFormatter_SystemConversationOnly() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<DashScopeMessage> result = formatter.formatMultiModal(messages);

        // Expected: first 2 messages of groundTruthMultiagent
        List<DashScopeMessage> expected = groundTruthMultiagent.subList(0, 2);

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlySystemMessage() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsSystem);

        // Expected: first message of groundTruthMultiagent
        List<DashScopeMessage> expected = groundTruthMultiagent.subList(0, 1);

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyConversation() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsConversation);

        // Expected: second message of groundTruthMultiagent (conversation history without tools)
        List<DashScopeMessage> expected =
                groundTruthMultiagent.subList(1, 2); // Just the user message with history

        assertMultiModalMessagesEqual(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyTools() {
        List<DashScopeMessage> result = formatter.formatMultiModal(msgsTools);

        // Expected: groundTruthMultiagentWithoutFirstConversation without first message (system)
        List<DashScopeMessage> expected =
                groundTruthMultiagentWithoutFirstConversation.subList(
                        1, groundTruthMultiagentWithoutFirstConversation.size());

        assertMultiModalMessagesEqual(expected, result);
    }

    /**
     * Deep comparison of two lists of DashScopeMessage.
     * This ensures the formatter output exactly matches the ground truth.
     */
    private void assertMultiModalMessagesEqual(
            List<DashScopeMessage> expected, List<DashScopeMessage> actual) {
        assertEquals(
                expected.size(),
                actual.size(),
                "Number of messages should match. Expected: "
                        + expected.size()
                        + ", Actual: "
                        + actual.size());

        for (int i = 0; i < expected.size(); i++) {
            DashScopeMessage expectedMsg = expected.get(i);
            DashScopeMessage actualMsg = actual.get(i);

            // Compare role
            assertEquals(
                    expectedMsg.getRole(), actualMsg.getRole(), "Role should match at index " + i);

            // Compare content
            assertContentEqual(
                    expectedMsg.getContentAsList(),
                    actualMsg.getContentAsList(),
                    "at message index " + i);

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
            List<DashScopeContentPart> expected,
            List<DashScopeContentPart> actual,
            String context) {
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
            DashScopeContentPart expectedPart = expected.get(i);
            DashScopeContentPart actualPart = actual.get(i);

            // Compare text (treat null and empty string as equivalent)
            if (expectedPart.getText() != null || actualPart.getText() != null) {
                String expectedText = expectedPart.getText();
                String actualText = actualPart.getText();
                // Normalize null and empty string
                String normalizedExpected =
                        (expectedText == null || expectedText.isEmpty()) ? "" : expectedText;
                String normalizedActual =
                        (actualText == null || actualText.isEmpty()) ? "" : actualText;
                if (!normalizedExpected.isEmpty()
                        && !normalizedActual.isEmpty()
                        && normalizedExpected.contains("The returned")
                        && normalizedExpected.contains("can be found at:")) {
                    normalizedExpected = normalizeTempFilePaths(normalizedExpected);
                    normalizedActual = normalizeTempFilePaths(normalizedActual);
                }
                assertEquals(
                        normalizedExpected,
                        normalizedActual,
                        "Text should match " + context + " at content index " + i);
            }

            // Compare image
            if (expectedPart.getImage() != null || actualPart.getImage() != null) {
                String expectedImage = expectedPart.getImage();
                String actualImage = actualPart.getImage();
                if (expectedImage != null
                        && actualImage != null
                        && expectedImage.startsWith("file://")
                        && actualImage.startsWith("file://")) {
                    assertTrue(
                            actualImage.contains(imagePath.replace("./", "")),
                            "Image URL should point to "
                                    + imagePath
                                    + " but was "
                                    + actualImage
                                    + " "
                                    + context);
                } else {
                    assertEquals(
                            expectedImage,
                            actualImage,
                            "Image should match " + context + " at content index " + i);
                }
            }

            // Compare audio
            assertEquals(
                    expectedPart.getAudio(),
                    actualPart.getAudio(),
                    "Audio should match " + context + " at content index " + i);

            // Compare video
            assertEquals(
                    expectedPart.getVideo(),
                    actualPart.getVideo(),
                    "Video should match " + context + " at content index " + i);
        }
    }

    private void assertToolCallsEqual(
            List<DashScopeToolCall> expected, List<DashScopeToolCall> actual, String context) {
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
            DashScopeToolCall expectedCall = expected.get(i);
            DashScopeToolCall actualCall = actual.get(i);

            assertEquals(
                    expectedCall.getId(),
                    actualCall.getId(),
                    "Tool call id should match " + context);
            assertEquals(
                    expectedCall.getType(),
                    actualCall.getType(),
                    "Tool call type should match " + context);

            if (expectedCall.getFunction() != null && actualCall.getFunction() != null) {
                assertEquals(
                        expectedCall.getFunction().getName(),
                        actualCall.getFunction().getName(),
                        "Function name should match " + context);
                // Note: Arguments comparison might need normalization (whitespace, order)
                assertJsonEqual(
                        expectedCall.getFunction().getArguments(),
                        actualCall.getFunction().getArguments(),
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
        Pattern pattern =
                Pattern.compile(
                        "(The returned (audio|image|video) can be found at: )[^\\n]+",
                        Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        String result = matcher.replaceAll("$1<TEMP_FILE>");
        return result.replaceAll("\\s+", " ").trim();
    }
}
