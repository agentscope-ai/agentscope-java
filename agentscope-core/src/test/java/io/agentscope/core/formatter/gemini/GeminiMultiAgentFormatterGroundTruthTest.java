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

import com.google.genai.types.Content;
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

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgent2, result);
    }

    @Test
    void testMultiAgentFormatter_TwoRoundsWithoutSecondTools() {
        // system + conversation + tools + conversation2
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);
        messages.addAll(msgsConversation2);

        List<Content> result = formatter.format(messages);

        // Ground truth without last tools2
        List<Map<String, Object>> expected =
                groundTruthMultiAgent2.subList(
                        0, groundTruthMultiAgent2.size() - msgsTools2.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_SingleRoundFullHistory() {
        // system + conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgent, result);
    }

    @Test
    void testMultiAgentFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

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

        List<Content> result = formatter.format(messages);

        assertContentsMatchGroundTruth(groundTruthMultiAgentWithoutFirstConversation, result);
    }

    @Test
    void testMultiAgentFormatter_OnlySystemMessage() {
        List<Content> result = formatter.format(msgsSystem);

        // Ground truth: only first message
        List<Map<String, Object>> expected = groundTruthMultiAgent.subList(0, 1);

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyConversation() {
        List<Content> result = formatter.format(msgsConversation);

        // Ground truth: second message (the merged conversation history)
        List<Map<String, Object>> expected =
                groundTruthMultiAgent.subList(1, groundTruthMultiAgent.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_OnlyTools() {
        List<Content> result = formatter.format(msgsTools);

        // Ground truth: last 3 messages (tools)
        // This corresponds to ground_truth_multiagent_without_first_conversation[1:]
        List<Map<String, Object>> expected =
                groundTruthMultiAgentWithoutFirstConversation.subList(
                        1, groundTruthMultiAgentWithoutFirstConversation.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testMultiAgentFormatter_EmptyMessages() {
        List<Content> result = formatter.format(List.of());

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
        String groundTruthJson =
                """
                [
                    {
                        "role": "user",
                        "parts": [
                            {
                                "text": "You're a helpful assistant."
                            }
                        ]
                    },
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
            List<Map<String, Object>> expectedGroundTruth, List<Content> actualContents) {
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
     * Convert List of Content objects to List of Maps for JSON comparison.
     *
     * @param contents Content objects
     * @return List of maps representing the contents
     */
    private List<Map<String, Object>> contentsToMaps(List<Content> contents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Content content : contents) {
            result.add(contentToMap(content));
        }
        return result;
    }

    /**
     * Convert a Content object to a Map for JSON comparison.
     *
     * @param content Content object
     * @return Map representation
     */
    private Map<String, Object> contentToMap(Content content) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();

        // Add role
        if (content.role().isPresent()) {
            map.put("role", content.role().get());
        }

        // Add parts
        if (content.parts().isPresent()) {
            List<Map<String, Object>> partsList = new ArrayList<>();
            for (var part : content.parts().get()) {
                Map<String, Object> partMap = new java.util.LinkedHashMap<>();

                // Text part
                if (part.text().isPresent()) {
                    partMap.put("text", part.text().get());
                }

                // Inline data (image/audio)
                if (part.inlineData().isPresent()) {
                    var inlineData = part.inlineData().get();
                    Map<String, Object> inlineDataMap = new java.util.LinkedHashMap<>();

                    if (inlineData.data().isPresent()) {
                        inlineDataMap.put("data", inlineData.data().get());
                    }
                    if (inlineData.mimeType().isPresent()) {
                        inlineDataMap.put("mime_type", inlineData.mimeType().get());
                    }

                    partMap.put("inline_data", inlineDataMap);
                }

                // Function call
                if (part.functionCall().isPresent()) {
                    var functionCall = part.functionCall().get();
                    Map<String, Object> functionCallMap = new java.util.LinkedHashMap<>();

                    if (functionCall.id().isPresent()) {
                        functionCallMap.put("id", functionCall.id().get());
                    }
                    if (functionCall.name().isPresent()) {
                        functionCallMap.put("name", functionCall.name().get());
                    }
                    if (functionCall.args().isPresent()) {
                        functionCallMap.put("args", functionCall.args().get());
                    }

                    partMap.put("function_call", functionCallMap);
                }

                // Function response
                if (part.functionResponse().isPresent()) {
                    var functionResponse = part.functionResponse().get();
                    Map<String, Object> functionResponseMap = new java.util.LinkedHashMap<>();

                    if (functionResponse.id().isPresent()) {
                        functionResponseMap.put("id", functionResponse.id().get());
                    }
                    if (functionResponse.name().isPresent()) {
                        functionResponseMap.put("name", functionResponse.name().get());
                    }
                    if (functionResponse.response().isPresent()) {
                        functionResponseMap.put("response", functionResponse.response().get());
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
