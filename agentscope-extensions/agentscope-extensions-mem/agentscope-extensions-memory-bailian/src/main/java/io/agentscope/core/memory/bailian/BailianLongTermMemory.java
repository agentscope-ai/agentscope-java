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
package io.agentscope.core.memory.bailian;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Long-term memory implementation using Bailian Long Term Memory service.
 *
 * <p>This implementation integrates with Alibaba Cloud Bailian's memory service,
 * which provides persistent, searchable memory storage using semantic similarity
 * and LLM-powered memory extraction.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Semantic memory search using vector embeddings</li>
 *   <li>LLM-powered memory extraction and inference</li>
 *   <li>Multi-tenant memory isolation (user_id, memory_library_id)</li>
 *   <li>Reactive, non-blocking operations</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Using try-with-resources (recommended)
 * try (BailianLongTermMemory memory = BailianLongTermMemory.builder()
 *         .apiKey(System.getenv("DASHSCOPE_API_KEY"))
 *         .userId("user_001")
 *         .memoryLibraryId("memory_library_123")
 *         .projectId(project_id_123)
 *         .profileSchema(profile_schema_123)
 *         .build()) {
 *
 *     // Record messages
 *     Msg msg = Msg.builder()
 *         .role(MsgRole.USER)
 *         .content("Remind me to drink water at 9 a.m. every day")
 *         .build();
 *     memory.record(List.of(msg)).block();
 *
 *     // Retrieve memories
 *     Msg query = Msg.builder()
 *         .role(MsgRole.USER)
 *         .content("What reminder do I have?")
 *         .build();
 *     String memories = memory.retrieve(query).block();
 * }
 * }</pre>
 *
 * @see LongTermMemory
 * @see BailianMemoryClient
 */
