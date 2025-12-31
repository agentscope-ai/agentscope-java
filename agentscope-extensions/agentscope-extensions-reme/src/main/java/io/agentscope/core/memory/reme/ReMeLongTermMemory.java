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
package io.agentscope.core.memory.reme;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Long-term memory implementation using ReMe as the backend.
 *
 * <p>This implementation integrates with ReMe, a memory layer for AI applications that
 * provides persistent, searchable memory storage using LLM-powered memory extraction.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>LLM-powered memory extraction and inference
 *   <li>Workspace-based memory isolation
 *   <li>Automatic memory summarization from conversation trajectories
 *   <li>Reactive, non-blocking operations
 * </ul>
 *
 * <p><b>Memory Isolation:</b>
 * Memories are organized by userId (mapped to ReMe's workspace_id), enabling
 * multi-tenant scenarios where different users maintain separate memory contexts.
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create memory instance
 * ReMeLongTermMemory memory = ReMeLongTermMemory.builder()
 *     .userId("task_workspace")
 *     .apiBaseUrl("http://localhost:8002")
 *     .build();
 *
 * // Use in ReActAgent
 * ReActAgent agent = ReActAgent.builder()
 *     .name("Assistant")
 *     .model(model)
 *     .longTermMemory(memory)
 *     .longTermMemoryMode(LongTermMemoryMode.BOTH)
 *     .build();
 * }</pre>
 *
 * @see LongTermMemory
 * @see ReMeClient
 */
public class ReMeLongTermMemory implements LongTermMemory {

    private final ReMeClient client;
    private final String userId;

    /**
     * Private constructor - use Builder instead.
     */
    private ReMeLongTermMemory(Builder builder) {
        // Validate required fields before creating ReMeClient
        if (builder.userId == null || builder.userId.isEmpty()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (builder.apiBaseUrl == null || builder.apiBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("apiBaseUrl is required");
        }

        this.client = new ReMeClient(builder.apiBaseUrl, builder.timeout);
        this.userId = builder.userId;
    }

    /**
     * Records messages to long-term memory.
     *
     * <p>This method converts messages to ReMe trajectory format and sends them to
     * the ReMe API for processing. ReMe will extract and store memorable information
     * from the conversation.
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
     * @param msgs List of messages to record
     * @return A Mono that completes when recording is finished
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        // Convert Msg list to ReMeMessage list
        // Only keep USER and ASSISTANT messages (excluding ASSISTANT messages with ToolUseBlock)
        List<ReMeMessage> remeMessages =
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

                                    // Check for non-empty text content
                                    String textContent = msg.getTextContent();
                                    if (textContent == null || textContent.isEmpty()) {
                                        return false;
                                    }

                                    // Exclude messages with compressed history
                                    if (textContent.contains("<compressed_history>")) {
                                        return false;
                                    }

                                    return true;
                                })
                        .map(this::convertToReMeMessage)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        if (remeMessages.isEmpty()) {
            return Mono.empty();
        }

        // Create trajectory with messages
        ReMeTrajectory trajectory = ReMeTrajectory.builder().messages(remeMessages).build();

        // Build request
        // Note: userId is used as workspaceId for ReMe API
        ReMeAddRequest request =
                ReMeAddRequest.builder()
                        .workspaceId(userId)
                        .trajectories(List.of(trajectory))
                        .build();

        // Send to ReMe API
        return client.add(request).then();
    }

    /**
     * Converts a Msg to a ReMeMessage.
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
    private ReMeMessage convertToReMeMessage(Msg msg) {
        String role =
                switch (msg.getRole()) {
                    case USER -> "user";
                    case ASSISTANT -> "assistant";
                    default -> null; // Filter out unsupported message types
                };

        if (role == null) {
            return null;
        }

        return ReMeMessage.builder().role(role).content(msg.getTextContent()).build();
    }

    /**
     * Retrieves relevant memories based on the input message.
     *
     * <p>Uses the message content as a query to search for relevant memories in ReMe.
     * Returns memory fragments as a newline-separated string, or empty string if no
     * relevant memories are found.
     *
     * <p>Only memories from the configured workspace are returned.
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

        // Build search request
        // Note: userId is used as workspaceId for ReMe API
        ReMeSearchRequest request =
                ReMeSearchRequest.builder().workspaceId(userId).query(query).topK(5).build();

        // Search and convert response to string
        return client.search(request)
                .map(
                        response -> {
                            // Use answer field if available, otherwise use memory_list
                            if (response.getAnswer() != null && !response.getAnswer().isEmpty()) {
                                return response.getAnswer();
                            }

                            List<String> memories = response.getMemories();
                            if (memories == null || memories.isEmpty()) {
                                return "";
                            }

                            return memories.stream()
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.joining("\n"));
                        })
                .onErrorReturn("");
    }

    /**
     * Creates a new builder for ReMeLongTermMemory.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ReMeLongTermMemory.
     */
    public static class Builder {
        private String userId;
        private String apiBaseUrl;
        private Duration timeout = Duration.ofSeconds(60);

        /**
         * Sets the userId.
         *
         * @param userId The userId
         * @return This builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Sets the ReMe API base URL.
         *
         * @param apiBaseUrl The base URL (e.g., "http://localhost:8002")
         * @return This builder
         */
        public Builder apiBaseUrl(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            return this;
        }

        /**
         * Sets the HTTP request timeout.
         *
         * @param timeout The timeout duration
         * @return This builder
         */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        /**
         * Builds the ReMeLongTermMemory instance.
         *
         * @return A new ReMeLongTermMemory instance
         * @throws IllegalArgumentException If required fields are missing
         */
        public ReMeLongTermMemory build() {
            return new ReMeLongTermMemory(this);
        }
    }
}
