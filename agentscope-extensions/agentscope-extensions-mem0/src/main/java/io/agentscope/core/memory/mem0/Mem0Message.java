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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a message in the Mem0 API format.
 *
 * <p>Messages are the primary input format for Mem0's memory recording. Each message
 * has a role (user or assistant), content, and optionally a name identifier.
 *
 * <p>Mem0 processes these messages to extract and infer memorable information, which
 * is then stored in the vector database for future retrieval.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Mem0Message {

    /** Role of the message sender, typically "user" or "assistant". */
    private String role;

    /** The actual text content of the message. */
    private String content;

    /** Optional name identifier for the sender. */
    private String name;

    /** Default constructor for Jackson deserialization. */
    public Mem0Message() {}

    /**
     * Creates a new Mem0Message with specified role and content.
     *
     * @param role The role (e.g., "user", "assistant")
     * @param content The message content
     */
    public Mem0Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * Creates a new Mem0Message with role, content, and name.
     *
     * @param role The role (e.g., "user", "assistant")
     * @param content The message content
     * @param name The sender's name
     */
    public Mem0Message(String role, String content, String name) {
        this.role = role;
        this.content = content;
        this.name = name;
    }

    // Getters and Setters

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * Builder for creating Mem0Message instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder class for Mem0Message. */
    public static class Builder {
        private String role;
        private String content;
        private String name;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Mem0Message build() {
            return new Mem0Message(role, content, name);
        }
    }
}
