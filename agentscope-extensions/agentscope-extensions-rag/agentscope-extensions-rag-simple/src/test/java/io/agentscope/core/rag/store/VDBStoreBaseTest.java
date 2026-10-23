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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.store.dto.SearchDocumentDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for VDBStoreBase interface.
 *
 * <p>Tests the VDBStoreBase interface, including default methods.
 */
@Tag("unit")
@DisplayName("VDBStoreBase Interface Unit Tests")
class VDBStoreBaseTest {

    /**
     * Simple test implementation of VDBStoreBase interface.
     */
    static class TestVDBStore implements VDBStoreBase {
        @Override
        public Mono<Void> add(List<Document> documents) {
            return Mono.empty();
        }

        @Override
        public Mono<List<Document>> search(SearchDocumentDto searchDocumentDto) {
            return Mono.just(new ArrayList<>());
        }

        @Override
        public Mono<Boolean> delete(String id) {
            return Mono.just(Boolean.TRUE);
        }
    }

    @Test
    @DisplayName("Should delete documents")
    void testDelete() {
        VDBStoreBase store = new TestVDBStore();

        StepVerifier.create(store.delete("test-id"))
                .assertNext(
                        result -> {
                            assertEquals(Boolean.TRUE, result);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should add documents")
    void testAdd() {
        VDBStoreBase store = new TestVDBStore();
        TextBlock content = TextBlock.builder().text("Test content").build();
        DocumentMetadata metadata = new DocumentMetadata(content, "doc-1", "0");
        Document doc = new Document(metadata);
        doc.setEmbedding(new double[] {0.1, 0.2, 0.3});

        StepVerifier.create(store.add(List.of(doc))).verifyComplete();
    }

    @Test
    @DisplayName("Should search vectors and return results")
    void testSearch() {
        VDBStoreBase store = new TestVDBStore();
        double[] queryEmbedding = new double[] {0.1, 0.2, 0.3};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(queryEmbedding)
                                        .limit(10)
                                        .build()))
                .assertNext(
                        results -> {
                            assertTrue(results instanceof List);
                            assertEquals(0, results.size());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search vectors with score threshold")
    void testSearchWithScoreThreshold() {
        VDBStoreBase store = new TestVDBStore();
        double[] queryEmbedding = new double[] {0.1, 0.2, 0.3};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(queryEmbedding)
                                        .limit(10)
                                        .scoreThreshold(0.5)
                                        .build()))
                .assertNext(results -> assertTrue(results.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should search vectors with vector name")
    void testSearchWithVectorName() {
        VDBStoreBase store = new TestVDBStore();
        double[] queryEmbedding = new double[] {0.1, 0.2, 0.3};

        StepVerifier.create(
                        store.search(
                                SearchDocumentDto.builder()
                                        .queryEmbedding(queryEmbedding)
                                        .vectorName("test-vector")
                                        .limit(10)
                                        .scoreThreshold(0.5)
                                        .build()))
                .assertNext(results -> assertTrue(results.isEmpty()))
                .verifyComplete();
    }
}
