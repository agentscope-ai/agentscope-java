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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.agentscope.core.util.JacksonJsonCodec;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OllamaEmbeddingResponse.
 */
@DisplayName("OllamaEmbeddingResponse Unit Tests")
class OllamaEmbeddingResponseTest {

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
    @DisplayName("Should create response with default constructor")
    void testDefaultConstructor() {
        OllamaEmbeddingResponse response = new OllamaEmbeddingResponse();

        assertNotNull(response);
        assertNull(response.getModel());
        assertNull(response.getEmbeddings());
        assertNull(response.getTotalDuration());
        assertNull(response.getLoadDuration());
        assertNull(response.getPromptEvalCount());
    }

    @Test
    @DisplayName("Should set and get all properties correctly")
    void testGettersAndSetters() {
        OllamaEmbeddingResponse response = new OllamaEmbeddingResponse();

        String model = "nomic-embed-text";
        List<float[]> embeddings =
                Arrays.asList(new float[] {0.1f, 0.2f, 0.3f}, new float[] {0.4f, 0.5f, 0.6f});
        Long totalDuration = 1000L;
        Long loadDuration = 500L;
        Integer promptEvalCount = 10;

        response.setModel(model);
        response.setEmbeddings(embeddings);
        response.setTotalDuration(totalDuration);
        response.setLoadDuration(loadDuration);
        response.setPromptEvalCount(promptEvalCount);

        assertEquals(model, response.getModel());
        assertEquals(embeddings, response.getEmbeddings());
        assertEquals(totalDuration, response.getTotalDuration());
        assertEquals(loadDuration, response.getLoadDuration());
        assertEquals(promptEvalCount, response.getPromptEvalCount());
    }

    @Test
    @DisplayName("Should serialize to JSON with snake_case")
    void testSerialization() throws Exception {
        OllamaEmbeddingResponse response = new OllamaEmbeddingResponse();
        response.setModel("nomic-embed-text");
        response.setEmbeddings(Arrays.asList(new float[] {0.1f, 0.2f, 0.3f}));
        response.setTotalDuration(1000L);
        response.setLoadDuration(500L);
        response.setPromptEvalCount(10);

        String json = jsonCodec.toJson(response);

        // Verify JSON contains snake_case fields
        assertTrue(json.contains("\"model\""));
        assertTrue(json.contains("\"embeddings\""));
        assertTrue(json.contains("\"total_duration\""));
        assertTrue(json.contains("\"load_duration\""));
        assertTrue(json.contains("\"prompt_eval_count\""));

        // Verify values are present
        assertTrue(json.contains("nomic-embed-text"));
        assertTrue(json.contains("0.1"));
        assertTrue(json.contains("1000"));
        assertTrue(json.contains("500"));
        assertTrue(json.contains("10"));
    }

    @Test
    @DisplayName("Should deserialize from JSON with snake_case")
    void testDeserialization() throws Exception {
        String json =
                """
                {
                    "model": "nomic-embed-text",
                    "embeddings": [[0.1, 0.2, 0.3], [0.4, 0.5, 0.6]],
                    "total_duration": 1000,
                    "load_duration": 500,
                    "prompt_eval_count": 10
                }
                """;

        OllamaEmbeddingResponse response = jsonCodec.fromJson(json, OllamaEmbeddingResponse.class);

        assertEquals("nomic-embed-text", response.getModel());
        assertNotNull(response.getEmbeddings());
        assertEquals(2, response.getEmbeddings().size());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, response.getEmbeddings().get(0), 0.001f);
        assertArrayEquals(new float[] {0.4f, 0.5f, 0.6f}, response.getEmbeddings().get(1), 0.001f);
        assertEquals(Long.valueOf(1000), response.getTotalDuration());
        assertEquals(Long.valueOf(500), response.getLoadDuration());
        assertEquals(Integer.valueOf(10), response.getPromptEvalCount());
    }

    @Test
    @DisplayName("Should handle null values during serialization/deserialization")
    void testNullValueHandling() throws Exception {
        OllamaEmbeddingResponse response = new OllamaEmbeddingResponse();
        response.setModel("nomic-embed-text");
        // Keep other fields as null

        String json = jsonCodec.toJson(response);
        // With JsonInclude.Include.NON_NULL, null fields should not appear in JSON
        assertTrue(json.contains("\"model\":\"nomic-embed-text\""));
        assertFalse(json.contains("\"embeddings\"")); // embeddings is null
        assertFalse(json.contains("\"total_duration\"")); // totalDuration is null
        assertFalse(json.contains("\"load_duration\"")); // loadDuration is null
        assertFalse(json.contains("\"prompt_eval_count\"")); // promptEvalCount is null

        OllamaEmbeddingResponse deserialized =
                jsonCodec.fromJson(json, OllamaEmbeddingResponse.class);
        assertNull(deserialized.getEmbeddings());
        assertNull(deserialized.getTotalDuration());
        assertNull(deserialized.getLoadDuration());
        assertNull(deserialized.getPromptEvalCount());
    }

    @Test
    @DisplayName("Should ignore unknown properties during deserialization")
    void testIgnoreUnknownProperties() throws Exception {
        String jsonWithUnknown =
                """
                {
                    "model": "nomic-embed-text",
                    "embeddings": [[0.1, 0.2, 0.3]],
                    "unknown_field": "unknown_value",
                    "another_unknown": 123
                }
                """;

        // Should not throw exception for unknown fields due to @JsonIgnoreProperties
        OllamaEmbeddingResponse response =
                jsonCodec.fromJson(jsonWithUnknown, OllamaEmbeddingResponse.class);

        assertEquals("nomic-embed-text", response.getModel());
        assertNotNull(response.getEmbeddings());
        assertEquals(1, response.getEmbeddings().size());
        assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, response.getEmbeddings().get(0), 0.001f);
    }
}
