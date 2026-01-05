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
package io.agentscope.core.memory.mem0;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Long-term memory implementation using Mem0 as the backend.
 *
 * <p>This implementation integrates with Mem0, a memory layer for AI applications that
 * provides persistent, searchable memory storage using vector embeddings and LLM-powered
 * memory extraction.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>Semantic memory search using vector embeddings
 *   <li>LLM-powered memory extraction and inference
 *   <li>Multi-tenant memory isolation (agent, user, run)
 *   <li>Automatic fallback mechanisms to ensure reliable memory storage
 *   <li>Reactive, non-blocking operations
 * </ul>
 *
 * <p><b>Memory Isolation:</b>
 * Memories are organized using three levels of metadata:
 * <ul>
 *   <li><b>agentId:</b> Identifies the agent (optional)</li>
 *   <li><b>userId:</b> Identifies the user/workspace (optional)</li>
 *   <li><b>runId:</b> Identifies the session/run (optional)</li>
 * </ul>
 * At least one identifier is required. During retrieval, only memories with matching
 * metadata are returned.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create memory instance with authentication
 * Mem0LongTermMemory memory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiKey(System.getenv("MEM0_API_KEY"))
 *     .build();
 *
 * // For local deployments without authentication
 * Mem0LongTermMemory localMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .build();
 *
 * // For self-hosted Mem0
 * Mem0LongTermMemory selfHostedMemory = Mem0LongTermMemory.builder()
 *     .agentName("Assistant")
 *     .userName("user_123")
 *     .apiBaseUrl("http://localhost:8000")
 *     .apiType(Mem0ApiType.SELF_HOSTED)  // Specify self-hosted API type
 *     .build();
 *
 * // Record a message
 * Msg msg = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("I prefer homestays when traveling")
 *     .build();
 *
 * memory.record(List.of(msg)).block();
 *
 * // Retrieve relevant memories
 * Msg query = Msg.builder()
 *     .role(MsgRole.USER)
 *     .content("What are my travel preferences?")
 *     .build();
 *
 * String memories = memory.retrieve(query).block();
 * // Result: "User prefers homestays when traveling"
 * }</pre>
 *
 * @see LongTermMemory
 * @see Mem0Client
 */
public class Mem0LongTermMemory implements LongTermMemory {

    private final Mem0Client client;
    private final String agentId;
    private final String userId;
    private final String runId;

    /**
     * Private constructor - use Builder instead.
     */
    private Mem0LongTermMemory(Builder builder) {
        Mem0ApiType apiType = builder.apiType != null ? builder.apiType : Mem0ApiType.PLATFORM;
        this.client = new Mem0Client(builder.apiBaseUrl, builder.apiKey, apiType, builder.timeout);
        this.agentId = builder.agentName;
        this.userId = builder.userId;
        this.runId = builder.runName;

        // Validate that at least one identifier is provided
        if (agentId == null && userId == null && runId == null) {
            throw new IllegalArgumentException(
                    "At least one of agentName, userName, or runName must be provided");
        }
    }

