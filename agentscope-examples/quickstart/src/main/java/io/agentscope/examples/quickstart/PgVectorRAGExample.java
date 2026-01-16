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
package io.agentscope.examples.quickstart;

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
import io.agentscope.core.rag.store.PgVectorStore;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.util.List;

/**
 * PgVectorRAGExample - Demonstrates local RAG with PostgreSQL pgvector and DashScope embedding.
 *
 * <p>This example shows how to build a local knowledge base using:
 * <ul>
 *   <li>DashScope text-embedding-v3 for generating embeddings</li>
 *   <li>PostgreSQL with pgvector extension for vector storage</li>
 *   <li>SimpleKnowledge for knowledge management</li>
 *   <li>ReActAgent for question answering</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>PostgreSQL 11+ with pgvector extension installed</li>
 *   <li>Run: CREATE EXTENSION IF NOT EXISTS vector;</li>
 *   <li>Set environment variables: DASHSCOPE_API_KEY, PG_HOST, PG_PORT, PG_DATABASE, PG_USER, PG_PASSWORD</li>
 * </ul>
 */
public class PgVectorRAGExample {

    private static final int EMBEDDING_DIMENSIONS = 1024;
    private static final String TABLE_NAME = "miss2_knowledge_embeddings";

    public static void main(String[] args) throws Exception {
        // Print welcome message
        ExampleUtils.printWelcome(
                "PgVector Local RAG Example",
                "This example demonstrates local RAG capabilities with PostgreSQL pgvector:\n"
                        + "  - DashScope text-embedding-v3 for embeddings\n"
                        + "  - PostgreSQL pgvector for vector storage\n"
                        + "  - Adding documents and Q&A with knowledge base");

        // Get configuration from environment
        String apiKey = ExampleUtils.getDashScopeApiKey();
        String pgHost = getEnvOrDefault("PG_HOST", "localhost");
        String pgPort = getEnvOrDefault("PG_PORT", "5432");
        String pgDatabase = getEnvOrDefault("PG_DATABASE", "vectordb");
        String pgUser = getEnvOrDefault("PG_USER", "postgres");
        String pgPassword = getEnvOrDefault("PG_PASSWORD", "postgres");

        String jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s", pgHost, pgPort, pgDatabase);

        System.out.println("PostgreSQL Configuration:");
        System.out.println("  - Host: " + pgHost);
        System.out.println("  - Port: " + pgPort);
        System.out.println("  - Database: " + pgDatabase);
        System.out.println("  - User: " + pgUser);
        System.out.println("  - Table: " + TABLE_NAME);
        System.out.println();

        // Create embedding model
        System.out.println("Creating DashScope embedding model...");
        EmbeddingModel embeddingModel =
                DashScopeTextEmbedding.builder()
                        .apiKey(apiKey)
                        .modelName("text-embedding-v3")
                        .dimensions(EMBEDDING_DIMENSIONS)
                        .build();
        System.out.println(
                "✓ Embedding model created (text-embedding-v3, dimensions: "
                        + EMBEDDING_DIMENSIONS
                        + ")\n");

        // Create PgVector store
        System.out.println("Connecting to PostgreSQL with pgvector...");
        try (PgVectorStore vectorStore =
                PgVectorStore.builder()
                        .jdbcUrl(jdbcUrl)
                        .username(pgUser)
                        .password(pgPassword)
                        .tableName(TABLE_NAME)
                        .dimensions(EMBEDDING_DIMENSIONS)
                        .distanceType(PgVectorStore.DistanceType.COSINE)
                        .build()) {

            System.out.println("✓ PgVectorStore connected successfully\n");

            // Create knowledge base
            System.out.println("Creating knowledge base...");
            Knowledge knowledge =
                    SimpleKnowledge.builder()
                            .embeddingModel(embeddingModel)
                            .embeddingStore(vectorStore)
                            .build();
            System.out.println("✓ Knowledge base created\n");

            // Add documents to knowledge base
            System.out.println("Adding sample documents to knowledge base...");
            addSampleDocuments(knowledge);
            System.out.println("✓ Documents added successfully\n");

            // Create and run QA agent
            System.out.println("=== Starting Q&A Agent ===");
            System.out.println("The agent can answer questions based on the knowledge base.");
            System.out.println("Try asking about AgentScope, RAG, or vector stores.\n");

            runQAAgent(apiKey, knowledge);
        }

        System.out.println("\n=== Example completed ===");
    }

