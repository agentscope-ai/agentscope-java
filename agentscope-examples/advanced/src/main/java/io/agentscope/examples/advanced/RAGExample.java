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
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.util.List;

/**
 * RAGExample - Demonstrates Retrieval-Augmented Generation (RAG) capabilities.
 */
public class RAGExample {

    private static final int EMBEDDING_DIMENSIONS = 1024;

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "RAG (Retrieval-Augmented Generation) Example",
                "This example demonstrates RAG capabilities:\n"
                        + "  - Creating and populating knowledge bases\n"
                        + "  - Generic mode: Automatic knowledge injection\n"
                        + "  - Agentic mode: Agent-controlled knowledge retrieval");

        // Get API key
        String apiKey = ExampleUtils.getDashScopeApiKey();

        // Create embedding model
        System.out.println("Creating embedding model...");
        EmbeddingModel embeddingModel =
                DashScopeTextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName("text-embedding-v3")
                        .dimensions(EMBEDDING_DIMENSIONS)
                        .build();
        System.out.println("Embedding model created\n");

        // Create vector store
        System.out.println("Creating vector store...");
        VDBStoreBase vectorStore = InMemoryStore.builder().dimensions(1024).build();
        System.out.println("Vector store created\n");

        // Create knowledge base
        System.out.println("Creating knowledge base...");
        Knowledge knowledge =
                SimpleKnowledge.builder()
                        .embeddingModel(embeddingModel)
                        .embeddingStore(vectorStore)
                        .build();
        System.out.println("Knowledge base created\n");

        // Add documents to knowledge base
        System.out.println("Adding documents to knowledge base...");
        addSampleDocuments(knowledge);
        System.out.println("Documents added\n");

        // Demonstrate Agentic Mode
        System.out.println("\n=== Agentic RAG Mode ===");
        System.out.println(
                "In Agentic mode, the agent decides when to retrieve knowledge\n"
                        + "using the retrieve_knowledge tool. This gives the agent more\n"
                        + "control over when to use knowledge.\n");
        demonstrateAgenticMode(apiKey, knowledge);

        System.out.println("\n=== All examples completed ===");
    }

    /**
     * Add sample documents to the knowledge base.
     *
     * @param knowledge the knowledge base to add documents to
     */
    private static void addSampleDocuments(Knowledge knowledge) {
        // Sample documents about AgentScope
        String[] documents = {
            "AgentScope is a multi-agent system framework developed by ModelScope. It provides a"
                    + " unified interface for building and managing multi-agent applications."
                    + " AgentScope supports both synchronous and asynchronous agent communication.",
            "AgentScope supports various agent types including ReActAgent, which implements the "
                    + "ReAct (Reasoning and Acting) algorithm. ReActAgent combines reasoning and "
                    + "acting in an iterative loop to solve complex tasks.",
            "RAG (Retrieval-Augmented Generation) is a technique that enhances language models by"
                    + " retrieving relevant information from a knowledge base before generating"
                    + " responses. This allows models to access up-to-date information and reduce"
                    + " hallucinations.",
            "AgentScope Java is the Java implementation of AgentScope framework. It provides "
                    + "reactive programming support using Project Reactor, making it suitable for "
                    + "building scalable multi-agent systems.",
            "Vector stores are used in RAG systems to store and search document embeddings. "
                    + "AgentScope supports in-memory vector stores and can integrate with external "
                    + "vector databases like Qdrant and ChromaDB."
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
                    knowledge.addDocuments(docs).block();
                    System.out.println(
                            "  Added document "
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
     * Demonstrate Generic RAG mode using built-in RAG configuration.
     *
     * @param apiKey the API key for the chat model
     * @param knowledge the knowledge base to use
     */
    private static void demonstrateGenericMode(String apiKey, Knowledge knowledge)
            throws IOException {
        // Create agent with built-in Generic RAG configuration
        ReActAgent agent =
                ReActAgent.builder()
                        .name("RAGAssistant")
                        .sysPrompt(
                                "You are a helpful assistant with access to a knowledge base. Use"
                                    + " the provided knowledge to answer questions accurately. If"
                                    + " the knowledge doesn't contain relevant information, say so"
                                    + " clearly.")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-max")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        // Built-in RAG configuration
                        .knowledge(knowledge)
                        .ragMode(RAGMode.GENERIC)
                        .retrieveConfig(
                                RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build())
                        .build();

        System.out.println("Generic mode agent created. Try asking:");
        System.out.println("  - 'What is AgentScope?'");
        System.out.println("  - 'What is RAG?'");
        System.out.println("  - 'What vector stores does AgentScope support?'\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Demonstrate Agentic RAG mode using built-in RAG configuration.
     *
     * @param apiKey the API key for the chat model
     * @param knowledge the knowledge base to use
     */
    private static void demonstrateAgenticMode(String apiKey, Knowledge knowledge)
            throws IOException {
        // Create agent with built-in Agentic RAG configuration
        ReActAgent agent =
                ReActAgent.builder()
                        .name("RAGAgent")
                        .sysPrompt(
                                "You are a helpful assistant with access to a knowledge retrieval"
                                    + " tool. When you need information from the knowledge base,"
                                    + " use the retrieve_knowledge tool. Always explain what you're"
                                    + " doing.")
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
                        // Built-in RAG configuration - Agentic mode
                        .knowledge(knowledge)
                        .ragMode(RAGMode.AGENTIC)
                        .build();

        System.out.println("Agentic mode agent created. Try asking:");
        System.out.println("  - 'What is AgentScope?'");
        System.out.println("  - 'Tell me about RAG'");
        System.out.println("  - 'What is ReActAgent?'\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }
}
