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
package io.agentscope.core.formatter.gemini;

import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildConversationMessages;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildConversationMessages2;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildSystemMessage;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildToolMessages;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildToolMessages2;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.getGroundTruthMultiAgent2Json;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.getGroundTruthMultiAgentJson;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.parseGroundTruth;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiBlob;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiFunctionCall;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiFunctionResponse;
import io.agentscope.core.message.Msg;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for GeminiMultiAgentFormatter.
 * This test validates that the multi-agent formatter output matches the
 * expected Gemini API format
 * exactly as defined in the Python version.
 */
class GeminiMultiAgentFormatterGroundTruthTest extends GeminiFormatterTestBase {

    private static GeminiMultiAgentFormatter formatter;
    private static String imagePath;
    private static String audioPath;
    private static Path imageTempPath;
    private static Path audioTempPath;

    // Test messages
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;
    private static List<Msg> msgsConversation2;
    private static List<Msg> msgsTools2;

    // Ground truth
    private static List<Map<String, Object>> groundTruthMultiAgent;
    private static List<Map<String, Object>> groundTruthMultiAgent2;
    private static List<Map<String, Object>> groundTruthMultiAgentWithoutFirstConversation;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new GeminiMultiAgentFormatter();

        // Create temporary files matching Python test setup
        imageTempPath = Files.createTempFile("gemini_test_image", ".png");
        imagePath = imageTempPath.toAbsolutePath().toString();
        Files.write(imageTempPath, "fake image content".getBytes());

        audioTempPath = Files.createTempFile("gemini_test_audio", ".mp3");
        audioPath = audioTempPath.toAbsolutePath().toString();
        Files.write(audioTempPath, "fake audio content".getBytes());

        // Build test messages
        msgsSystem = buildSystemMessage();
        msgsConversation = buildConversationMessages(imagePath, audioPath);
        msgsTools = buildToolMessages(imagePath);
        msgsConversation2 = buildConversationMessages2();
        msgsTools2 = buildToolMessages2(imagePath);

        // Parse ground truth
        groundTruthMultiAgent = parseGroundTruth(getGroundTruthMultiAgentJson());
        groundTruthMultiAgent2 = parseGroundTruth(getGroundTruthMultiAgent2Json());

