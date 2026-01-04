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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Toolkit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Unit tests for ReActAgent's built-in RAG configuration.
 */
@Tag("unit")
@DisplayName("ReActAgent RAG Configuration Tests")
class ReActAgentRAGConfigTest {

    private Model mockModel;
    private Knowledge mockKnowledge;

    @BeforeEach
    void setUp() {
        // Create a simple mock model
        mockModel =
                new Model() {
                    @Override
                    public Flux<ChatResponse> stream(
                            List<Msg> messages,
                            List<io.agentscope.core.model.ToolSchema> tools,
                            io.agentscope.core.model.GenerateOptions options) {
                        // Return a simple finish response
                        ChatResponse response =
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("Test response")
                                                                .build()))
                                        .build();
                        return Flux.just(response);
                    }

                    @Override
                    public String getModelName() {
                        return "mock-model";
                    }
                };

        // Create a simple mock knowledge base
        mockKnowledge =
                new Knowledge() {
                    private final List<Document> documents = new ArrayList<>();

                    @Override
                    public Mono<Void> addDocuments(List<Document> docs) {
                        documents.addAll(docs);
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        // Return mock documents with scores
                        List<Document> results = new ArrayList<>();
                        for (int i = 0; i < Math.min(config.getLimit(), 2); i++) {
                            DocumentMetadata metadata =
                                    new DocumentMetadata(
                                            TextBlock.builder().text("Mock content " + i).build(),
                                            "doc-" + i,
                                            "chunkId");
                            Document doc = new Document(metadata);
                            doc.setScore(0.9 - i * 0.1);
                            results.add(doc);
                        }
                        return Mono.just(results);
                    }
                };
    }

    @Test
    @DisplayName("Should configure Generic RAG mode")
    void testGenericRAGMode() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.GENERIC)
                        .build();

        assertNotNull(agent);
        assertEquals("TestAgent", agent.getName());
    }

    @Test
    @DisplayName("Should configure Agentic RAG mode")
    void testAgenticRAGMode() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.AGENTIC)
                        .build();

        assertNotNull(agent);
        // Verify that knowledge retrieval tool was registered
        assertTrue(
                agent.getToolkit().getToolNames().contains("retrieve_knowledge"),
                "Knowledge retrieval tool should be registered in Agentic mode");
    }

    @Test
    @DisplayName("Should configure with custom RetrieveConfig")
    void testCustomRetrieveConfig() {
        RetrieveConfig customConfig =
                RetrieveConfig.builder().limit(10).scoreThreshold(0.8).build();

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.GENERIC)
                        .retrieveConfig(customConfig)
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should configure with multiple knowledge bases")
    void testMultipleKnowledgeBases() {
        Knowledge kb1 = mockKnowledge;
        Knowledge kb2 =
                new Knowledge() {
                    @Override
                    public Mono<Void> addDocuments(List<Document> documents) {
                        return Mono.empty();
                    }

                    @Override
                    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
                        DocumentMetadata metadata =
                                new DocumentMetadata(
                                        TextBlock.builder().text("KB2 content").build(),
                                        "kb2-doc",
                                        "chunkId2");
                        Document doc = new Document(metadata);
                        doc.setScore(0.95);
                        return Mono.just(List.of(doc));
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledges(List.of(kb1, kb2))
                        .ragMode(RAGMode.GENERIC)
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should not configure RAG when mode is NONE")
    void testNoneRAGMode() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.NONE)
                        .build();

        assertNotNull(agent);
        // Verify that no knowledge retrieval tool was registered
        assertTrue(
                !agent.getToolkit().getToolNames().contains("retrieve_knowledge"),
                "Knowledge retrieval tool should not be registered in NONE mode");
    }

    @Test
    @DisplayName("Should not configure RAG when no knowledge bases provided")
    void testNoKnowledgeBases() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .ragMode(RAGMode.GENERIC)
                        .build();

        assertNotNull(agent);
        // No exception should be thrown
    }

    @Test
    @DisplayName("Should configure enableOnlyForUserQueries")
    void testEnableOnlyForUserQueries() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.GENERIC)
                        .enableOnlyForUserQueries(false)
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should use default RAG mode as GENERIC")
    void testDefaultRAGMode() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        // Not specifying ragMode - should default to GENERIC
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should handle null knowledge gracefully")
    void testNullKnowledge() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(null) // Null knowledge should be ignored
                        .ragMode(RAGMode.GENERIC)
                        .build();

        assertNotNull(agent);
    }

    @Test
    @DisplayName("Should add single knowledge base using knowledge() method")
    void testSingleKnowledgeMethod() {
        ReActAgent agent =
                ReActAgent.builder()
                        .name("TestAgent")
                        .model(mockModel)
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        .knowledge(mockKnowledge)
                        .ragMode(RAGMode.AGENTIC)
                        .build();

        assertNotNull(agent);
        assertTrue(agent.getToolkit().getToolNames().contains("retrieve_knowledge"));
    }
}
