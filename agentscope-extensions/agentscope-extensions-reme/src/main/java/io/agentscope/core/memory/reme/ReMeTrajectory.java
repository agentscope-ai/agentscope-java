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
import java.util.List;

/**
 * Represents a trajectory (conversation sequence) in the ReMe API format.
 *
 * <p>A trajectory contains a list of messages that form a conversation sequence.
 * This is used when recording memories to ReMe.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeTrajectory {

    /** List of messages in this trajectory. */
    private List<ReMeMessage> messages;

    /** Default constructor for Jackson deserialization. */
    public ReMeTrajectory() {}

    /**
     * Creates a new ReMeTrajectory with specified messages.
     *
     * @param messages The list of messages
     */
    public ReMeTrajectory(List<ReMeMessage> messages) {
        this.messages = messages;
    }

    // Getters and Setters

    public List<ReMeMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ReMeMessage> messages) {
        this.messages = messages;
    }

    /**
     * Builder for creating ReMeTrajectory instances.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder class for ReMeTrajectory. */
    public static class Builder {
        private List<ReMeMessage> messages;

        public Builder messages(List<ReMeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public ReMeTrajectory build() {
            return new ReMeTrajectory(messages);
        }
    }
}
