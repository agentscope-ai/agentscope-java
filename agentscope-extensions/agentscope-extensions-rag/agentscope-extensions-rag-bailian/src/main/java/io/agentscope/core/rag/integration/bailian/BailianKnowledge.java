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
package io.agentscope.core.rag.integration.bailian;

import com.aliyun.bailian20231229.models.RetrieveResponse;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Bailian Cloud Knowledge Base implementation.
 *
 * <p>This class provides integration with Alibaba Cloud Bailian Knowledge Base service,
 * implementing the Knowledge interface to enable seamless RAG (Retrieval-Augmented Generation)
 * capabilities with cloud-hosted knowledge bases.
 *
 * <p>Key features:
 * <ul>
 *   <li>Cloud-hosted knowledge base (no local infrastructure needed)
 *   <li>Automatic document parsing and embedding by Bailian service
 *   <li>Enterprise-grade search with reranking and filtering
 *   <li>Supports structured, unstructured, and image knowledge bases
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * // 1. Configure Bailian connection
 * BailianConfig config = BailianConfig.builder()
 *     .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
 *     .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
 *     .workspaceId("llm-xxx")
 *     .indexId("mymxbdxxxx")
 *     .build();
 *
 * // 2. Create knowledge base instance
 * BailianKnowledge knowledge = BailianKnowledge.builder()
 *     .config(config)
 *     .build();
 *
 * // 3. Retrieve documents
 * RetrieveConfig retrieveConfig = RetrieveConfig.builder()
 *     .limit(5)
 *     .scoreThreshold(0.5)
 *     .build();
 *
 * List<Document> results = knowledge.retrieve("query text", retrieveConfig).block();
 *
 * // 4. Use with agents via KnowledgeRetrievalTools
 * KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
 * Toolkit toolkit = new Toolkit();
 * toolkit.registerObject(tools);
 *
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(chatModel)
 *     .toolkit(toolkit)
 *     .build();
 * }</pre>
 *
 * <p><b>Note:</b> Currently, only the retrieve operation is supported via API.
 * Document upload and management must be performed through the Bailian console
 * or will be supported in future releases.
 */
public class BailianKnowledge implements Knowledge {

    private static final Logger log = LoggerFactory.getLogger(BailianKnowledge.class);

    private final BailianClient client;
    private final BailianConfig config;
    private final String indexId;

    /**
     * Creates a new BailianKnowledge instance.
     *
     * @param client the Bailian API client
     * @param config the Bailian configuration (can be null if client was created separately)
     * @param indexId the knowledge base index ID
     */
    private BailianKnowledge(BailianClient client, BailianConfig config, String indexId) {
        if (client == null) {
            throw new IllegalArgumentException("BailianClient cannot be null");
        }
        if (indexId == null || indexId.trim().isEmpty()) {
            throw new IllegalArgumentException("IndexId cannot be null or empty");
        }

        this.client = client;
        this.config = config;
        this.indexId = indexId;

        log.info(
                "BailianKnowledge initialized for index: {} in workspace: {}",
                indexId,
                client.getWorkspaceId());
    }

