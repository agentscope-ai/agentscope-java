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
package io.agentscope.core.rag.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.exception.VectorStoreException;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * Unit tests for InMemoryStore.
 */
@Tag("unit")
@DisplayName("InMemoryStore Unit Tests")
class InMemoryStoreTest {

    private InMemoryStore store;
    private static final int DIMENSIONS = 3;

    @BeforeEach
    void setUp() {
        store = InMemoryStore.builder().dimensions(DIMENSIONS).build();
    }

    @Test
    @DisplayName("Should create store with specified dimensions")
    void testCreateStore() {
        InMemoryStore newStore = InMemoryStore.builder().dimensions(1024).build();
        assertEquals(1024, newStore.getDimensions());
        assertTrue(newStore.isEmpty());
    }

    @Test
    @DisplayName("Should create store with default dimensions")
    void testCreateStoreDefault() {
        InMemoryStore defaultStore = InMemoryStore.builder().build();
        assertEquals(1024, defaultStore.getDimensions());
    }

    @Test
    @DisplayName("Should throw exception for invalid dimensions")
    void testCreateStoreInvalidDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> InMemoryStore.builder().dimensions(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> InMemoryStore.builder().dimensions(-1).build());
    }

    @Test
    @DisplayName("Should add documents to store")
    void testAdd() {
        Document doc = createDocument("doc-1", "Test content", new double[] {1.0, 2.0, 3.0});

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();

        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("Should throw error when adding null document list")
    void testAddNullList() {
        StepVerifier.create(store.add(null)).expectError(IllegalArgumentException.class).verify();
    }

    @Test
    @DisplayName("Should throw error when adding null document")
    void testAddNullDocument() {
        // Create a list containing null to trigger validation
        List<Document> listWithNull = new java.util.ArrayList<>();
        listWithNull.add(null);

        StepVerifier.create(store.add(listWithNull))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when adding document without embedding")
    void testAddNoEmbedding() {
        Document doc = createDocument("doc-1", "Test", null);

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when embedding dimension mismatch")
    void testAddDimensionMismatch() {
        Document doc = createDocument("doc-1", "Test", new double[] {1.0, 2.0}); // Wrong dimension

        StepVerifier.create(store.add(List.of(doc)))
                .expectError(VectorStoreException.class)
                .verify();
    }

    @Test
    @DisplayName("Should replace existing document with same UUID")
    void testAddReplace() {
        // Documents with same docId and content will have same UUID
        Document doc1 = createDocument("doc-1", "Same content", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-1", "Same content", new double[] {4.0, 5.0, 6.0});

        store.add(List.of(doc1)).block();
        store.add(List.of(doc2)).block();

        // Should have same UUID and thus replace
        assertEquals(doc1.getId(), doc2.getId());
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("Should add multiple documents in batch")
    void testAddBatch() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {4.0, 5.0, 6.0});

        StepVerifier.create(store.add(List.of(doc1, doc2))).verifyComplete();

        assertEquals(2, store.size());
    }

    @Test
    @DisplayName("Should handle empty document list")
    void testAddEmptyList() {
        StepVerifier.create(store.add(List.of())).verifyComplete();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should search for similar vectors")
    void testSearch() {
        // Add some documents with different embeddings
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {0.0, 1.0, 0.0});
        Document doc3 = createDocument("doc-3", "Content 3", new double[] {0.0, 0.0, 1.0});

        store.add(List.of(doc1, doc2, doc3)).block();

        // Search for vector similar to doc1's embedding
        double[] query = {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 2, null))
                .assertNext(
                        results -> {
                            assertEquals(2, results.size());
                            // First result should be doc-1 (identical, similarity = 1.0)
                            assertEquals(doc1.getId(), results.get(0).getId());
                            assertEquals(1.0, results.get(0).getScore(), 1e-9);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return empty list when store is empty")
    void testSearchEmptyStore() {
        double[] query = {1.0, 2.0, 3.0};

        StepVerifier.create(store.search(query, 5, null))
                .assertNext(results -> assertTrue(results.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return top K results")
    void testSearchTopK() {
        // Add 5 documents
        for (int i = 0; i < 5; i++) {
            Document doc =
                    createDocument("doc-" + i, "Content " + i, new double[] {(double) i, 0.0, 0.0});
            store.add(List.of(doc)).block();
        }

        double[] query = {0.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 3, null))
                .assertNext(results -> assertEquals(3, results.size()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return results sorted by similarity")
    void testSearchSorted() {
        Document doc1 =
                createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0}); // Most similar
        Document doc2 =
                createDocument("doc-2", "Content 2", new double[] {0.5, 0.5, 0.0}); // Less similar
        Document doc3 =
                createDocument("doc-3", "Content 3", new double[] {0.0, 1.0, 0.0}); // Least similar

        store.add(List.of(doc1, doc2, doc3)).block();

        double[] query = {1.0, 0.0, 0.0};

        StepVerifier.create(store.search(query, 3, null))
                .assertNext(
                        results -> {
                            assertEquals(3, results.size());
                            // Results should be sorted by similarity (descending)
                            assertTrue(results.get(0).getScore() >= results.get(1).getScore());
                            assertTrue(results.get(1).getScore() >= results.get(2).getScore());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should filter by score threshold")
    void testSearchWithScoreThreshold() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 0.0, 0.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {0.5, 0.5, 0.0});
        Document doc3 = createDocument("doc-3", "Content 3", new double[] {0.0, 1.0, 0.0});

        store.add(List.of(doc1, doc2, doc3)).block();

        double[] query = {1.0, 0.0, 0.0};

        // Set high threshold to filter out less similar documents
        StepVerifier.create(store.search(query, 10, 0.9))
                .assertNext(
                        results -> {
                            // Only doc1 should pass the threshold (similarity = 1.0)
                            assertEquals(1, results.size());
                            assertEquals(doc1.getId(), results.get(0).getId());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when searching with null query")
    void testSearchNullQuery() {
        StepVerifier.create(store.search(null, 5, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should throw error when searching with invalid limit")
    void testSearchInvalidLimit() {
        double[] query = {1.0, 2.0, 3.0};

        StepVerifier.create(store.search(query, 0, null))
                .expectError(IllegalArgumentException.class)
                .verify();

        StepVerifier.create(store.search(query, -1, null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should delete document from store")
    void testDelete() {
        Document doc = createDocument("doc-1", "Test content", new double[] {1.0, 2.0, 3.0});

        store.add(List.of(doc)).block();
        assertEquals(1, store.size());

        StepVerifier.create(store.delete(doc.getId()))
                .assertNext(deleted -> assertTrue(deleted))
                .verifyComplete();

        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("Should return false when deleting non-existent document")
    void testDeleteNonExistent() {
        StepVerifier.create(store.delete("non-existent-uuid"))
                .assertNext(deleted -> assertFalse(deleted))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should throw error when deleting with null ID")
    void testDeleteNullId() {
        StepVerifier.create(store.delete(null))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("Should clear all documents")
    void testClear() {
        Document doc1 = createDocument("doc-1", "Content 1", new double[] {1.0, 2.0, 3.0});
        Document doc2 = createDocument("doc-2", "Content 2", new double[] {4.0, 5.0, 6.0});

        store.add(List.of(doc1, doc2)).block();

        assertEquals(2, store.size());

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.isEmpty());
    }

    @Test
    @DisplayName("Should maintain thread safety")
    void testThreadSafety() throws InterruptedException {
        int numThreads = 10;
        int docsPerThread = 10;
        Thread[] threads = new Thread[numThreads];

        // Concurrent adds
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] =
                    new Thread(
                            () -> {
                                for (int j = 0; j < docsPerThread; j++) {
                                    String docId = "doc-" + threadId + "-" + j;
                                    Document doc =
                                            createDocument(
                                                    docId,
                                                    "Content " + threadId + "-" + j,
                                                    new double[] {
                                                        (double) threadId, (double) j, 0.0
                                                    });
                                    store.add(List.of(doc)).block();
                                }
                            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(numThreads * docsPerThread, store.size());
    }

    @Test
    @DisplayName("Should not modify original embedding array")
    void testImmutableEmbedding() {
        double[] original = {1.0, 2.0, 3.0};
        Document doc = createDocument("doc-1", "Test", original);

        store.add(List.of(doc)).block();

        // Modify original array
        original[0] = 999.0;

        // Search should still use the original values (defensive copy was made)
        double[] query = {1.0, 2.0, 3.0};
        List<Document> results = store.search(query, 1, null).block();

        assertNotNull(results);
        assertEquals(1, results.size());
        // The stored vector should not be affected by the modification
        // We verify this by checking the search result is still similar to the original query
        assertTrue(results.get(0).getScore() > 0.9);
    }

    /**
     * Helper method to create a test document.
     */
    private Document createDocument(String docId, String content, double[] embedding) {
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        Document doc = new Document(metadata);
        if (embedding != null) {
            doc.setEmbedding(embedding);
        }
        return doc;
    }
}
