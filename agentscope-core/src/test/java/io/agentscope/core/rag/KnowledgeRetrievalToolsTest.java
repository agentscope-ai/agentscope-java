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
package io.agentscope.core.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Unit tests for KnowledgeRetrievalTools.
 */
@Tag("unit")
@DisplayName("KnowledgeRetrievalTools Unit Tests")
class KnowledgeRetrievalToolsTest {

    private static Document makeDoc(String text) {
        return new Document(
                new DocumentMetadata(TextBlock.builder().text(text).build(), "doc-1", "0"));
    }

    @Test
    @DisplayName("When LLM omits limit, defaultConfig.getLimit() should be used")
    void testNullLimitFallsBackToDefaultConfig() {
        AtomicReference<RetrieveConfig> capturedConfig = new AtomicReference<>();

        Knowledge knowledge =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> documents) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        capturedConfig.set(config);
                        return Mono.just(List.of());
                    }
                };

        RetrieveConfig defaultConfig = RetrieveConfig.builder().limit(8).build();
        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge, defaultConfig);

        // Simulate LLM not providing limit (null)
        tools.retrieveKnowledge("test query", null, null);

        assertNotNull(capturedConfig.get());
        assertEquals(
                8,
                capturedConfig.get().getLimit(),
                "When limit is null, defaultConfig.getLimit() should be used, not hardcoded 5");
    }

    @Test
    @DisplayName("When LLM provides explicit limit, it should override defaultConfig")
    void testExplicitLimitOverridesDefault() {
        AtomicReference<RetrieveConfig> capturedConfig = new AtomicReference<>();

        Knowledge knowledge =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> documents) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        capturedConfig.set(config);
                        return Mono.just(List.of());
                    }
                };

        RetrieveConfig defaultConfig = RetrieveConfig.builder().limit(8).build();
        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge, defaultConfig);

        tools.retrieveKnowledge("test query", 3, null);

        assertNotNull(capturedConfig.get());
        assertEquals(
                3,
                capturedConfig.get().getLimit(),
                "Explicit limit from LLM should override defaultConfig.getLimit()");
    }

    @Test
    @DisplayName("formatDocumentsForTool should return no-results message for empty list")
    void testFormatEmptyResults() {
        Knowledge knowledge =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> docs) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        return Mono.just(List.of());
                    }
                };

        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
        String result = tools.retrieveKnowledge("query", null, null);

        assertTrue(
                result.contains("No relevant documents found"),
                "Empty results should return no-results message");
    }

    @Test
    @DisplayName("formatDocumentsForTool should include document content and score")
    void testFormatDocumentsWithScore() {
        Document doc = makeDoc("Important content");
        doc.setScore(0.87);

        Knowledge knowledge =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> docs) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        return Mono.just(List.of(doc));
                    }
                };

        KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
        String result = tools.retrieveKnowledge("query", null, null);

        assertTrue(result.contains("Important content"));
        assertTrue(result.contains("0.870"));
    }
}
