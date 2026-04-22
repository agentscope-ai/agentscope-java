/*
 * Copyright 2024-2026 the original author or authors.
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
package io.agentscope.core.formatter.ollama.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.util.JacksonJsonCodec;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaEmbeddingRequest.
 */
@DisplayName("OllamaEmbeddingRequest Unit Tests")
class OllamaEmbeddingRequestTest {

    private static final String TEST_MODEL = "nomic-embed-text";
    private static final List<String> TEST_INPUT = Arrays.asList("Hello", "World");

    private JacksonJsonCodec jsonCodec;

    @BeforeEach
    void setUp() {
        // Create a JacksonJsonCodec instance with snake_case naming strategy for testing
        JacksonJsonCodec defaultCodec = new JacksonJsonCodec();
        jsonCodec =
                new JacksonJsonCodec(
                        defaultCodec
                                .getObjectMapper()
                                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }

    @Test
    @DisplayName("Should create request with default constructor")
    void testDefaultConstructor() {
        OllamaEmbeddingRequest request = new OllamaEmbeddingRequest();

        assertNotNull(request);
        assertNull(request.getModel());
        assertNull(request.getInput());
        assertNull(request.getKeepAlive());
        assertNull(request.getTruncate());
        assertNull(request.getOptions());
    }

    @Test
    @DisplayName("Should create request with parameterized constructor")
    void testParameterizedConstructor() {
        OllamaEmbeddingRequest request = new OllamaEmbeddingRequest(TEST_MODEL, TEST_INPUT);

        assertEquals(TEST_MODEL, request.getModel());
        assertEquals(TEST_INPUT, request.getInput());
        assertNull(request.getKeepAlive());
        assertNull(request.getTruncate());
        assertNull(request.getOptions());
    }

    @Test
    @DisplayName("Should set and get all properties correctly")
    void testGettersAndSetters() {
        OllamaEmbeddingRequest request = new OllamaEmbeddingRequest();

        // Set all properties
        String keepAlive = "10m";
        Boolean truncate = true;
        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", 2048);

        request.setModel(TEST_MODEL);
        request.setInput(TEST_INPUT);
        request.setKeepAlive(keepAlive);
        request.setTruncate(truncate);
        request.setOptions(options);

        // Verify all properties
        assertEquals(TEST_MODEL, request.getModel());
        assertEquals(TEST_INPUT, request.getInput());
        assertEquals(keepAlive, request.getKeepAlive());
        assertEquals(truncate, request.getTruncate());
        assertEquals(options, request.getOptions());
    }

    @Test
    @DisplayName("Should serialize to JSON with snake_case")
    void testSerialization() throws Exception {
        OllamaEmbeddingRequest request = new OllamaEmbeddingRequest(TEST_MODEL, TEST_INPUT);
        request.setKeepAlive("10m");
        request.setTruncate(true);
        Map<String, Object> options = new HashMap<>();
        options.put("num_ctx", 2048);
        request.setOptions(options);

        String json = jsonCodec.toJson(request);

        // Verify JSON contains snake_case fields
        assertTrue(json.contains("\"model\""));
        assertTrue(json.contains("\"input\""));
        assertTrue(json.contains("\"keep_alive\""));
        assertTrue(json.contains("\"truncate\""));
        assertTrue(json.contains("\"options\""));

        // Verify values are present
        assertTrue(json.contains(TEST_MODEL));
        assertTrue(json.contains("Hello"));
        assertTrue(json.contains("World"));
        assertTrue(json.contains("10m"));
        assertTrue(json.contains("true"));
        assertTrue(json.contains("num_ctx"));
    }

    @Test
    @DisplayName("Should deserialize from JSON with snake_case")
    void testDeserialization() throws Exception {
        String json =
                """
                {
                    "model": "nomic-embed-text",
                    "input": ["Hello", "World"],
                    "keep_alive": "10m",
                    "truncate": true,
                    "options": {
                        "num_ctx": 2048
                    }
                }
                """;

        OllamaEmbeddingRequest request = jsonCodec.fromJson(json, OllamaEmbeddingRequest.class);

        assertEquals("nomic-embed-text", request.getModel());
        assertEquals(Arrays.asList("Hello", "World"), request.getInput());
        assertEquals("10m", request.getKeepAlive());
        assertEquals(Boolean.TRUE, request.getTruncate());
        assertNotNull(request.getOptions());
        assertEquals(2048, request.getOptions().get("num_ctx"));
    }

    @Test
    @DisplayName("Should handle null values during serialization/deserialization")
    void testNullValueHandling() throws Exception {
        OllamaEmbeddingRequest request = new OllamaEmbeddingRequest();
        request.setModel(TEST_MODEL);
        // Keep other fields as null

        String json = jsonCodec.toJson(request);
        // With JsonInclude.Include.NON_NULL, null fields should not appear in JSON
        assertTrue(json.contains("\"model\":\"" + TEST_MODEL + "\""));
        assertFalse(json.contains("\"input\"")); // input is null
        assertFalse(json.contains("\"keep_alive\"")); // keepAlive is null
        assertFalse(json.contains("\"truncate\"")); // truncate is null
        assertFalse(json.contains("\"options\"")); // options is null

        OllamaEmbeddingRequest deserialized =
                jsonCodec.fromJson(json, OllamaEmbeddingRequest.class);
        assertNull(deserialized.getInput());
        assertNull(deserialized.getKeepAlive());
        assertNull(deserialized.getTruncate());
        assertNull(deserialized.getOptions());
    }
}
