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
package io.agentscope.core.rag.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for SimpleKnowledge.
 */
@Tag("unit")
@DisplayName("SimpleKnowledge Unit Tests")
class SimpleKnowledgeTest {

    private static final int DIMENSIONS = 3;

    private MockEmbeddingModel embeddingModel;
    private InMemoryStore vectorStore;
    private SimpleKnowledge knowledgeBase;

    @BeforeEach
    void setUp() {
        embeddingModel = new MockEmbeddingModel(DIMENSIONS);
        vectorStore = InMemoryStore.builder().dimensions(DIMENSIONS).build();
        knowledgeBase =
                SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
    }

    @Test
    @DisplayName("Should create SimpleKnowledge with valid parameters")
    void testCreate() {
        SimpleKnowledge kb =
                SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
        assertNotNull(kb);
        assertEquals(embeddingModel, kb.getEmbeddingModel());
        assertEquals(vectorStore, kb.getEmbeddingStore());
    }

    @Test
    @DisplayName("Should throw exception for null embedding model")
    void testCreateNullEmbeddingModel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SimpleKnowledge.builder().embeddingStore(vectorStore).build());
    }

    @Test
    @DisplayName("Should throw exception for null vector store")
    void testCreateNullVectorStore() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SimpleKnowledge.builder().embeddingModel(embeddingModel).build());
    }

    @Test
    @DisplayName("Should add documents to knowledge base")
    void testAddDocuments() {
        Document doc1 = createDocument("doc1", "Content 1");
        Document doc2 = createDocument("doc2", "Content 2");

        StepVerifier.create(knowledgeBase.addDocuments(List.of(doc1, doc2))).verifyComplete();

        // Size() returns -1 for SimpleKnowledge, check vector store instead
        assertEquals(2, vectorStore.size());
    }

    @Test
    @DisplayName("Should add documents to knowledge base with vector name")
    void testAddWithVectorName() {
        Document doc1 = createDocument("doc1", "Content 1");
        doc1.setVectorName("test-vector");
        StepVerifier.create(knowledgeBase.addDocuments(List.of(doc1))).verifyComplete();
        assertEquals(1, vectorStore.size());
    }

    @Test
    @DisplayName("Should handle empty document list")
    void testAddEmptyDocuments() {
        StepVerifier.create(knowledgeBase.addDocuments(List.of())).verifyComplete();
        // Empty list should not add anything
        assertEquals(0, vectorStore.size());
    }

    @Test
    @DisplayName("Should throw error for null document list")
    void testAddNullDocuments() {
        StepVerifier.create(knowledgeBase.addDocuments(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should retrieve documents by query")
    void testRetrieve() {
        // Add documents
        Document doc1 = createDocument("doc1", "Machine learning is interesting");
        Document doc2 = createDocument("doc2", "Java programming language");
        knowledgeBase.addDocuments(List.of(doc1, doc2)).block();

        // Retrieve
        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.0).build();

        StepVerifier.create(knowledgeBase.retrieve("machine learning", config))
                .assertNext(
                        results -> {
                            assertNotNull(results);
                            assertTrue(results.size() > 0);
                            for (Document doc : results) {
                                assertNotNull(doc.getScore());
                                assertTrue(doc.getScore() >= config.getScoreThreshold());
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should filter results by score threshold")
    void testRetrieveWithThreshold() {
        Document doc1 = createDocument("doc1", "Machine learning");
        knowledgeBase.addDocuments(List.of(doc1)).block();

        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.9).build();

        StepVerifier.create(knowledgeBase.retrieve("machine learning", config))
                .assertNext(
                        results -> {
                            for (Document doc : results) {
                                assertTrue(doc.getScore() >= 0.9);
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should limit results by config limit")
    void testRetrieveWithLimit() {
        // Add multiple documents
        for (int i = 0; i < 10; i++) {
            Document doc = createDocument("doc" + i, "Content " + i);
            knowledgeBase.addDocuments(List.of(doc)).block();
        }

        RetrieveConfig config = RetrieveConfig.builder().limit(3).scoreThreshold(0.0).build();

        StepVerifier.create(knowledgeBase.retrieve("query", config))
                .assertNext(results -> assertTrue(results.size() <= 3))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return empty list for empty query")
    void testRetrieveEmptyQuery() {
        RetrieveConfig config = RetrieveConfig.builder().build();

        StepVerifier.create(knowledgeBase.retrieve("", config))
                .assertNext(results -> assertTrue(results.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error for null query")
    void testRetrieveNullQuery() {
        RetrieveConfig config = RetrieveConfig.builder().build();

        StepVerifier.create(knowledgeBase.retrieve(null, config))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error for null config")
    void testRetrieveNullConfig() {
        StepVerifier.create(knowledgeBase.retrieve("query", null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should return results sorted by score")
    void testRetrieveSorted() {
        Document doc1 = createDocument("doc1", "Machine learning");
        Document doc2 = createDocument("doc2", "Deep learning");
        knowledgeBase.addDocuments(List.of(doc1, doc2)).block();

        RetrieveConfig config = RetrieveConfig.builder().limit(5).scoreThreshold(0.0).build();

        StepVerifier.create(knowledgeBase.retrieve("machine learning", config))
                .assertNext(
                        results -> {
                            if (results.size() > 1) {
                                for (int i = 0; i < results.size() - 1; i++) {
                                    assertTrue(
                                            results.get(i).getScore()
                                                    >= results.get(i + 1).getScore());
                                }
                            }
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should retrieve documents filtered by vector name")
    void testRetrieveWithVectorName() {
        Document doc1 = createDocument("doc1", "Machine learning");
        doc1.setVectorName("test-vector");
        Document doc2 = createDocument("doc2", "Machine learning");
        knowledgeBase.addDocuments(List.of(doc1, doc2)).block();

        RetrieveConfig config =
                RetrieveConfig.builder()
                        .vectorName("test-vector")
                        .limit(3)
                        .scoreThreshold(0.0)
                        .build();

        StepVerifier.create(knowledgeBase.retrieve("Machine learning", config))
                .assertNext(
                        results -> {
                            assertEquals(doc1.getId(), results.get(0).getId());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle clear operation")
    void testClear() {
        Document doc = createDocument("doc1", "Content");
        knowledgeBase.addDocuments(List.of(doc)).block();

        assertEquals(1, vectorStore.size());

        // After clear(), documents remain (clear is not implemented for SimpleKnowledge)
        // To actually clear, you would need to use vectorStore.clear() directly
        vectorStore.clear();
        assertEquals(0, vectorStore.size());
    }

    /**
     * Creates a test document.
     */
    private Document createDocument(String docId, String content) {
        io.agentscope.core.message.TextBlock textBlock =
                io.agentscope.core.message.TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        return new Document(metadata);
    }

    /**
     * Mock EmbeddingModel for testing.
     */
    private static class MockEmbeddingModel implements EmbeddingModel {
        private final int dimensions;
        private final Map<String, double[]> embeddings = new HashMap<>();
        private boolean shouldThrowError = false;

        MockEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        void setShouldThrowError(boolean shouldThrowError) {
            this.shouldThrowError = shouldThrowError;
        }

        @Override
        public Mono<double[]> embed(io.agentscope.core.message.ContentBlock block) {
            if (shouldThrowError) {
                return Mono.error(new RuntimeException("Mock embedding error"));
            }
            if (block instanceof io.agentscope.core.message.TextBlock) {
                String text = ((io.agentscope.core.message.TextBlock) block).getText();
                return Mono.fromCallable(
                        () -> {
                            // Generate deterministic embedding based on text
                            double[] embedding =
                                    embeddings.computeIfAbsent(text, k -> generateEmbedding(text));
                            return embedding.clone();
                        });
            }
            return Mono.error(new UnsupportedOperationException("Unsupported content block type"));
        }

        @Override
        public String getModelName() {
            return "mock-embedding-model";
        }

        @Override
        public int getDimensions() {
            return dimensions;
        }

        private double[] generateEmbedding(String text) {
            // Generate a simple deterministic embedding
            double[] embedding = new double[dimensions];
            int hash = text.hashCode();
            for (int i = 0; i < dimensions; i++) {
                embedding[i] = (double) ((hash + i) % 100) / 100.0;
            }
            // Normalize
            double norm = 0.0;
            for (double v : embedding) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < dimensions; i++) {
                    embedding[i] /= norm;
                }
            }
            return embedding;
        }
    }
}
