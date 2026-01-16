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
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildSystemMessage;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.buildToolMessages;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.getGroundTruthChatJson;
import static io.agentscope.core.formatter.gemini.GeminiFormatterTestData.parseGroundTruth;

import io.agentscope.core.formatter.gemini.dto.GeminiContent;
import io.agentscope.core.formatter.gemini.dto.GeminiPart;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiBlob;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiFunctionCall;
import io.agentscope.core.formatter.gemini.dto.GeminiPart.GeminiFunctionResponse;
import io.agentscope.core.message.Msg;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for GeminiChatFormatter.
 * This test validates that the formatter output matches the expected Gemini API
 * format
 * exactly as defined in the Python version.
 */
class GeminiChatFormatterGroundTruthTest extends GeminiFormatterTestBase {

    private static GeminiChatFormatter formatter;
    private static String imagePath;
    private static String audioPath;

    // Test messages
    private static List<Msg> msgsSystem;
    private static List<Msg> msgsConversation;
    private static List<Msg> msgsTools;

    // Ground truth
    private static List<Map<String, Object>> groundTruthChat;

    @BeforeAll
    static void setUp() throws IOException {
        formatter = new GeminiChatFormatter();

        // Create temporary files matching Python test setup
        imagePath = "./image.png";
        File imageFile = new File(imagePath);
        Files.write(imageFile.toPath(), "fake image content".getBytes());

        audioPath = "./audio.mp3";
        File audioFile = new File(audioPath);
        Files.write(audioFile.toPath(), "fake audio content".getBytes());

        // Build test messages
        msgsSystem = buildSystemMessage();
        msgsConversation = buildConversationMessages(imagePath, audioPath);
        msgsTools = buildToolMessages(imagePath);

        // Parse ground truth
        groundTruthChat = parseGroundTruth(getGroundTruthChatJson());
    }

    @AfterAll
    static void tearDown() {
        // Clean up temporary files
        new File(imagePath).deleteOnExit();
        new File(audioPath).deleteOnExit();
    }

    @Test
    void testChatFormatter_FullHistory() {
        // Combine all messages: system + conversation + tools
        List<Msg> allMessages = new ArrayList<>();
        allMessages.addAll(msgsSystem);
        allMessages.addAll(msgsConversation);
        allMessages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(allMessages);

        // System message is extracted to systemInstruction, so we skip the first message in ground
        // truth
        List<Map<String, Object>> expected = groundTruthChat.subList(1, groundTruthChat.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(messages);

        // Ground truth without first message (system)
        List<Map<String, Object>> expected = groundTruthChat.subList(1, groundTruthChat.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutConversation() {
        // system + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsTools);

        List<GeminiContent> result = formatter.format(messages);

        // Ground truth: last 3 messages (tools) only, as system message is extracted
        List<Map<String, Object>> expected =
                groundTruthChat.subList(
                        groundTruthChat.size() - msgsTools.size(), groundTruthChat.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutTools() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<GeminiContent> result = formatter.format(messages);

        // Ground truth without last 3 messages (tools) and without first (system)
        // System message is extracted, so we skip index 0
        List<Map<String, Object>> expected =
                groundTruthChat.subList(1, groundTruthChat.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_EmptyMessages() {
        List<GeminiContent> result = formatter.format(List.of());

        assertContentsMatchGroundTruth(List.of(), result);
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
        Map<String, Object> map = new LinkedHashMap<>();

        // Add role
        if (content.getRole() != null) {
            map.put("role", content.getRole());
        }

        // Add parts
        if (content.getParts() != null) {
            List<Map<String, Object>> partsList = new ArrayList<>();
            for (GeminiPart part : content.getParts()) {
                Map<String, Object> partMap = new LinkedHashMap<>();

                // Text part
                if (part.getText() != null) {
                    partMap.put("text", part.getText());
                }

                // Inline data (image/audio)
                if (part.getInlineData() != null) {
                    GeminiBlob inlineData = part.getInlineData();
                    Map<String, Object> inlineDataMap = new LinkedHashMap<>();

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
                    Map<String, Object> functionCallMap = new LinkedHashMap<>();

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
                    Map<String, Object> functionResponseMap = new LinkedHashMap<>();

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
