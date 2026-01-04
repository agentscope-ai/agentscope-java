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
package io.agentscope.core.rag.integration.haystack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.rag.integration.haystack.model.HayStackDocument;
import io.agentscope.core.rag.integration.haystack.model.SparseEmbedding;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for HayStackConfig.
 */
class HayStackConfigTest {

    @Nested
    class RequiredParametersTest {

        @Test
        void shouldBuildWithMinimumRequiredFields() {
            HayStackConfig config =
                    HayStackConfig.builder().baseUrl("http://localhost:8000/retrieve").build();

            assertNotNull(config);
            assertEquals("http://localhost:8000/retrieve", config.getBaseUrl());
        }

        @Test
        void shouldThrowWhenBaseUrlMissing() {
            assertThrows(IllegalArgumentException.class, () -> HayStackConfig.builder().build());
        }
    }

    @Nested
    class DefaultValuesTest {

        @Test
        void shouldHaveCorrectDefaults() {
            HayStackConfig config =
                    HayStackConfig.builder().baseUrl("http://localhost:8000/retrieve").build();

            assertEquals(3, config.getTopK());
            assertEquals(Duration.ofSeconds(30), config.getTimeout());
            assertEquals(3, config.getMaxRetries());
            assertEquals(0, config.getSuccessCode());
            assertNull(config.getScaleScore());
            assertNull(config.getReturnEmbedding());
            assertNull(config.getScoreThreshold());
            assertNull(config.getGroupBy());
            assertNull(config.getFilters());
        }
    }

    @Nested
    class RetrievalParametersTest {

        @Test
        void shouldSetTopK() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .topK(10)
                            .build();

            assertEquals(10, config.getTopK());
        }

        @Test
        void shouldThrowForInvalidTopK() {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            HayStackConfig.builder()
                                    .baseUrl("http://localhost:8000/retrieve")
                                    .topK(0)
                                    .build());
        }

        @Test
        void shouldSetScoreThreshold() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .scoreThreshold(0.4d)
                            .build();

            assertEquals(0.4d, config.getScoreThreshold());
        }

        @Test
        void shouldSetScaleScoreAndReturnEmbedding() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .scaleScore(true)
                            .returnEmbedding(true)
                            .build();

            assertTrue(config.getScaleScore());
            assertTrue(config.getReturnEmbedding());
        }
    }

    @Nested
    class GroupingAndWindowTest {

        @Test
        void shouldSetGroupByAndGroupSize() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .groupBy("source_id")
                            .groupSize(5)
                            .build();

            assertEquals("source_id", config.getGroupBy());
            assertEquals(5, config.getGroupSize());
        }

        @Test
        void shouldSetWindowSize() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .windowSize(3)
                            .build();

            assertEquals(3, config.getWindowSize());
        }
    }

    @Nested
    class EmbeddingTest {

        @Test
        void shouldSetQueryEmbedding() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .queryEmbedding(List.of(0.1f, 0.2f, 0.3f))
                            .build();

            assertNotNull(config.getQueryEmbedding());
            assertEquals(3, config.getQueryEmbedding().size());
        }

        @Test
        void shouldSetSparseEmbedding() {
            SparseEmbedding sparseEmbedding =
                    new SparseEmbedding.Builder()
                            .indices(List.of(1, 10, 20))
                            .values(List.of(0.3f, 0.2f, 0.1f))
                            .build();

            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .querySparseEmbedding(sparseEmbedding)
                            .build();

            assertNotNull(config.getQuerySparseEmbedding());
            assertEquals(3, config.getQuerySparseEmbedding().getIndices().size());
        }
    }

    @Nested
    class FiltersTest {

        @Test
        void shouldSetFilters() {
            Map<String, Object> filters = Map.of("author", "Toby", "year", Map.of("$gte", 2024));

            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .filters(filters)
                            .build();

            assertNotNull(config.getFilters());
            assertEquals("Toby", config.getFilters().get("author"));
        }

        @Test
        void shouldSetFilterPolicy() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .filterPolicy(FilterPolicy.MERGE)
                            .build();

            assertEquals(FilterPolicy.MERGE, config.getFilterPolicy());
        }
    }

    @Nested
    class DocumentsTest {

        @Test
        void shouldSetDocuments() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("doc-1");

            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .documents(List.of(doc))
                            .build();

            assertEquals(1, config.getDocuments().size());
        }

        @Test
        void shouldSetRetrievedDocuments() {
            HayStackDocument doc = new HayStackDocument();
            doc.setId("doc-1");

            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .retrievedDocuments(List.of(doc))
                            .build();

            assertEquals(1, config.getRetrievedDocuments().size());
        }
    }

    @Nested
    class HttpConfigurationTest {

        @Test
        void shouldSetHttpConfiguration() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
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
                            HayStackConfig.builder()
                                    .baseUrl("http://localhost:8000/retrieve")
                                    .maxRetries(-1)
                                    .build());
        }

        @Test
        void shouldSetCustomHeaders() {
            HayStackConfig config =
                    HayStackConfig.builder()
                            .baseUrl("http://localhost:8000/retrieve")
                            .addCustomHeader("X-Request-ID", "req-123")
                            .addCustomHeader("X-Trace-ID", "trace-456")
                            .build();

            assertEquals("req-123", config.getCustomHeaders().get("X-Request-ID"));
            assertEquals("trace-456", config.getCustomHeaders().get("X-Trace-ID"));
        }
    }

    @Test
    void shouldBuildCompleteConfiguration() {
        HayStackConfig config =
                HayStackConfig.builder()
                        .baseUrl("http://localhost:8000/retrieve")
                        .topK(20)
                        .scaleScore(true)
                        .returnEmbedding(true)
                        .scoreThreshold(0.5d)
                        .groupBy("source_id")
                        .groupSize(3)
                        .windowSize(2)
                        .filters(Map.of("category", "AI"))
                        .timeout(Duration.ofSeconds(45))
                        .maxRetries(4)
                        .addCustomHeader("X-Test", "value")
                        .build();

        assertNotNull(config);
        assertEquals(20, config.getTopK());
        assertTrue(config.getScaleScore());
        assertTrue(config.getReturnEmbedding());
        assertEquals(0.5f, config.getScoreThreshold());
        assertEquals("source_id", config.getGroupBy());
        assertEquals(3, config.getGroupSize());
        assertEquals(2, config.getWindowSize());
        assertEquals("AI", config.getFilters().get("category"));
        assertEquals(Duration.ofSeconds(45), config.getTimeout());
        assertEquals(4, config.getMaxRetries());
        assertEquals("value", config.getCustomHeaders().get("X-Test"));
    }
}
