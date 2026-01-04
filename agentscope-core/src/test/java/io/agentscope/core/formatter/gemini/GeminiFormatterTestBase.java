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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for Gemini formatter tests.
 * Provides common test utilities and temporary file management.
 */
public abstract class GeminiFormatterTestBase {

    protected static final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    protected Path tempImageFile;
    protected Path tempAudioFile;

    @BeforeEach
    void setUpBase() throws IOException {
        // Create temporary test files
        tempImageFile = Files.createTempFile("test_image", ".png");
        Files.write(tempImageFile, "fake image content".getBytes());

        tempAudioFile = Files.createTempFile("test_audio", ".mp3");
        Files.write(tempAudioFile, "fake audio content".getBytes());
    }

    @AfterEach
    void tearDownBase() throws IOException {
        // Clean up temporary files
        if (tempImageFile != null && Files.exists(tempImageFile)) {
            Files.deleteIfExists(tempImageFile);
        }
        if (tempAudioFile != null && Files.exists(tempAudioFile)) {
            Files.deleteIfExists(tempAudioFile);
        }
    }

    /**
     * Convert an object to formatted JSON string.
     *
     * @param obj Object to convert
     * @return JSON string
     */
    protected String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert object to JSON", e);
        }
    }

    /**
     * Parse JSON string to object.
     *
     * @param json JSON string
     * @param clazz Target class
     * @param <T> Type parameter
     * @return Parsed object
     */
    protected <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    /**
     * Deep compare two JSON strings by normalizing and comparing their structure.
     * This method parses both JSON strings and compares the resulting objects.
     *
     * @param expectedJson Expected JSON string
     * @param actualJson Actual JSON string
     */
    protected void assertJsonEquals(String expectedJson, String actualJson) {
        try {
            Object expected = objectMapper.readValue(expectedJson, Object.class);
            Object actual = objectMapper.readValue(actualJson, Object.class);

            // Serialize back to normalized JSON for comparison
            String normalizedExpected = objectMapper.writeValueAsString(expected);
            String normalizedActual = objectMapper.writeValueAsString(actual);

            assertEquals(
                    normalizedExpected,
                    normalizedActual,
                    "JSON structures do not match.\nExpected:\n"
                            + objectMapper
                                    .writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(expected)
                            + "\n\nActual:\n"
                            + objectMapper
                                    .writerWithDefaultPrettyPrinter()
                                    .writeValueAsString(actual));
        } catch (Exception e) {
            throw new AssertionError("Failed to compare JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Deep compare two objects by converting to JSON and comparing structure.
     *
     * @param expected Expected object
     * @param actual Actual object
     */
    protected void assertObjectEquals(Object expected, Object actual) {
        String expectedJson = toJson(expected);
        String actualJson = toJson(actual);
        assertJsonEquals(expectedJson, actualJson);
    }
}