public class BailianLongTermMemory implements LongTermMemory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BailianLongTermMemory.class);
    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com";
    private static final Integer DEFAULT_TOP_K = 10;
    private static final Double DEFAULT_MIN_SCORE = 0.3;

    private final BailianMemoryClient client;
    private final String userId;
    private final String memoryLibraryId;
    private final String projectId;
    private final String profileSchema;
    private final Integer topK;
    private final Double minScore;
    private final Boolean enableRerank;
    private final Boolean enableJudge;
    private final Boolean enableRewrite;
    private final Map<String, Object> metadata;

    /**
     * Creates a new {@link BailianLongTermMemory} instance.
     *
     * @param builder the builder for configuring the memory
     */
    private BailianLongTermMemory(Builder builder) {
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey cannot be null or blank");
        }
        if (builder.userId == null || builder.userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        this.userId = builder.userId;
        this.memoryLibraryId = builder.memoryLibraryId;
        this.projectId = builder.projectId;
        this.profileSchema = builder.profileSchema;
        this.topK = builder.topK;
        this.minScore = builder.minScore;
        this.enableRerank = builder.enableRerank;
        this.enableJudge = builder.enableJudge;
        this.enableRewrite = builder.enableRewrite;
        this.metadata = builder.metadata;
        this.client =
                BailianMemoryClient.builder()
                        .apiBaseUrl(builder.apiBaseUrl)
                        .apiKey(builder.apiKey)
                        .httpTransport(builder.httpTransport)
                        .build();
    }

    /**
     * Records messages to long-term memory.
     *
     * <p>This method converts each message to a BailianMessage object, preserving the
     * conversation structure (role and content). The messages are sent to Bailian API
     * which uses LLM inference to extract memorable information.
     *
     * <p>Only USER and ASSISTANT messages are recorded. For ASSISTANT messages,
     * only those without ToolUseBlock (pure assistant replies) are kept, filtering
     * out tool call requests. TOOL and SYSTEM messages are also filtered out to keep
     * the conversation history clean and focused on user-assistant interactions.
     *
     * <p>Messages containing compressed history markers (&lt;compressed_history&gt;) are
     * filtered out to avoid storing redundant compressed information.
     *
     * <p>Null messages and messages with empty text content are filtered out before
     * processing. Empty message lists are handled gracefully without error.
     *
     * @param msgs list of messages to record
     * @return a Mono that completes when recording is finished
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        List<BailianMessage> bailianMessages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg -> {
                                    // Filter by role: only USER and ASSISTANT
                                    MsgRole role = msg.getRole();
                                    if (role != MsgRole.USER && role != MsgRole.ASSISTANT) {
                                        return false;
                                    }

                                    // For ASSISTANT messages, exclude those with ToolUseBlock
                                    // (tool call requests should not be recorded)
                                    if (role == MsgRole.ASSISTANT
                                            && msg.hasContentBlocks(ToolUseBlock.class)) {
                                        return false;
                                    }

                                    // Check for non-blank text content
                                    String textContent = msg.getTextContent();
                                    if (textContent == null || textContent.isBlank()) {
                                        return false;
                                    }

                                    // Exclude messages with compressed history
                                    if (textContent.contains("<compressed_history>")) {
                                        return false;
                                    }

                                    return true;
                                })
                        .map(this::convertToBailianMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        if (bailianMessages.isEmpty()) {
            return Mono.empty();
        }

        BailianAddRequest.Builder addRequestBuilder =
                BailianAddRequest.builder().userId(userId).messages(bailianMessages);

        if (memoryLibraryId != null && !memoryLibraryId.isBlank()) {
            addRequestBuilder.memoryLibraryId(memoryLibraryId);
        }

        if (projectId != null && !projectId.isBlank()) {
            addRequestBuilder.projectId(projectId);
        }

        if (profileSchema != null && !profileSchema.isBlank()) {
            addRequestBuilder.profileSchema(profileSchema);
        }

        if (metadata != null && !metadata.isEmpty()) {
            addRequestBuilder.metadata(metadata);
        }

        return client.add(addRequestBuilder.build())
                .doOnError(e -> log.warn("Failed to record Bailian memory", e))
                .onErrorComplete()
                .then();
    }

    /**
     * Retrieves relevant memories based on the input message.
     *
     * <p>Uses semantic search to find memories relevant to the message content.
     * Returns memory text as a newline-separated string, or empty string if no
     * relevant memories are found.
     *
     * @param msg the message to use as a search query
     * @return a Mono emitting the retrieved memory text (maybe empty)
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }

        String query = msg.getTextContent();
        if (query == null || query.isBlank()) {
            return Mono.just("");
        }

        BailianMessage searchMessage = BailianMessage.builder().role("user").content(query).build();

        BailianSearchRequest.Builder searchRequestBuilder =
                BailianSearchRequest.builder()
                        .userId(userId)
                        .messages(List.of(searchMessage))
                        .topK(topK)
                        .minScore(minScore)
                        .enableRerank(enableRerank)
                        .enableJudge(enableJudge)
                        .enableRewrite(enableRewrite);

        if (memoryLibraryId != null && !memoryLibraryId.isBlank()) {
            searchRequestBuilder.memoryLibraryId(memoryLibraryId);
        }

        if (projectId != null && !projectId.isBlank()) {
            searchRequestBuilder.projectIds(List.of(projectId));
        }

        return client.search(searchRequestBuilder.build())
                .map(
                        response -> {
                            if (response.getMemoryNodes() == null
                                    || response.getMemoryNodes().isEmpty()) {
                                return "";
                            }

                            return response.getMemoryNodes().stream()
                                    .map(BailianMemoryNode::getContent)
                                    .filter(s -> s != null && !s.isBlank())
                                    .collect(Collectors.joining("\n"));
                        })
                .doOnError(e -> log.warn("Failed to retrieve Bailian memory", e))
                .onErrorReturn("");
    }

    /**
     * Converts a Msg to a BailianMessage.
     *
     * <p>Role mapping:
     * <ul>
     *   <li>USER -> "user"</li>
     *   <li>ASSISTANT -> "assistant" (only pure assistant replies without ToolUseBlock)</li>
     * </ul>
     *
     * <p>Returns null for unsupported message types (TOOL, SYSTEM, or ASSISTANT with ToolUseBlock),
     * which will be filtered out by the caller.
     */
    private BailianMessage convertToBailianMessage(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    default -> null; // Filter out unsupported message types
                };

        if (role == null) {
            return null;
        }

        return BailianMessage.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * Creates a new builder for {@link BailianLongTermMemory}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Closes the BailianMemoryClient and releases resources.
     */
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }

    /**
     * Builder for {@link BailianLongTermMemory}.
     */
    public static class Builder {
        private String apiKey;
        private String apiBaseUrl = DEFAULT_BASE_URL;
        private String userId;
        private String memoryLibraryId;
        private String projectId;
        private String profileSchema;
        private Integer topK = DEFAULT_TOP_K;
        private Double minScore = DEFAULT_MIN_SCORE;
        private Boolean enableRerank = false;
        private Boolean enableJudge = false;
        private Boolean enableRewrite = false;
        private Map<String, Object> metadata;
        private HttpTransport httpTransport = HttpTransportFactory.getDefault();

        /**
         * Sets the Bailian API key.
         *
         * @param apiKey the DASHSCOPE_API_KEY
         * @return this builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the Bailian API base URL.
         *
         * @param apiBaseUrl the base URL
         * @return this builder
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the user identifier for memory organization.
         *
         * @param userId the user ID (required)
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the memory library identifier.
         *
         * @param memoryLibraryId the memory library ID
         * @return this builder
         */
        public Builder memoryLibraryId(String memoryLibraryId) {
            this.memoryLibraryId = memoryLibraryId;
            return this;
        }

        /**
         * Sets the project identifier.
         *
         * @param projectId the project ID
         * @return this builder
         */
        public Builder projectId(String projectId) {
            this.projectId = projectId;
            return this;
        }

        /**
         * Sets the profile schema identifier.
         *
         * @param profileSchema the profile schema ID
         * @return this builder
         */
        public Builder profileSchema(String profileSchema) {
            this.profileSchema = profileSchema;
            return this;
        }

        /**
         * Sets the maximum number of search results.
         *
         * @param topK the top K value
         * @return this builder
         */
        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets the minimum similarity score threshold.
         *
         * @param minScore the minimum score threshold [0,1]
         * @return this builder
         */
        public Builder minScore(Double minScore) {
            this.minScore = minScore;
            return this;
        }

        /**
         * Sets whether to enable reranking.
         *
         * @param enableRerank true to enable reranking, false otherwise
         * @return this builder
         */
        public Builder enableRerank(Boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        /**
         * Sets whether to enable judge.
         *
         * @param enableJudge true to enable judge, false otherwise
         * @return this builder
         */
        public Builder enableJudge(Boolean enableJudge) {
            this.enableJudge = enableJudge;
            return this;
        }

        /**
         * Sets whether to enable rewrite.
         *
         * @param enableRewrite true to enable rewrite, false otherwise
         * @return this builder
         */
        public Builder enableRewrite(Boolean enableRewrite) {
            this.enableRewrite = enableRewrite;
            return this;
        }

        /**
         * Sets custom metadata to be stored with memories.
         *
         * @param metadata custom metadata map
         * @return this builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the HTTP transport.
         *
         * @param httpTransport the HTTP transport
         * @return this builder
         */
        public Builder httpTransport(HttpTransport httpTransport) {
            this.httpTransport = httpTransport;
            return this;
        }

        /**
         * Builds the {@link BailianLongTermMemory} instance.
         *
         * @return a new {@link BailianLongTermMemory} instance
         */
        public BailianLongTermMemory build() {
            return new BailianLongTermMemory(this);
        }
    }
}
