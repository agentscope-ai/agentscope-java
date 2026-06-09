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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a message in the Bailian Memory API format.
 *
 * <p>Messages are used for both adding memories and searching for relevant memories.
 * Each message has a role (user or assistant) and content.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BailianMessage {

    /** Role of the message sender, "user" or "assistant". */
    private String role;

    /** The actual text content of the message. */
    private String content;

    /** Default constructor for Jackson deserialization. */
    public BailianMessage() {}

    /**
     * Creates a new BailianMessage with specified role and content.
     *
     * @param role the role (e.g., "user", "assistant")
     * @param content the message content
     */
    public BailianMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * Gets the role.
     *
     * @return the role
     */
    public String getRole() {
        return role;
    }

    /**
     * Sets the role.
     *
     * @param role the role
     */
    public void setRole(String role) {
        this.role = role;
    }

    /**
     * Gets the content.
     *
     * @return the content
     */
    public String getContent() {
        return content;
    }

    /**
     * Sets the content.
     *
     * @param content the content
     */
    public void setContent(String content) {
        this.content = content;
    }

    /**
     * Creates a new builder for BailianMessage.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for BailianMessage.
     */
    public static class Builder {
        private String role;
        private String content;

        /**
         * Sets the role.
         *
         * @param role the role
         * @return this builder
         */
        public Builder role(String role) {
            this.role = role;
            return this;
        }

        /**
         * Sets the content.
         *
         * @param content the content
         * @return this builder
         */
        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /**
         * Builds the BailianMessage instance.
         *
         * @return a new BailianMessage instance
         */
        public BailianMessage build() {
            return new BailianMessage(role, content);
        }
    }
}