        // Build ground truth for "without first conversation" scenario
        // This corresponds to Python's
        // ground_truth_multiagent_without_first_conversation
        // Format: system + tools (without the conversation history wrapper)
        groundTruthMultiAgentWithoutFirstConversation = buildWithoutFirstConversationGroundTruth();
    }

    @AfterAll
    static void tearDown() {
        // Clean up temporary files
        try {
            if (imageTempPath != null) {
                Files.deleteIfExists(imageTempPath);
            }
            if (audioTempPath != null) {
                Files.deleteIfExists(audioTempPath);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    void testMultiAgentFormatter_TwoRoundsFullHistory() {
        // system + conversation + tools + conversation2 + tools2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);
        messages.addAll(msgsTools2);

        List<GeminiContent> result = formatter.format(messages);

        // System message is extracted to systemInstruction, so we skip the first message in ground
        // truth
        List<Map<String, Object>> expected =
                groundTruthMultiAgent2.subList(1, groundTruthMultiAgent2.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_TwoRoundsWithoutSecondTools() {
        // system + conversation + tools + conversation2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);

        List<GeminiContent> result = formatter.format(messages);

        // Ground truth without first message (system) and last tools2
        List<Map<String, Object>> expected =
                groundTruthMultiAgent2.subList(
                        1, groundTruthMultiAgent2.size() - msgsTools2.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_SingleRoundFullHistory() {
        // system + conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(messages);

        // System message is extracted to systemInstruction, so we skip the first message in ground
        // truth
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(messages);

        // Ground truth without first message (system)
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutFirstConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgentWithoutFirstConversation, result);
    }

    @Test
    void testMultiAgentFormatter_OnlySystemMessage() {
        List<GeminiContent> result = formatter.format(msgsSystem);

        // System message is now extracted to systemInstruction, not returned in contents
        // So we expect an empty list
        assertContentsMatchGroundTruth(List.of(), result);
    }

    @Test
    void testMultiAgentFormatter_OnlyConversation() {
        List<GeminiContent> result = formatter.format(msgsConversation);

        // Ground truth: second message (the merged conversation history)
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyTools() {
        List<GeminiContent> result = formatter.format(msgsTools);

        // Ground truth: all messages in groundTruthMultiAgentWithoutFirstConversation
        // This corresponds to tool call + tool response + assistant response (wrapped in history)
        List<Map<String, Object>> expected = groundTruthMultiAgentWithoutFirstConversation;

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_EmptyMessages() {
        List<GeminiContent> result = formatter.format(List.of());

        assertContentsMatchGroundTruth(List.of(), result);
    }

    /**
     * Build ground truth for "without first conversation" scenario.
     * This is equivalent to Python's
     * ground_truth_multiagent_without_first_conversation.
     *
     * @return Ground truth data
     */
    private static List<Map<String, Object>> buildWithoutFirstConversationGroundTruth() {
        // Parse the base ground truth
        // NOTE: System message is now extracted to systemInstruction field,
        // so it's not included in the contents array anymore
        String groundTruthJson =
                """
                [
                    {
                        "role": "model",
                        "parts": [
                            {
                                "function_call": {
                                    "id": "1",
                                    "name": "get_capital",
                                    "args": {
                                        "country": "Japan"
                                    }
                                }
                            }
                        ]
                    },
                    {
                        "role": "user",
                        "parts": [
                            {
                                "function_response": {
                                    "id": "1",
                                    "name": "get_capital",
                                    "response": {
                                        "output": "- The capital of Japan is Tokyo.\\n- The returned image can be found at: ./image.png\\n- The returned audio can be found at: /var/folders/gf/krg8x_ws409cpw_46b2s6rjc0000gn/T/tmpfymnv2w9.wav"
                                    }
                                }
                            }
                        ]
                    },
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "# Conversation History\\nThe content between <history></history> tags contains your conversation history\\n<history>assistant: The capital of Japan is Tokyo.\\n</history>"
                            }
                        ]
                    }
                ]
                """;

        return parseGroundTruth(groundTruthJson);
    }

    /**
     * Convert a list of Content objects to JSON and compare with ground truth.
     *
     * @param expectedGroundTruth Expected ground truth as list of maps
     * @param actualContents      Actual Content objects from formatter
     */
    private void assertContentsMatchGroundTruth(
            List<Map<String, Object>> expectedGroundTruth, List<GeminiContent> actualContents) {
        String expectedJson = toJson(expectedGroundTruth);
        String actualJson = toJson(contentsToMaps(actualContents));

        // Normalize temporary file paths before comparison
        String normalizedExpected = normalizeTempFilePaths(expectedJson);
        String normalizedActual = normalizeTempFilePaths(actualJson);

        assertJsonEquals(normalizedExpected, normalizedActual);
    }

    /**
     * Normalize temporary file paths in JSON for comparison.
     * Replaces actual temp file paths with a placeholder.
     *
     * @param json JSON string
     * @return Normalized JSON
     */
    private String normalizeTempFilePaths(String json) {
        // Replace any temp file path (e.g., /var/folders/.../tmpXXX.wav or
        // .../agentscope_XXX.wav)
        // with a placeholder to allow comparison
        return json.replaceAll(
                "(The returned (audio|image|video) can be found at: )[^\"]+", "$1<TEMP_FILE>");
    }

    /**
     * Convert List of GeminiContent objects to List of Maps for JSON comparison.
     *
     * @param contents GeminiContent objects
     * @return List of maps representing the contents
     */
    private List<Map<String, Object>> contentsToMaps(List<GeminiContent> contents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GeminiContent content : contents) {
            result.add(contentToMap(content));
        }
        return result;
    }

    /**
     * Convert a GeminiContent object to a Map for JSON comparison.
     *
     * @param content GeminiContent object
     * @return Map representation
     */
    private Map<String, Object> contentToMap(GeminiContent content) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();

        // Add role
        if (content.getRole() != null) {
            map.put("role", content.getRole());
        }

        // Add parts
        if (content.getParts() != null) {
            List<Map<String, Object>> partsList = new ArrayList<>();
            for (GeminiPart part : content.getParts()) {
                Map<String, Object> partMap = new java.util.LinkedHashMap<>();

                // Text part
                if (part.getText() != null) {
                    partMap.put("text", part.getText());
                }

                // Inline data (image/audio)
                if (part.getInlineData() != null) {
                    GeminiBlob inlineData = part.getInlineData();
                    Map<String, Object> inlineDataMap = new java.util.LinkedHashMap<>();

                    if (inlineData.getData() != null) {
                        inlineDataMap.put("data", inlineData.getData());
                    }
                    if (inlineData.getMimeType() != null) {
                        inlineDataMap.put("mime_type", inlineData.getMimeType());
                    }

                    partMap.put("inline_data", inlineDataMap);
                }

                // Function call
                if (part.getFunctionCall() != null) {
                    GeminiFunctionCall functionCall = part.getFunctionCall();
                    Map<String, Object> functionCallMap = new java.util.LinkedHashMap<>();

                    if (functionCall.getId() != null) {
                        functionCallMap.put("id", functionCall.getId());
                    }
                    if (functionCall.getName() != null) {
                        functionCallMap.put("name", functionCall.getName());
                    }
                    if (functionCall.getArgs() != null) {
                        functionCallMap.put("args", functionCall.getArgs());
                    }

                    partMap.put("function_call", functionCallMap);
                }

                // Function response
                if (part.getFunctionResponse() != null) {
                    GeminiFunctionResponse functionResponse = part.getFunctionResponse();
                    Map<String, Object> functionResponseMap = new java.util.LinkedHashMap<>();

                    if (functionResponse.getId() != null) {
                        functionResponseMap.put("id", functionResponse.getId());
                    }
                    if (functionResponse.getName() != null) {
                        functionResponseMap.put("name", functionResponse.getName());
                    }
                    if (functionResponse.getResponse() != null) {
                        functionResponseMap.put("response", functionResponse.getResponse());
                    }

                    partMap.put("function_response", functionResponseMap);
                }

                partsList.add(partMap);
            }
            map.put("parts", partsList);
        }

        return map;
    }
}