    /**
     * Adds documents to the knowledge base.
     *
     * <p><b>Note:</b> This operation is not yet supported via API. Please use the
     * Bailian console to upload documents to your knowledge base, or wait for future
     * API support.
     *
     * @param documents the list of documents to add
     * @return a Mono that completes with an error indicating the operation is not supported
     */
    @Override
    public Mono<Void> addDocuments(List<Document> documents) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Document upload via API is not yet supported. "
                                + "Please use Bailian console to manage documents, "
                                + "or this feature will be added in future releases."));
    }

    /**
     * Retrieves relevant documents based on a query.
     *
     * <p>This method searches the Bailian knowledge base for documents relevant to
     * the given query. The query is automatically embedded by Bailian service, and
     * results are filtered by score threshold and limited as specified in the config.
     *
     * <p>Bailian's retrieve API includes:
     * <ul>
     *   <li>Automatic query embedding
     *   <li>Vector similarity search
     *   <li>Optional reranking for improved relevance
     *   <li>Metadata filtering support
     * </ul>
     *
     * <p>If conversation history is provided in RetrieveConfig and query rewrite is
     * enabled in BailianConfig, the query will be rewritten based on the conversation
     * context for better retrieval.
     *
     * @param query the search query text
     * @param config the retrieval configuration (limit, score threshold, conversation history)
     * @return a Mono that emits a list of relevant Document objects, sorted by relevance
     */
    @Override
    public Mono<List<Document>> retrieve(String query, RetrieveConfig config) {
        if (query == null) {
            return Mono.error(new IllegalArgumentException("Query cannot be null"));
        }
        if (config == null) {
            return Mono.error(new IllegalArgumentException("RetrieveConfig cannot be null"));
        }
        if (query.trim().isEmpty()) {
            log.debug("Empty query received, returning empty result");
            return Mono.just(List.of());
        }

        log.debug(
                "Retrieving documents for query: '{}' (limit: {}, threshold: {}, historySize: {})",
                query,
                config.getLimit(),
                config.getScoreThreshold(),
                config.getConversationHistory() != null
                        ? config.getConversationHistory().size()
                        : 0);

        // Convert Msg conversation history to QueryHistoryEntry
        List<QueryHistoryEntry> queryHistory =
                (config.getConversationHistory() != null
                                && !config.getConversationHistory().isEmpty())
                        ? convertToQueryHistory(config.getConversationHistory())
                        : null;

        return client.retrieve(indexId, query, config.getLimit(), queryHistory)
                .map(RetrieveResponse::getBody)
                .map(BailianDocumentConverter::fromBailianResponse)
                .map(
                        documents ->
                                documents.stream()
                                        .filter(
                                                doc ->
                                                        doc.getScore() != null
                                                                && doc.getScore()
                                                                        >= config
                                                                                .getScoreThreshold())
                                        .sorted(
                                                Comparator.comparing(
                                                        Document::getScore,
                                                        Comparator.reverseOrder()))
                                        .limit(config.getLimit())
                                        .collect(Collectors.toList()))
                .doOnSuccess(
                        docs ->
                                log.debug(
                                        "Successfully retrieved {} documents (after filtering)",
                                        docs.size()))
                .doOnError(error -> log.error("Failed to retrieve documents from Bailian", error));
    }

    /**
     * Converts a list of Msg objects to QueryHistoryEntry format for Bailian API.
     *
     * <p>This method extracts USER and ASSISTANT messages from the conversation history
     * and converts them to the format expected by Bailian's multi-turn rewrite API.
     * Only text content is extracted from messages (other content types are ignored).
     *
     * @param messages the conversation history as Msg objects
     * @return a list of QueryHistoryEntry objects, or empty list if no valid messages
     */
    private List<QueryHistoryEntry> convertToQueryHistory(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        return messages.stream()
                .filter(msg -> msg.getRole() != null)
                .filter(msg -> msg.getRole() == MsgRole.USER || msg.getRole() == MsgRole.ASSISTANT)
                .map(
                        msg -> {
                            String role = msg.getRole() == MsgRole.USER ? "user" : "assistant";
                            String content = extractTextContent(msg);
                            return new QueryHistoryEntry(role, content);
                        })
                .filter(entry -> entry.getContent() != null && !entry.getContent().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Extracts text content from a Msg object.
     *
     * <p>This method concatenates all text blocks from the message's content.
     * Non-text content types (images, tool uses, etc.) are ignored.
     *
     * @param msg the message to extract text from
     * @return the concatenated text content, or empty string if no text content
     */
    private String extractTextContent(Msg msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock) {
                if (text.length() > 0) {
                    text.append("\n");
                }
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /**
     * Gets the knowledge base index ID.
     *
     * @return the index ID
     */
    public String getIndexId() {
        return indexId;
    }

    /**
     * Gets the Bailian API client.
     *
     * @return the client
     */
    public BailianClient getClient() {
        return client;
    }

    /**
     * Creates a new builder for BailianKnowledge.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for BailianKnowledge.
     */
    public static class Builder {
        private BailianConfig config;
        private BailianClient client;
        private String indexId;

        private Builder() {}

        /**
         * Sets the Bailian configuration.
         *
         * <p>If this is set, a BailianClient will be automatically created from the config.
         * You cannot set both config and client - choose one approach.
         *
         * @param config the Bailian configuration
         * @return this builder for method chaining
         */
        public Builder config(BailianConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Sets the Bailian client directly.
         *
         * <p>Use this if you want to share a client instance across multiple
         * BailianKnowledge instances. You cannot set both config and client.
         *
         * @param client the Bailian client
         * @return this builder for method chaining
         */
        public Builder client(BailianClient client) {
            this.client = client;
            return this;
        }

        /**
         * Sets the knowledge base index ID.
         *
         * <p>This overrides the indexId from config if both are provided.
         *
         * @param indexId the knowledge base index ID
         * @return this builder for method chaining
         */
        public Builder indexId(String indexId) {
            this.indexId = indexId;
            return this;
        }

        /**
         * Builds a new BailianKnowledge instance.
         *
         * @return a new BailianKnowledge instance
         * @throws IllegalArgumentException if required parameters are missing
         * @throws IllegalStateException if both config and client are set
         */
        public BailianKnowledge build() {
            // Validate that only one of config or client is set
            if (config != null && client != null) {
                throw new IllegalStateException(
                        "Cannot set both config and client. Choose one approach.");
            }

            // Create client from config if needed
            BailianClient finalClient = client;
            if (finalClient == null) {
                if (config == null) {
                    throw new IllegalArgumentException("Either config or client must be provided");
                }
                try {
                    finalClient = new BailianClient(config);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to create BailianClient from config", e);
                }
            }

            // Determine final indexId
            String finalIndexId = indexId;
            if (finalIndexId == null && config != null) {
                finalIndexId = config.getIndexId();
            }

            if (finalIndexId == null || finalIndexId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "IndexId must be provided either via config or builder");
            }

            return new BailianKnowledge(finalClient, config, finalIndexId);
        }
    }
}
