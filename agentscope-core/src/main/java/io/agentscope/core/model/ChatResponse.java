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
package io.agentscope.core.model;

import io.agentscope.core.message.ContentBlock;
import java.util.List;
import java.util.Map;

/**
 * Represents a chat completion response from a language model.
 *
 * <p>This immutable data class contains the response content, usage information,
 * and optional metadata returned by the model after processing a chat request.
 */
public class ChatResponse {

    private final String id;
    private final List<ContentBlock> content;
    private final ChatUsage usage;
    private final Map<String, Object> metadata;
    private final String finishReason;

    /**
     * Creates a new ChatResponse instance.
     *
     * @param id the unique identifier for this response
     * @param content the list of content blocks containing the response content
     * @param usage the token usage information for this response
     * @param metadata additional metadata from the model provider
     * @param finishReason the reason for
     */
    public ChatResponse(
            String id,
            List<ContentBlock> content,
            ChatUsage usage,
            Map<String, Object> metadata,
            String finishReason) {
        this.id = id;
        this.content = content;
        this.usage = usage;
        this.metadata = metadata;
        this.finishReason = finishReason;
    }

    /**
     * Gets the unique identifier for this response.
     *
     * @return the response identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the content blocks containing the response content.
     *
     * @return the list of content blocks
     */
    public List<ContentBlock> getContent() {
        return content;
    }

    /**
     * Gets the token usage information for this response.
     *
     * @return the usage information
     */
    public ChatUsage getUsage() {
        return usage;
    }

    /**
     * Gets the metadata from the model provider.
     *
     * @return the metadata map
     */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Gets the reason the model stopped generating tokens.
     *
     * @return the finish reason
     * */
    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Creates a new builder for ChatResponse.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating ChatResponse instances.
     */
    public static class Builder {
        private String id;
        private List<ContentBlock> content;
        private ChatUsage usage;
        private Map<String, Object> metadata;
        private String finishReason;

        /**
         * Sets the response identifier.
         *
         * @param id the unique identifier for this response
         * @return this builder instance
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the content blocks for the response.
         *
         * @param content the list of content blocks containing the response content
         * @return this builder instance
         */
        public Builder content(List<ContentBlock> content) {
            this.content = content;
            return this;
        }

        /**
         * Sets the usage information for the response.
         *
         * @param usage the token usage information
         * @return this builder instance
         */
        public Builder usage(ChatUsage usage) {
            this.usage = usage;
            return this;
        }

        /**
         * Sets the metadata for the response.
         *
         * @param metadata additional metadata from the model provider
         * @return this builder instance
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Sets the finish reason for the response.
         *
         * @param finishReason the finish reason sent by the model provider
         * @return this builder instance
         */
        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /**
         * Builds a new ChatResponse instance with the set values.
         *
         * @return a new ChatResponse instance
         */
        public ChatResponse build() {
            return new ChatResponse(id, content, usage, metadata, finishReason);
        }
    }
}
