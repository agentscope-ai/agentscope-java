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
package io.agentscope.examples.hitlchat.dto;

import java.util.List;

/**
 * Tool confirmation request DTO.
 */
public class ToolConfirmRequest {

    private String sessionId;
    private boolean confirmed;
    private String reason;
    private List<ToolCallInfo> toolCalls;

    public ToolConfirmRequest() {}

    public ToolConfirmRequest(
            String sessionId, boolean confirmed, String reason, List<ToolCallInfo> toolCalls) {
        this.sessionId = sessionId;
        this.confirmed = confirmed;
        this.reason = reason;
        this.toolCalls = toolCalls;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<ToolCallInfo> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallInfo> toolCalls) {
        this.toolCalls = toolCalls;
    }

    /** Tool call information for rejection response. */
    public static class ToolCallInfo {
        private String id;
        private String name;

        public ToolCallInfo() {}

        public ToolCallInfo(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
