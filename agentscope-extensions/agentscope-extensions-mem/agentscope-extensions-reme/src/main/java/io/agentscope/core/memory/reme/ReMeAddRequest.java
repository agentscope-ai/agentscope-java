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
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Request object for recording messages through ReMe's {@code auto_memory} job.
 *
 * <p>ReMe 0.4.x accepts a flat message list plus a session identifier instead of the
 * legacy personal-memory trajectory payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReMeAddRequest {

    /** Messages to append into the ReMe session. */
    private List<ReMeMessage> messages;

    /** ReMe session identifier. */
    @JsonProperty("session_id")
    private String sessionId;

    /** Optional hint forwarded to ReMe's memory evolution step. */
    @JsonProperty("memory_hint")
    private String memoryHint;

    /** Optional job metadata. */
    private Map<String, Object> metadata;

    /** Default constructor for Jackson. */
    public ReMeAddRequest() {}

    /**
     * Creates a new ReMeAddRequest.
     *
     * @param messages Messages to record
     * @param sessionId ReMe session ID
     * @param memoryHint Optional memory hint
     * @param metadata Optional metadata
     */
    public ReMeAddRequest(
            List<ReMeMessage> messages,
            String sessionId,
            String memoryHint,
            Map<String, Object> metadata) {
        this.messages = messages;
        this.sessionId = sessionId;
        this.memoryHint = memoryHint;
        this.metadata = metadata;
    }

    public List<ReMeMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ReMeMessage> messages) {
        this.messages = messages;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMemoryHint() {
        return memoryHint;
    }

    public void setMemoryHint(String memoryHint) {
        this.memoryHint = memoryHint;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    /**
     * Legacy accessor kept for source compatibility.
     *
     * @deprecated ReMe 0.4.x uses {@code session_id}; use {@link #getSessionId()} instead.
     */
    @Deprecated
    public String getWorkspaceId() {
        return sessionId;
    }

    /**
     * Legacy mutator kept for source compatibility.
     *
     * @deprecated ReMe 0.4.x uses {@code session_id}; use {@link #setSessionId(String)} instead.
     */
    @Deprecated
    public void setWorkspaceId(String workspaceId) {
        this.sessionId = workspaceId;
    }

    /**
     * Creates a new builder for ReMeAddRequest.
     *
     * @return A new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for ReMeAddRequest. */
    public static class Builder {
        private List<ReMeMessage> messages;
        private String sessionId;
        private String memoryHint;
        private Map<String, Object> metadata;

        public Builder messages(List<ReMeMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder memoryHint(String memoryHint) {
            this.memoryHint = memoryHint;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Legacy builder alias kept for source compatibility.
         *
         * @deprecated ReMe 0.4.x uses {@code session_id}; use {@link #sessionId(String)}.
         */
        @Deprecated
        public Builder workspaceId(String workspaceId) {
            this.sessionId = workspaceId;
            return this;
        }

        public ReMeAddRequest build() {
            return new ReMeAddRequest(messages, sessionId, memoryHint, metadata);
        }
    }
}