    /**
     * Add sample documents to the knowledge base.
     */
    private static void addSampleDocuments(Knowledge knowledge) {
        String[] documents = {
            "AgentScope是由ModelScope团队开发的多智能体系统框架。它提供了统一的接口来构建和管理多智能体应用程序。"
                    + "AgentScope支持同步和异步的智能体通信方式，使开发者能够灵活地设计复杂的智能体交互流程。",
            "AgentScope支持多种类型的智能体，包括ReActAgent。ReActAgent实现了ReAct（推理与行动）算法，"
                    + "它在迭代循环中结合推理和行动来解决复杂任务。这种方式使智能体能够进行深度思考并采取有效行动。",
            "RAG（检索增强生成）是一种通过在生成响应之前从知识库中检索相关信息来增强语言模型的技术。" + "这使模型能够访问最新信息并减少幻觉现象，提高回答的准确性和可靠性。",
            "AgentScope Java是AgentScope框架的Java实现版本。它使用Project Reactor提供响应式编程支持，"
                    + "使其适合构建可扩展的多智能体系统。Java版本保持了与Python版本一致的API设计理念。",
            "向量数据库用于RAG系统中存储和搜索文档嵌入。AgentScope支持多种向量数据库，包括内存存储、"
                    + "Qdrant、Milvus、PgVector等。PgVector是PostgreSQL的向量扩展，支持高效的相似性搜索。",
            "PgVector是PostgreSQL数据库的向量搜索扩展。它支持L2距离、内积和余弦相似度等多种距离度量。"
                    + "PgVector使用HNSW索引来加速向量搜索，非常适合生产环境中的RAG应用。"
        };

        // Create reader for text documents
        TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);

        for (int i = 0; i < documents.length; i++) {
            String docText = documents[i];
            ReaderInput input = ReaderInput.fromString(docText);

            try {
                List<Document> docs = reader.read(input).block();
                if (docs != null && !docs.isEmpty()) {
                    knowledge.addDocuments(docs).block();
                    System.out.println(
                            "  ["
                                    + (i + 1)
                                    + "/"
                                    + documents.length
                                    + "] "
                                    + docText.substring(0, Math.min(40, docText.length()))
                                    + "...");
                }
            } catch (Exception e) {
                System.err.println("  Error adding document " + (i + 1) + ": " + e.getMessage());
            }
        }
    }

    /**
     * Create and run the Q&A agent with knowledge base.
     */
    private static void runQAAgent(String apiKey, Knowledge knowledge) throws IOException {
        // Create agent with Agentic RAG mode
        ReActAgent agent =
                ReActAgent.builder()
                        .name("KnowledgeAssistant")
                        .sysPrompt(
                                "你是一个智能助手，可以访问知识库来回答问题。当用户提问时，"
                                        + "使用retrieve_knowledge工具从知识库中检索相关信息，然后基于检索到的内容给出准确的回答。"
                                        + "如果知识库中没有相关信息，请诚实地告知用户。")
                        .model(
                                DashScopeChatModel.builder()
                                        .apiKey(apiKey)
                                        .modelName("qwen-plus")
                                        .stream(true)
                                        .enableThinking(false)
                                        .formatter(new DashScopeChatFormatter())
                                        .build())
                        .memory(new InMemoryMemory())
                        .toolkit(new Toolkit())
                        .knowledge(knowledge)
                        .ragMode(RAGMode.AGENTIC)
                        .retrieveConfig(
                                RetrieveConfig.builder().limit(3).scoreThreshold(0.3).build())
                        .build();

        System.out.println("Suggested questions to try:");
        System.out.println("  - 什么是AgentScope？");
        System.out.println("  - RAG技术有什么作用？");
        System.out.println("  - PgVector支持哪些距离度量？");
        System.out.println("  - ReActAgent是如何工作的？\n");

        // Start interactive chat
        ExampleUtils.startChat(agent);
    }

    /**
     * Get environment variable value or return default.
     */
    private static String getEnvOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }
}
