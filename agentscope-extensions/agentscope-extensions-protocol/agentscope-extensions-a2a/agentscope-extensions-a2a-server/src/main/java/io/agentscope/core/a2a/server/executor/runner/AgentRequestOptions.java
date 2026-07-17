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

package io.agentscope.core.a2a.server.executor.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The options for each agent requests, such as `taskId`, `sessionId`,
 * `userId`, `agentId`, or metadata of requests.
 */
public class AgentRequestOptions {

    /**
     * Task id, most likely a UUID.
     */
    private String taskId;

    /**
     * AgentStateStore id, if null or empty string means not found session id from request.
     */
    private String sessionId;

    /**
     * User id, if null or empty string means not found user id from request.
     */
    private String userId;

    /**
     * Business agent id, if null or empty string means not found agent id from request.
     */
    private String agentId;

    private Map<String, String> headers = Collections.emptyMap();

    /**
     * Metadata merged from A2A request context and message. Message metadata wins on duplicated keys.
     */
    private Map<String, Object> metadata = Collections.emptyMap();

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            this.headers = Collections.emptyMap();
            return;
        }
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            this.metadata = Collections.emptyMap();
            return;
        }
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
