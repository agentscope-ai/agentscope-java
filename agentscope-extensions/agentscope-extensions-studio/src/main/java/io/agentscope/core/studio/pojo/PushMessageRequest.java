/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.studio.pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.Msg;

/**
 * Request payload for pushing a message to Studio.
 *
 * <p>This is sent via POST /trpc/pushMessage to display messages in the Studio UI.
 */
public class PushMessageRequest {
    @JsonProperty("runId")
    private final String runId;

    @JsonProperty("replyId")
    private final String replyId;

    @JsonProperty("name")
    private final String name;

    @JsonProperty("role")
    private final String role;

    @JsonProperty("msg")
    private final Msg msg;

    private PushMessageRequest(Builder builder) {
        this.runId = builder.runId;
        this.replyId = builder.replyId;
        this.name = builder.name;
        this.role = builder.role;
        this.msg = builder.msg;
    }

    /**
     * Gets the run ID for this message.
     *
     * @return the run ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * Gets the reply ID for this message.
     *
     * @return the reply ID, or null if this is not a reply
     */
    public String getReplyId() {
        return replyId;
    }

    /**
     * Gets the agent name that produced this message.
     *
     * @return the agent name
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the role of the message sender.
     *
     * @return the role (e.g., "assistant", "user")
     */
    public String getRole() {
        return role;
    }

    /**
     * Gets the message content.
     *
     * @return the message object
     */
    public Msg getMsg() {
        return msg;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runId;
        private String replyId;
        private String name;
        private String role;
        private Msg msg;

        public Builder runId(String runId) {
            this.runId = runId;
            return this;
        }

        public Builder replyId(String replyId) {
            this.replyId = replyId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder msg(Msg msg) {
            this.msg = msg;
            return this;
        }

        public PushMessageRequest build() {
            return new PushMessageRequest(this);
        }
    }
}
