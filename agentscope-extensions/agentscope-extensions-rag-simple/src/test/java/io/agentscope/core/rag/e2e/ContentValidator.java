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
package io.agentscope.core.rag.e2e;

import io.agentscope.core.message.Msg;
import java.util.List;

/**
 * Utility class for validating E2E test responses with intelligent content checking.
 *
 * <p>Provides methods to validate responses that may have non-deterministic LLM output,
 * using keyword matching, semantic validation, and structure verification.
 */
public class ContentValidator {

    private ContentValidator() {
        // Utility class
    }

    /**
     * Validates that a response contains meaningful content.
     *
     * @param response The response message to validate
     * @return true if response has meaningful content (> 5 characters)
     */
    public static boolean hasMeaningfulContent(Msg response) {
        String text = TestUtils.extractTextContent(response);
        return text != null && text.trim().length() > 5;
    }

    /**
     * Validates that any response in the list contains meaningful content.
     *
     * @param responses List of response messages
     * @return true if at least one response has meaningful content
     */
    public static boolean hasMeaningfulContent(List<Msg> responses) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream().anyMatch(ContentValidator::hasMeaningfulContent);
    }

    /**
     * Checks if response contains expected keywords (case-insensitive).
     *
     * @param response The response message
     * @param expectedKeywords Keywords to look for
     * @return true if any keyword is found
     */
    public static boolean containsKeywords(Msg response, String... expectedKeywords) {
        String text = TestUtils.extractTextContent(response);
        if (text == null) return false;

        String lowerText = text.toLowerCase();
        for (String keyword : expectedKeywords) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if any response in the list contains expected keywords.
     *
     * @param responses List of response messages
     * @param expectedKeywords Keywords to look for
     * @return true if any response contains any keyword
     */
    public static boolean containsKeywords(List<Msg> responses, String... expectedKeywords) {
        if (responses == null || responses.isEmpty()) {
            return false;
        }

        return responses.stream()
                .anyMatch(response -> containsKeywords(response, expectedKeywords));
    }

    /**
     * Validates mathematical calculation result.
     *
     * @param response The response containing calculation result
     * @param expectedResult The expected numerical result
     * @return true if response contains the expected result
     */
    public static boolean containsCalculationResult(Msg response, String expectedResult) {
        return containsKeywords(response, expectedResult);
    }

    /**
     * Validates mathematical calculation result in any response.
     *
     * @param responses List of responses containing calculation results
     * @param expectedResult The expected numerical result
     * @return true if any response contains the expected result
     */
    public static boolean containsCalculationResult(List<Msg> responses, String expectedResult) {
        return containsKeywords(responses, expectedResult);
    }

    /**
     * Validates image analysis response mentions expected visual elements.
     *
     * @param response The image analysis response
     * @param expectedElements Expected visual elements (e.g., "dog", "cat", "car")
     * @return true if response mentions any expected elements
     */
    public static boolean mentionsVisualElements(Msg response, String... expectedElements) {
        return containsKeywords(response, expectedElements);
    }

    /**
     * Validates audio analysis response mentions expected audio elements.
     *
     * @param response The audio analysis response
     * @param expectedElements Expected audio elements (e.g., "music", "speech", "sound")
     * @return true if response mentions any expected elements
     */
    public static boolean mentionsAudioElements(Msg response, String... expectedElements) {
        return containsKeywords(response, expectedElements);
    }

    /**
     * Validates error handling response contains error indicators.
     *
     * @param response The error handling response
     * @return true if response indicates proper error handling
     */
    public static boolean indicatesErrorHandling(Msg response) {
        return containsKeywords(response, "error", "invalid", "cannot", "unable", "sorry");
    }

    /**
     * Validates conversation context preservation.
     *
     * @param currentResponse The current response
     * @param previousContext Elements from previous conversation
     * @return true if response references previous context
     */
    public static boolean maintainsContext(Msg currentResponse, String... previousContext) {
        return containsKeywords(currentResponse, previousContext);
    }

    /**
     * Validates multimodal tool response mentions file information.
     *
     * @param response The tool response
     * @return true if response mentions file paths, URLs, or media information
     */
    public static boolean mentionsFileInfo(Msg response) {
        return containsKeywords(
                response,
                "file",
                "url",
                "http",
                "image",
                "audio",
                "video",
                "path",
                ".jpg",
                ".png",
                ".wav",
                ".mp3",
                "found",
                "generated");
    }

    /**
     * Validates thinking mode response contains thinking indicators.
     *
     * @param response The thinking mode response
     * @return true if response shows thinking process
     */
    public static boolean showsThinkingProcess(Msg response) {
        return containsKeywords(
                response,
                "think",
                "consider",
                "analyze",
                "reason",
                "let me",
                "first",
                "then",
                "step");
    }

    /**
     * Validates response length meets minimum requirements.
     *
     * @param response The response to check
     * @param minLength Minimum expected length
     * @return true if response meets length requirement
     */
    public static boolean meetsMinimumLength(Msg response, int minLength) {
        String text = TestUtils.extractTextContent(response);
        return text != null && text.length() >= minLength;
    }

    /**
     * Validates response contains structured information (like lists, steps, etc.).
     *
     * @param response The response to check
     * @return true if response shows structured content
     */
    public static boolean hasStructuredContent(Msg response) {
        String text = TestUtils.extractTextContent(response);
        if (text == null) return false;

        // Look for indicators of structured content
        return text.matches(".*[0-9]+.*") // numbered lists
                || text.matches(".*[-*•].*") // bullet points
                || text.matches(".*[Ff]irst.*") // sequential language
                || text.matches(".*[Nn]ext.*")
                || text.matches(".*[Ff]inally.*");
    }
}
