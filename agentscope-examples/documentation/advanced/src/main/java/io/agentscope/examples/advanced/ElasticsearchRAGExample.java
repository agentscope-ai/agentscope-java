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
package io.agentscope.examples.advanced;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.store.ElasticsearchStore;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ElasticsearchRAGExample - Demonstrates RAG capabilities using Elasticsearch as the vector store.
 *
 * <p>Prerequisites:
 * 1. An Elasticsearch instance running (e.g., via Docker: docker run -p 9200:9200 -e "discovery.type=single-node" -e "xpack.security.enabled=false" docker.elastic.co/elasticsearch/elasticsearch:8.12.0)
 * 2. DashScope API Key
 */
public class ElasticsearchRAGExample {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchRAGExample.class);

    // Configuration
    private static final int EMBEDDING_DIMENSIONS = 1024;

    // Elasticsearch Configuration (Modify these as per your setup)
    private static final String ES_URL = System.getProperty("es.url", "http://localhost:9200");
    private static final String ES_USERNAME = System.getProperty("es.user", "elastic");
    private static final String ES_PASSWORD = System.getProperty("es.pass", "changeme");
    private static final String ES_INDEX_NAME = "agentscope_rag_example";

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "Elasticsearch RAG Example",
                "This example demonstrates RAG capabilities using Elasticsearch:\n"
                        + "  - Connecting to Elasticsearch vector store\n"
                        + "  - Indexing documents with dense vectors\n"
                        + "  - Agentic knowledge retrieval backed by ES");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // 1. Create embedding model
        System.out.println("Creating embedding model...");
        EmbeddingModel embeddingModel =
                DashScopeTextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName("text-embedding-v3")
                        .dimensions(EMBEDDING_DIMENSIONS)
                        .build();
        System.out.println("Embedding model created.");

        // 2. Create Elasticsearch vector store
        System.out.println("Connecting to Elasticsearch at " + ES_URL + "...");

        // Note: We use try-with-resources to ensure the store is closed when done,
        // effectively closing the underlying HTTP clients.
        try (ElasticsearchStore vectorStore =
                ElasticsearchStore.builder()
                        .url(ES_URL)
                        .username(ES_USERNAME) // Set null if no auth
                        .password(ES_PASSWORD) // Set null if no auth
                        .indexName(ES_INDEX_NAME)
                        .dimensions(EMBEDDING_DIMENSIONS)
                        .build()) {

            System.out.println("Elasticsearch store initialized and connected.");

            // 3. Create knowledge base linking the model and the store
            System.out.println("Creating knowledge base...");
            Knowledge knowledge =
                    SimpleKnowledge.builder()
                            .embeddingModel(embeddingModel)
                            .embeddingStore(vectorStore)
                            .build();
            System.out.println("Knowledge base created.");

            // 4. Add documents to knowledge base (Elasticsearch)
            System.out.println("Adding documents to Elasticsearch...");
            // In a real scenario, you might check if data exists first to avoid duplication
            addSampleDocuments(knowledge);
            System.out.println("Documents added to index: " + ES_INDEX_NAME);

            // 5. Demonstrate Agentic Mode
            System.out.println("\n=== Agentic RAG Mode with Elasticsearch ===");
            demonstrateAgenticMode(apiKey, knowledge);

        } catch (Exception e) {
            log.error("Error running Elasticsearch RAG Example", e);
            System.err.println("Error: " + e.getMessage());
            System.err.println("Make sure Elasticsearch is running at " + ES_URL);
        }

        System.out.println("\n=== Example completed ===");
        // System.exit(0) is often needed with async libraries to kill lingering threads
        System.exit(0);
    }

    /**
     * Add sample documents to the knowledge base.
     */
    private static void addSampleDocuments(Knowledge knowledge) {
        // Sample documents about AgentScope and Elasticsearch
        String[] documents = {
            "AgentScope is a multi-agent system framework developed by ModelScope. It provides a"
                    + " unified interface for building and managing multi-agent applications.",
            "Elasticsearch is a distributed, RESTful search and analytics engine capable of "
                    + "performing vector similarity search using kNN (k-nearest neighbors).",
            "This specific example demonstrates how to replace the InMemoryStore with an "
                    + "ElasticsearchStore in AgentScope to persist knowledge data.",
            "RAG (Retrieval-Augmented Generation) combines LLMs with external knowledge retrieval "
                    + "to reduce hallucinations and provide up-to-date information.",
            "AgentScope allows developers to easily switch between different vector store"
                    + " implementations via the VDBStoreBase interface."
        };

        // Create reader for text documents
        TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);

        // Add each document
        for (int i = 0; i < documents.length; i++) {
            String docText = documents[i];
            ReaderInput input = ReaderInput.fromString(docText);

            try {
                List<Document> docs = reader.read(input).block();
                if (docs != null && !docs.isEmpty()) {
                    // This will embed the document and push it to Elasticsearch
                    knowledge.addDocuments(docs).block();
                    System.out.println(
                            "  Indexed document "
                                    + (i + 1)
                                    + ": "
                                    + docText.substring(0, Math.min(50, docText.length()))
                                    + "...");
                }
            } catch (Exception e) {
                System.err.println("  Error adding document " + (i + 1) + ": " + e.getMessage());
            }
        }
    }

    /**
     * Demonstrate Agentic RAG mode using built-in RAG configuration.
     */
    private static void demonstrateAgenticMode(String apiKey, Knowledge knowledge)
            throws IOException {
        // Create agent with built-in Agentic RAG configuration
        ReActAgent agent =
                ReActAgent.builder()
                        .name("ES_RAG_Agent")
                        .sysPrompt(
                                "You are a helpful assistant with access to an Elasticsearch"
                                    + " knowledge base. When user asks technical questions, use the"
                                    + " retrieve_knowledge tool to find the answer in the database."
                                    + " Always cite your source if possible.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .toolkit(new Toolkit())
                        .memory(new InMemoryMemory())
                        // Bind the Elasticsearch-backed Knowledge base
                        .knowledge(knowledge)
                        .ragMode(RAGMode.AGENTIC)
                        .build();

        System.out.println("Agent created. Try asking:");
        System.out.println("  - 'What is AgentScope?'");
        System.out.println("  - 'How is Elasticsearch used here?'");
        System.out.println("  - 'Explain RAG.'\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }
}
