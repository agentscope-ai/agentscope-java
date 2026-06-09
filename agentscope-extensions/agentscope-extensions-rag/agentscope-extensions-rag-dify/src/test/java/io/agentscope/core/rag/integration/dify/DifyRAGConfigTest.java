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
package io.agentscope.core.rag.integration.dify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DifyRAGConfigTest {

    @Test
    void testBuilderWithRequiredFields() {
        DifyRAGConfig config =
                DifyRAGConfig.builder().apiKey("test-api-key").datasetId("dataset-123").build();

        assertNotNull(config);
        assertEquals("test-api-key", config.getApiKey());
        assertEquals("dataset-123", config.getDatasetId());
        assertEquals("https://api.dify.ai/v1", config.getApiBaseUrl()); // default
        assertEquals(RetrievalMode.HYBRID_SEARCH, config.getRetrievalMode()); // default
    }

    @Test
    void testBuilderWithAllFields() {
        RerankConfig rerankConfig = RerankConfig.builder().build();

        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .datasetId("dataset-123")
                        .apiBaseUrl("https://custom.dify.ai/v1")
                        .retrievalMode(RetrievalMode.SEMANTIC_SEARCH)
                        .topK(20)
                        .scoreThreshold(0.7)
                        .enableRerank(true)
                        .rerankConfig(rerankConfig)
                        .connectTimeout(Duration.ofSeconds(60))
                        .readTimeout(Duration.ofSeconds(120))
                        .maxRetries(5)
                        .build();

        assertEquals("https://custom.dify.ai/v1", config.getApiBaseUrl());
        assertEquals(RetrievalMode.SEMANTIC_SEARCH, config.getRetrievalMode());
        assertEquals(20, config.getTopK());
        assertEquals(0.7, config.getScoreThreshold());
        assertTrue(config.getEnableRerank());
        assertNotNull(config.getRerankConfig());
        assertEquals(Duration.ofSeconds(60), config.getConnectTimeout());
        assertEquals(Duration.ofSeconds(120), config.getReadTimeout());
        assertEquals(5, config.getMaxRetries());
    }

    @Test
    void testBuilderMissingApiKey() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DifyRAGConfig.builder().datasetId("dataset-123").build());
    }

    @Test
    void testBuilderMissingDatasetId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DifyRAGConfig.builder().apiKey("test-api-key").build());
    }

    @Test
    void testBuilderInvalidTopK() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DifyRAGConfig.builder()
                                .apiKey("test-api-key")
                                .datasetId("dataset-123")
                                .topK(0)
                                .build());
    }

    @Test
    void testBuilderInvalidScoreThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DifyRAGConfig.builder()
                                .apiKey("test-api-key")
                                .datasetId("dataset-123")
                                .scoreThreshold(-0.1)
                                .build());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DifyRAGConfig.builder()
                                .apiKey("test-api-key")
                                .datasetId("dataset-123")
                                .scoreThreshold(1.1)
                                .build());
    }

    @Test
    void testBuilderInvalidMaxRetries() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        DifyRAGConfig.builder()
                                .apiKey("test-api-key")
                                .datasetId("dataset-123")
                                .maxRetries(-1)
                                .build());
    }

    @Test
    void testCustomHeaders() {
        DifyRAGConfig config =
                DifyRAGConfig.builder()
                        .apiKey("test-api-key")
                        .datasetId("dataset-123")
                        .addCustomHeader("X-Custom-Header", "value")
                        .build();

        Map<String, String> headers = config.getCustomHeaders();
        assertNotNull(headers);
        assertEquals("value", headers.get("X-Custom-Header"));
    }

    @Test
    void testToString() {
        DifyRAGConfig config =
                DifyRAGConfig.builder().apiKey("test-api-key").datasetId("dataset-123").build();

        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("dataset-123"));
        assertFalse(str.contains("test-api-key")); // Should not expose API key
    }
}