    /**
     * Records messages to long-term memory.
     *
     * <p>This method converts each message to a Mem0Message object, preserving the
     * conversation structure (role and content). The messages are sent to Mem0 API
     * which uses LLM inference to extract memorable information.
     *
     * <p>Null messages and messages with empty text content are filtered out before
     * processing. Empty message lists are handled gracefully without error.
     *
     * @param msgs List of messages to record
     * @return A Mono that completes when recording is finished
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        // Convert Msg list to Mem0Message list
        List<Mem0Message> mem0Messages =
                msgs.stream()
                        .filter(Objects::nonNull)
                        .filter(
                                msg ->
                                        msg.getTextContent() != null
                                                && !msg.getTextContent().isEmpty()
                                                && !msg.getTextContent()
                                                        .contains("<compressed_history>"))
                        .map(this::convertToMem0Message)
                        .collect(Collectors.toList());

        if (mem0Messages.isEmpty()) {
            return Mono.empty();
        }

        // Send messages to Mem0
        Mem0AddRequest request =
                Mem0AddRequest.builder()
                        .messages(mem0Messages)
                        .userId(userId)
                        .runId(runId)
                        .infer(true)
                        .build();

        return client.add(request).then();
    }

    /**
     * Converts a Msg to a Mem0Message.
     *
     * <p>Role mapping:
     * <ul>
     *   <li>USER -> "user"</li>
     *   <li>ASSISTANT -> "assistant"</li>
     *   <li>SYSTEM -> "user" (system messages as user context)</li>
     *   <li>TOOL -> "assistant" (tool results as assistant context)</li>
     * </ul>
     */
    private Mem0Message convertToMem0Message(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER, SYSTEM -> "user";
                    case ASSISTANT, TOOL -> "assistant";
                };

        return Mem0Message.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * Builds a search request with the given query.
     *
     * @param query The search query string
     * @return A configured Mem0SearchRequest for v2 API
     */
    private Mem0SearchRequest buildSearchRequest(String query) {
        return Mem0SearchRequest.builder().query(query).userId(userId).runId(runId).topK(5).build();
    }

    /**
     * Retrieves relevant memories based on the input message.
     *
     * <p>Uses semantic search to find memories relevant to the message content.
     * Returns memory text as a newline-separated string, or empty string if no
     * relevant memories are found.
     *
     * <p>Only memories with matching metadata (agentId, userId, runId) are returned.
     *
     * @param msg The message to use as a search query
     * @return A Mono emitting the retrieved memory text (may be empty)
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }

        String query = msg.getTextContent();
        if (query == null || query.isEmpty()) {
            return Mono.just("");
        }

        return client.search(buildSearchRequest(query))
                .map(
                        response -> {
                            if (response.getResults() == null || response.getResults().isEmpty()) {
                                return "";
                            }

                            return response.getResults().stream()
                                    .map(Mem0SearchResult::getMemory)
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining("\n"));
                        })
                .onErrorReturn("");
    }

    /**
     * Creates a new builder for Mem0LongTermMemory.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for Mem0LongTermMemory.
     */
    public static class Builder {
        private String agentName;
        private String userId;
        private String runName;
        private String apiBaseUrl;
        private String apiKey;
        private Mem0ApiType apiType;
        private java.time.Duration timeout = java.time.Duration.ofSeconds(60);

        /**
         * Sets the agent name identifier.
         *
         * @param agentName The agent's name
         * @return This builder
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Sets the user id identifier.
         *
         * @param userId The user's ID
         * @return This builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the run name identifier.
         *
         * @param runName The run/session name or ID
         * @return This builder
         */
        public Builder runName(String runName) {
            this.runName = runName;
            return this;
        }

        /**
         * Sets the Mem0 API base URL.
         *
         * @param apiBaseUrl The base URL (e.g., "http://localhost:8000")
         * @return This builder
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the Mem0 API key.
         *
         * @param apiKey The API key for authentication (optional for local deployments without
         *     authentication)
         * @return This builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Sets the HTTP request timeout.
         *
         * @param timeout The timeout duration
         * @return This builder
         */
        public Builder timeout(java.time.Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Sets the Mem0 API type.
         *
         * @param apiType API type enum
         * @return This builder
         */
        public Builder apiType(Mem0ApiType apiType) {
            this.apiType = apiType;
            return this;
        }

        /**
         * Builds the Mem0LongTermMemory instance.
         *
         * @return A new Mem0LongTermMemory instance
         * @throws IllegalArgumentException If required fields are missing
         */
        public Mem0LongTermMemory build() {
            if (apiBaseUrl == null || apiBaseUrl.isEmpty()) {
                throw new IllegalArgumentException("apiBaseUrl is required");
            }

            return new Mem0LongTermMemory(this);
        }
    }
}
