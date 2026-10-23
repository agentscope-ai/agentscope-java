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
package io.agentscope.spring.boot.admin.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AgentScope admin/ops starter.
 *
 * <p>The starter is opt-in. Set {@code agentscope.admin.enabled=true} to activate. Write operations
 * are additionally gated on {@code agentscope.admin.write-enabled} to keep production deployments
 * safe by default.
 */
@ConfigurationProperties(prefix = "agentscope.admin")
public class AdminProperties {

    /** Master switch. When false, no admin beans, controllers, or endpoints are registered. */
    private boolean enabled = false;

    /**
     * Whether write operations (compact, abort, reset, fork, drain, shutdown, ...) are accepted.
     * Read-only inspection endpoints work even when this is false.
     */
    private boolean writeEnabled = false;

    /** Base path for the data-plane REST controllers. Trailing slashes are trimmed. */
    private String basePath = "/v1/admin";

    /**
     * Optional shared-secret token required as the {@code X-Agentscope-Admin-Token} header on every
     * write request. When blank, no token check is performed (rely on the surrounding security
     * layer). Strongly recommended in production.
     */
    private String writeToken = "";

    /** Maximum number of messages to keep verbatim when compacting a session. */
    private int compactKeepLastMessages = 2;

    /** Whether to broadcast {@code AdminAuditEvent} to the Spring {@code ApplicationEventPublisher}. */
    private boolean publishAuditEvents = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public String getBasePath() {
        String trimmed = basePath == null ? "/v1/admin" : basePath.trim();
        if (trimmed.isEmpty()) {
            return "/v1/admin";
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getWriteToken() {
        return writeToken == null ? "" : writeToken;
    }

    public void setWriteToken(String writeToken) {
        this.writeToken = writeToken;
    }

    public int getCompactKeepLastMessages() {
        return Math.max(0, compactKeepLastMessages);
    }

    public void setCompactKeepLastMessages(int compactKeepLastMessages) {
        this.compactKeepLastMessages = compactKeepLastMessages;
    }

    public boolean isPublishAuditEvents() {
        return publishAuditEvents;
    }

    public void setPublishAuditEvents(boolean publishAuditEvents) {
        this.publishAuditEvents = publishAuditEvents;
    }
}
