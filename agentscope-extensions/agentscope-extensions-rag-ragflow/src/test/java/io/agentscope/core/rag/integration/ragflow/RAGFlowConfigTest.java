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
package io.agentscope.core.rag.integration.ragflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for RAGFlowConfig.
 */
class RAGFlowConfigTest {

    @Nested
    class RequiredParametersTest {

        @Test
        void shouldBuildWithMinimumRequiredFields() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .build();

            assertNotNull(config);
            assertEquals("test-api-key", config.getApiKey());
            assertEquals("http://localhost:9380", config.getBaseUrl());
            assertEquals(1, config.getDatasetIds().size());
            assertEquals("dataset-123", config.getDatasetIds().get(0));
        }

        @Test
        void shouldBuildWithDocumentIdsOnly() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDocumentId("doc-123")
                            .build();

            assertNotNull(config);
            assertTrue(config.getDatasetIds().isEmpty());
            assertEquals(1, config.getDocumentIds().size());
            assertEquals("doc-123", config.getDocumentIds().get(0));
        }

        @Test
        void shouldThrowWhenApiKeyMissing() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .build());
        }

        @Test
        void shouldThrowWhenBaseUrlMissing() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .addDatasetId("dataset-123")
                                    .build());
        }

        @Test
        void shouldThrowWhenBothIdsAreMissing() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .build());
        }
    }

    @Nested
    class DefaultValuesTest {

        @Test
        void shouldHaveCorrectDefaults() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .build();

            assertEquals(1024, config.getTopK());
            assertEquals(0.2, config.getSimilarityThreshold());
            assertEquals(0.3, config.getVectorSimilarityWeight());
            assertEquals(1, config.getPage());
            assertEquals(30, config.getPageSize());
            assertEquals(Duration.ofSeconds(30), config.getTimeout());
            assertEquals(3, config.getMaxRetries());
            assertNull(config.getUseKg());
            assertNull(config.getTocEnhance());
            assertNull(config.getKeyword());
            assertNull(config.getHighlight());
        }
    }

    @Nested
    class RetrievalParametersTest {

        @Test
        void shouldSetTopK() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .topK(50)
                            .build();

            assertEquals(50, config.getTopK());
        }

        @Test
        void shouldThrowForInvalidTopK() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .topK(0)
                                    .build());

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .topK(-1)
                                    .build());
        }

        @Test
        void shouldSetSimilarityThreshold() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .similarityThreshold(0.5)
                            .build();

            assertEquals(0.5, config.getSimilarityThreshold());
        }

        @Test
        void shouldThrowForInvalidSimilarityThreshold() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .similarityThreshold(-0.1)
                                    .build());

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .similarityThreshold(1.1)
                                    .build());
        }

        @Test
        void shouldSetVectorSimilarityWeight() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .vectorSimilarityWeight(0.7)
                            .build();

            assertEquals(0.7, config.getVectorSimilarityWeight());
        }

        @Test
        void shouldThrowForInvalidVectorSimilarityWeight() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .vectorSimilarityWeight(-0.1)
                                    .build());

            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .vectorSimilarityWeight(1.1)
                                    .build());
        }
    }

    @Nested
    class PaginationTest {

        @Test
        void shouldSetPagination() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .page(2)
                            .pageSize(50)
                            .build();

            assertEquals(2, config.getPage());
            assertEquals(50, config.getPageSize());
        }

        @Test
        void shouldThrowForInvalidPage() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .page(0)
                                    .build());
        }

        @Test
        void shouldThrowForInvalidPageSize() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .pageSize(0)
                                    .build());
        }
    }

    @Nested
    class AdvancedFeaturesTest {

        @Test
        void shouldSetAdvancedFeatures() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .useKg(true)
                            .tocEnhance(true)
                            .rerankId(1)
                            .keyword(true)
                            .highlight(true)
                            .build();

            assertTrue(config.getUseKg());
            assertTrue(config.getTocEnhance());
            assertEquals(1, config.getRerankId());
            assertTrue(config.getKeyword());
            assertTrue(config.getHighlight());
        }

        @Test
        void shouldSetCrossLanguages() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .addCrossLanguage("en")
                            .addCrossLanguage("zh")
                            .addCrossLanguage("ja")
                            .build();

            assertNotNull(config.getCrossLanguages());
            assertEquals(3, config.getCrossLanguages().size());
            assertTrue(config.getCrossLanguages().contains("en"));
            assertTrue(config.getCrossLanguages().contains("zh"));
            assertTrue(config.getCrossLanguages().contains("ja"));
        }

        @Test
        void shouldSetCrossLanguagesViaList() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .crossLanguages(List.of("en", "zh"))
                            .build();

            assertEquals(2, config.getCrossLanguages().size());
        }
    }

    @Nested
    class MetadataConditionTest {

        @Test
        void shouldSetMetadataCondition() {
            Map<String, Object> condition =
                    Map.of(
                            "logic",
                            "and",
                            "conditions",
                            List.of(
                                    Map.of(
                                            "name", "author",
                                            "comparison_operator", "=",
                                            "value", "Toby")));

            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .metadataCondition(condition)
                            .build();

            assertNotNull(config.getMetadataCondition());
            assertEquals("and", config.getMetadataCondition().get("logic"));
        }
    }

    @Nested
    class HttpConfigurationTest {

        @Test
        void shouldSetHttpConfiguration() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .timeout(Duration.ofSeconds(60))
                            .maxRetries(5)
                            .build();

            assertEquals(Duration.ofSeconds(60), config.getTimeout());
            assertEquals(5, config.getMaxRetries());
        }

        @Test
        void shouldThrowForNegativeMaxRetries() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RAGFlowConfig.builder()
                                    .apiKey("test-api-key")
                                    .baseUrl("http://localhost:9380")
                                    .addDatasetId("dataset-123")
                                    .maxRetries(-1)
                                    .build());
        }

        @Test
        void shouldSetCustomHeaders() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-123")
                            .addCustomHeader("X-Custom-Header", "value1")
                            .addCustomHeader("X-Request-ID", "req-123")
                            .build();

            Map<String, String> headers = config.getCustomHeaders();
            assertNotNull(headers);
            assertEquals("value1", headers.get("X-Custom-Header"));
            assertEquals("req-123", headers.get("X-Request-ID"));
        }
    }

    @Nested
    class MultipleIdsTest {

        @Test
        void shouldSupportMultipleDatasetIds() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-1")
                            .addDatasetId("dataset-2")
                            .addDatasetId("dataset-3")
                            .build();

            assertEquals(3, config.getDatasetIds().size());
        }

        @Test
        void shouldSupportDatasetIdsViaList() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .datasetIds(List.of("dataset-1", "dataset-2"))
                            .build();

            assertEquals(2, config.getDatasetIds().size());
        }

        @Test
        void shouldSupportMultipleDocumentIds() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDocumentId("doc-1")
                            .addDocumentId("doc-2")
                            .build();

            assertEquals(2, config.getDocumentIds().size());
        }

        @Test
        void shouldSupportBothDatasetAndDocumentIds() {
            RAGFlowConfig config =
                    RAGFlowConfig.builder()
                            .apiKey("test-api-key")
                            .baseUrl("http://localhost:9380")
                            .addDatasetId("dataset-1")
                            .addDocumentId("doc-1")
                            .build();

            assertFalse(config.getDatasetIds().isEmpty());
            assertFalse(config.getDocumentIds().isEmpty());
        }
    }

    @Test
    void shouldBuildCompleteConfiguration() {
        Map<String, Object> metadataCondition =
                Map.of("logic", "and", "conditions", List.of(Map.of("name", "author")));

        RAGFlowConfig config =
                RAGFlowConfig.builder()
                        .apiKey("test-api-key")
                        .baseUrl("http://localhost:9380")
                        .addDatasetId("dataset-1")
                        .addDatasetId("dataset-2")
                        .addDocumentId("doc-1")
                        .topK(100)
                        .similarityThreshold(0.5)
                        .vectorSimilarityWeight(0.7)
                        .page(2)
                        .pageSize(50)
                        .useKg(true)
                        .tocEnhance(true)
                        .rerankId(1)
                        .keyword(true)
                        .highlight(true)
                        .addCrossLanguage("en")
                        .metadataCondition(metadataCondition)
                        .timeout(Duration.ofSeconds(60))
                        .maxRetries(5)
                        .addCustomHeader("X-Custom", "value")
                        .build();

        assertNotNull(config);
        assertEquals("test-api-key", config.getApiKey());
        assertEquals("http://localhost:9380", config.getBaseUrl());
        assertEquals(2, config.getDatasetIds().size());
        assertEquals(1, config.getDocumentIds().size());
        assertEquals(100, config.getTopK());
        assertEquals(0.5, config.getSimilarityThreshold());
        assertEquals(0.7, config.getVectorSimilarityWeight());
        assertEquals(2, config.getPage());
        assertEquals(50, config.getPageSize());
        assertTrue(config.getUseKg());
        assertTrue(config.getTocEnhance());
        assertEquals(1, config.getRerankId());
        assertTrue(config.getKeyword());
        assertTrue(config.getHighlight());
        assertEquals(1, config.getCrossLanguages().size());
        assertNotNull(config.getMetadataCondition());
        assertEquals(Duration.ofSeconds(60), config.getTimeout());
        assertEquals(5, config.getMaxRetries());
        assertEquals("value", config.getCustomHeaders().get("X-Custom"));
    }
}
