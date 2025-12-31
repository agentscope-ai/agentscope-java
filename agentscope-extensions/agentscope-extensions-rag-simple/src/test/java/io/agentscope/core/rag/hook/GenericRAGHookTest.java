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
package io.agentscope.core.rag.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.GenericRAGHook;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import java.util.ArrayList;
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
 * Unit tests for GenericRAGHook.
 */
@Tag("unit")
@DisplayName("GenericRAGHook Unit Tests")
class GenericRAGHookTest {

    private static final int DIMENSIONS = 3;

    private Knowledge knowledge;
    private GenericRAGHook hook;
    private AgentBase mockAgent;

    @BeforeEach
    void setUp() {
        TestMockEmbeddingModel embeddingModel = new TestMockEmbeddingModel(DIMENSIONS);
        InMemoryStore vectorStore = InMemoryStore.builder().dimensions(DIMENSIONS).build();
        knowledge =
                SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
        hook = new GenericRAGHook(knowledge);
        mockAgent =
                new AgentBase("MockAgent") {
                    @Override
                    protected Mono<Msg> doCall(List<Msg> msgs) {
                        return Mono.just(msgs.get(0));
                    }

                    @Override
                    protected Mono<Void> doObserve(Msg msg) {
                        return Mono.empty();
                    }

                    @Override
                    protected Mono<Msg> handleInterrupt(
                            InterruptContext context, Msg... originalArgs) {
                        return Mono.just(
                                Msg.builder()
                                        .name(getName())
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder().text("Interrupted").build())
                                        .build());
                    }
                };
    }

    @Test
    @DisplayName("Should create GenericRAGHook with default configuration")
    void testCreateWithDefaults() {
        GenericRAGHook newHook = new GenericRAGHook(knowledge);
        assertNotNull(newHook);
        assertEquals(knowledge, newHook.getKnowledgeBase());
        assertNotNull(newHook.getDefaultConfig());
        assertTrue(newHook.isEnableOnlyForUserQueries());
    }

    @Test
    @DisplayName("Should create GenericRAGHook with custom configuration")
    void testCreateWithCustomConfig() {
        RetrieveConfig config = RetrieveConfig.builder().limit(10).scoreThreshold(0.7).build();
        GenericRAGHook newHook = new GenericRAGHook(knowledge, config, false);
        assertNotNull(newHook);
        assertEquals(config, newHook.getDefaultConfig());
        assertTrue(!newHook.isEnableOnlyForUserQueries());
    }

    @Test
    @DisplayName("Should throw exception for null knowledge base")
    void testCreateNullKnowledgeBase() {
        assertThrows(IllegalArgumentException.class, () -> new GenericRAGHook(null));
    }

    @Test
    @DisplayName("Should throw exception for null config")
    void testCreateNullConfig() {
        assertThrows(
                IllegalArgumentException.class, () -> new GenericRAGHook(knowledge, null, true));
    }

    @Test
    @DisplayName("Should have high priority")
    void testPriority() {
        assertEquals(50, hook.priority());
    }

    @Test
    @DisplayName("Should handle PreCallEvent and inject knowledge")
    void testHandlePreCallEvent() {
        // Add documents to knowledge base
        Document doc1 = createDocument("doc1", "Machine learning is interesting");
        knowledge.addDocuments(List.of(doc1)).block();

        // Create user message
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is machine learning?").build())
                        .build();

        PreCallEvent event = new PreCallEvent(mockAgent, new ArrayList<>(List.of(userMsg)));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            assertTrue(result instanceof PreCallEvent);
                            PreCallEvent preCallEvent = (PreCallEvent) result;
                            List<Msg> enhancedMessages = preCallEvent.getInputMessages();

                            // Should have knowledge message + original message
                            assertTrue(enhancedMessages.size() >= 2);
                            // First message should be system message with knowledge
                            Msg firstMsg = enhancedMessages.get(1);
                            assertEquals(MsgRole.SYSTEM, firstMsg.getRole());
                            assertTrue(firstMsg.getTextContent().contains("knowledge base"));
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should skip non-user messages when enableOnlyForUserQueries is true")
    void testSkipNonUserMessages() {
        // Create assistant message
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response").build())
                        .build();

        List<Msg> inputMessages = List.of(assistantMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            PreReasoningEvent preReasoningEvent = (PreReasoningEvent) result;
                            List<Msg> messages = preReasoningEvent.getInputMessages();
                            // Should not add knowledge message
                            assertEquals(1, messages.size());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should process all messages when enableOnlyForUserQueries is false")
    void testProcessAllMessages() {
        GenericRAGHook hookAll = new GenericRAGHook(knowledge, hook.getDefaultConfig(), false);

        // Add documents
        Document doc = createDocument("doc1", "Test content");
        knowledge.addDocuments(List.of(doc)).block();

        // Create assistant message
        Msg assistantMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Response").build())
                        .build();

        List<Msg> inputMessages = List.of(assistantMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        StepVerifier.create(hookAll.onEvent(event))
                .assertNext(
                        result -> {
                            PreReasoningEvent preReasoningEvent = (PreReasoningEvent) result;
                            List<Msg> messages = preReasoningEvent.getInputMessages();
                            // Should try to extract query (may be empty)
                            assertNotNull(messages);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty messages")
    void testHandleEmptyMessages() {
        List<Msg> emptyMessages = List.of();

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, emptyMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            PreReasoningEvent preReasoningEvent = (PreReasoningEvent) result;
                            assertTrue(preReasoningEvent.getInputMessages().isEmpty());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle empty query")
    void testHandleEmptyQuery() {
        // Create user message with empty content
        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("").build())
                        .build();

        List<Msg> inputMessages = List.of(userMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            PreReasoningEvent preReasoningEvent = (PreReasoningEvent) result;
                            // Should not modify messages for empty query
                            assertEquals(1, preReasoningEvent.getInputMessages().size());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle retrieval errors gracefully")
    void testHandleRetrievalError() {
        // Create a knowledge base that will fail
        TestMockEmbeddingModel errorModel = new TestMockEmbeddingModel(DIMENSIONS);
        errorModel.setShouldThrowError(true);
        InMemoryStore vectorStore = InMemoryStore.builder().dimensions(DIMENSIONS).build();
        Knowledge errorKB =
                SimpleKnowledge.builder()
                        .embeddingModel(errorModel)
                        .embeddingStore(vectorStore)
                        .build();
        GenericRAGHook errorHook = new GenericRAGHook(errorKB);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test query").build())
                        .build();

        List<Msg> inputMessages = List.of(userMsg);

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        // Should not throw, but return original event
        StepVerifier.create(errorHook.onEvent(event))
                .assertNext(
                        result -> {
                            PreReasoningEvent preReasoningEvent = (PreReasoningEvent) result;
                            // Should return original messages without modification
                            assertEquals(1, preReasoningEvent.getInputMessages().size());
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should ignore non-PreReasoningEvent events")
    void testIgnoreOtherEvents() {
        PreCallEvent preCallEvent = new PreCallEvent(mockAgent, List.of());

        StepVerifier.create(hook.onEvent(preCallEvent))
                .assertNext(result -> assertEquals(preCallEvent, result))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should format knowledge content correctly")
    void testFormatKnowledgeContent() {
        // Add documents with scores
        Document doc1 = createDocument("doc1", "Content 1");
        doc1.setScore(0.9);
        Document doc2 = createDocument("doc2", "Content 2");
        doc2.setScore(0.8);
        knowledge.addDocuments(List.of(doc1, doc2)).block();

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("query").build())
                        .build();

        List<Msg> inputMessages = new ArrayList<>(List.of(userMsg));

        PreCallEvent event = new PreCallEvent(mockAgent, inputMessages);

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        result -> {
                            PreCallEvent preCallEvent = (PreCallEvent) result;
                            List<Msg> messages = preCallEvent.getInputMessages();
                            Msg knowledgeMsg = messages.get(1);

                            String content = knowledgeMsg.getTextContent();
                            assertTrue(content.contains("knowledge base"));
                            assertTrue(
                                    content.contains("Content 1") || content.contains("Content 2"));
                        })
                .verifyComplete();
    }

    /**
     * Creates a test document.
     */
    private Document createDocument(String docId, String content) {
        TextBlock textBlock = TextBlock.builder().text(content).build();
        DocumentMetadata metadata = new DocumentMetadata(textBlock, docId, "0");
        return new Document(metadata);
    }

    /**
     * Mock EmbeddingModel for testing.
     */
    private static class TestMockEmbeddingModel implements EmbeddingModel {
        private final int dimensions;
        private final Map<String, double[]> embeddings = new HashMap<>();
        private boolean shouldThrowError = false;

        TestMockEmbeddingModel(int dimensions) {
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
            if (block instanceof TextBlock) {
                String text = ((TextBlock) block).getText();
                return Mono.fromCallable(
                        () -> {
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
            double[] embedding = new double[dimensions];
            int hash = text.hashCode();
            for (int i = 0; i < dimensions; i++) {
                embedding[i] = (double) ((hash + i) % 100) / 100.0;
            }
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
