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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;

/**
 * Knowledge retrieval tools for Agentic RAG mode.
 *
 * <p>This class provides tool methods that can be registered with agents to enable
 * autonomous knowledge retrieval. Agents can call these tools to search the knowledge
 * base when they need information.
 *
 * <p>This is the Agentic mode implementation - agents decide when and how to retrieve
 * knowledge from the knowledge base.
 *
 * <p>Example usage:
 * <pre>{@code
 * KnowledgeBase knowledgeBase = new SimpleKnowledge(embeddingModel, vectorStore);
 * KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledgeBase);
 *
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(tools);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .toolkit(toolkit)
 *     .build();
 * }</pre>
 */
public class KnowledgeRetrievalTools {

    private final Knowledge knowledge;

    /**
     * Creates a new KnowledgeRetrievalTools instance.
     *
     * @param knowledge the knowledge base to retrieve from
     * @throws IllegalArgumentException if knowledgeBase is null
     */
    public KnowledgeRetrievalTools(Knowledge knowledge) {
        if (knowledge == null) {
            throw new IllegalArgumentException("Knowledge base cannot be null");
        }
        this.knowledge = knowledge;
    }

    /**
     * Retrieves relevant documents from the knowledge base.
     *
     * <p>This tool method allows agents to search the knowledge base for information
     * relevant to a query. The agent can specify how many documents to retrieve.
     *
     * <p>If the agent has conversation history in memory, that history will be automatically
     * included in the retrieval configuration. Knowledge bases that support multi-turn
     * conversation context (like Bailian) can use this history to improve retrieval accuracy.
     *
     * <p>Use this tool when:
     * <ul>
     *   <li>The user asks questions about stored knowledge
     *   <li>You need to find specific information from the knowledge base
     *   <li>You want to provide context-aware responses based on stored documents
     * </ul>
     *
     * @param query the search query to find relevant documents
     * @param limit the maximum number of documents to retrieve (default: 5)
     * @param agent the agent making the call (automatically injected by framework)
     * @return a formatted string containing the retrieved documents and their scores
     */
    @Tool(
            name = "retrieve_knowledge",
            description =
                    "Retrieve relevant documents from knowledge base. Use this tool when you need"
                        + " to find specific information or when user asks questions about stored"
                        + " knowledge.")
    public String retrieveKnowledge(
            @ToolParam(
                            name = "query",
                            description =
                                    "The search query to find relevant documents in the knowledge"
                                            + " base")
                    String query,
            @ToolParam(
                            name = "limit",
                            description = "Maximum number of documents to retrieve (default: 5)",
                            required = false)
                    Integer limit,
            Agent agent) {

        // Set default value
        if (limit == null) {
            limit = 5;
        }

        // Extract conversation history from agent if available
        List<Msg> conversationHistory = null;
        if (agent instanceof ReActAgent reActAgent) {
            conversationHistory = reActAgent.getMemory().getMessages();
        }

        // Build retrieval config with conversation history
        RetrieveConfig config =
                RetrieveConfig.builder()
                        .limit(limit)
                        .scoreThreshold(0.5)
                        .conversationHistory(conversationHistory)
                        .build();

        return knowledge
                .retrieve(query, config)
                .map(this::formatDocumentsForTool)
                .onErrorReturn("Failed to retrieve knowledge for query: " + query)
                .block(); // Convert to synchronous call to match Tool interface
    }

    /**
     * Formats retrieved documents for tool return format.
     *
     * <p>Converts the list of documents into a human-readable string format that
     * can be used by the agent in its reasoning process.
     *
     * @param documents the list of retrieved documents
     * @return a formatted string representation
     */
    private String formatDocumentsForTool(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "No relevant documents found in the knowledge base.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Retrieved ").append(documents.size()).append(" relevant document(s):\n\n");

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            sb.append("Document ").append(i + 1);
            if (doc.getScore() != null) {
                sb.append(" (Score: ").append(String.format("%.3f", doc.getScore())).append(")");
            }
            sb.append(":\n");
            sb.append(doc.getMetadata().getContentText()).append("\n\n");
        }

        return sb.toString();
    }

    /**
     * Gets the knowledge base used by this tool.
     *
     * @return the knowledge base
     */
    public Knowledge getKnowledgeBase() {
        return knowledge;
    }
}
