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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a message in the ReMe API format.
 *
 * <p>Messages are the primary input format for ReMe's memory recording. Each message
 * has a role (user or assistant) and content.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeMessage {

    /** Role of the message sender, typically "user" or "assistant". */
    private String role;

    /** The actual text content of the message. */
    private String content;

    /** Default constructor for Jackson deserialization. */
    public ReMeMessage() {}

    /**
     * Creates a new ReMeMessage with specified role and content.
     *
     * @param role The role (e.g., "user", "assistant")
     * @param content The message content
     */
    public ReMeMessage(String role, String content) {
        this.role = role;
        this.content = content;
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

    /**
     * Builder for creating ReMeMessage instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder class for ReMeMessage. */
    public static class Builder {
        private String role;
        private String content;

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public ReMeMessage build() {
            return new ReMeMessage(role, content);
        }
    }
}
