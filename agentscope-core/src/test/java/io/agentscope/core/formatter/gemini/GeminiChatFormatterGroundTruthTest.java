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

import com.google.genai.types.Content;
import io.agentscope.core.message.Msg;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ground truth tests for GeminiChatFormatter.
 * This test validates that the formatter output matches the expected Gemini API format
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

        List<Content> result = formatter.format(allMessages);

        assertContentsMatchGroundTruth(groundTruthChat, result);
    }

    @Test
    void testChatFormatter_WithoutSystemMessage() {
        // conversation + tools
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsConversation);
        messages.addAll(msgsTools);

        List<Content> result = formatter.format(messages);

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

        List<Content> result = formatter.format(messages);

        // Ground truth: first message + last 3 messages (tools)
        List<Map<String, Object>> expected = new ArrayList<>();
        expected.add(groundTruthChat.get(0));
        expected.addAll(
                groundTruthChat.subList(
                        groundTruthChat.size() - msgsTools.size(), groundTruthChat.size()));

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_WithoutTools() {
        // system + conversation
        List<Msg> messages = new ArrayList<>();
        messages.addAll(msgsSystem);
        messages.addAll(msgsConversation);

        List<Content> result = formatter.format(messages);

        // Ground truth without last 3 messages (tools)
        List<Map<String, Object>> expected =
                groundTruthChat.subList(0, groundTruthChat.size() - msgsTools.size());

        assertContentsMatchGroundTruth(expected, result);
    }

    @Test
    void testChatFormatter_EmptyMessages() {
        List<Content> result = formatter.format(List.of());

        assertContentsMatchGroundTruth(List.of(), result);
    }

    /**
     * Convert a list of Content objects to JSON and compare with ground truth.
     *
     * @param expectedGroundTruth Expected ground truth as list of maps
     * @param actualContents Actual Content objects from formatter
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
