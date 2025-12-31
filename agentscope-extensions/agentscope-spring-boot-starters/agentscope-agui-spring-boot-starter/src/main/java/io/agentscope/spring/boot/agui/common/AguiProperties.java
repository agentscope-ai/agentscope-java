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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agui.model.ToolMergeMode;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for AG-UI integration.
 *
 * <p>These properties can be configured in application.yml or application.properties:
 *
 * <pre>
 * agentscope:
 *   agui:
 *     path-prefix: /agui
 *     cors-enabled: true
 *     cors-allowed-origins:
 *       - "*"
 *     run-timeout: 10m
 *     default-tool-merge-mode: MERGE_FRONTEND_PRIORITY
 *     default-agent-id: default
 *     agent-id-header: X-Agent-Id
 *     enable-path-routing: true
 * </pre>
 */
@ConfigurationProperties(prefix = "agentscope.agui")
public class AguiProperties {

    /** Path prefix for AG-UI endpoints. */
    private String pathPrefix = "/agui";

    /** Whether CORS is enabled. */
    private boolean corsEnabled = true;

    /** Allowed origins for CORS. */
    private List<String> corsAllowedOrigins = List.of("*");

    /** Timeout for agent runs. */
    private Duration runTimeout = Duration.ofMinutes(10);

    /** Default tool merge mode. */
    private ToolMergeMode defaultToolMergeMode = ToolMergeMode.MERGE_FRONTEND_PRIORITY;

    /** Whether to emit state events. */
    private boolean emitStateEvents = true;

    /** Whether to emit tool call argument events. */
    private boolean emitToolCallArgs = true;

    /** Default agent ID to use when not specified in the request. */
    private String defaultAgentId = "default";

    /**
     * Whether to manage conversation memory on the backend by threadId. When enabled, the backend
     * maintains agent instances per threadId, preserving conversation history across requests.
     */
    private boolean serverSideMemory = false;

    /**
     * Maximum number of thread sessions to keep in memory. Only used when serverSideMemory is
     * enabled.
     */
    private int maxThreadSessions = 1000;

    /**
     * Session timeout in minutes. Sessions inactive for longer than this will be removed. Set to 0
     * for no timeout. Only used when serverSideMemory is enabled.
     */
    private int sessionTimeoutMinutes = 30;

    /**
     * HTTP header name to read agent ID from. The agent ID can be passed via this header when
     * making requests.
     */
    private String agentIdHeader = "X-Agent-Id";

    /**
     * Whether to enable path variable routing for agent ID. When enabled, requests can be made to
     * /agui/run/{agentId}.
     */
    private boolean enablePathRouting = true;

    /**
     * Timeout for SSE emitter in milliseconds. This is the maximum time an SSE connection can stay
     * open.
     */
    private long sseTimeout = 600000L;

    public String getPathPrefix() {
        return pathPrefix;
    }

    public void setPathPrefix(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public boolean isCorsEnabled() {
        return corsEnabled;
    }

    public void setCorsEnabled(boolean corsEnabled) {
        this.corsEnabled = corsEnabled;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins;
    }

    public Duration getRunTimeout() {
        return runTimeout;
    }

    public void setRunTimeout(Duration runTimeout) {
        this.runTimeout = runTimeout;
    }

    public ToolMergeMode getDefaultToolMergeMode() {
        return defaultToolMergeMode;
    }

    public void setDefaultToolMergeMode(ToolMergeMode defaultToolMergeMode) {
        this.defaultToolMergeMode = defaultToolMergeMode;
    }

    public boolean isEmitStateEvents() {
        return emitStateEvents;
    }

    public void setEmitStateEvents(boolean emitStateEvents) {
        this.emitStateEvents = emitStateEvents;
    }

    public boolean isEmitToolCallArgs() {
        return emitToolCallArgs;
    }

    public void setEmitToolCallArgs(boolean emitToolCallArgs) {
        this.emitToolCallArgs = emitToolCallArgs;
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public boolean isServerSideMemory() {
        return serverSideMemory;
    }

    public void setServerSideMemory(boolean serverSideMemory) {
        this.serverSideMemory = serverSideMemory;
    }

    public int getMaxThreadSessions() {
        return maxThreadSessions;
    }

    public void setMaxThreadSessions(int maxThreadSessions) {
        this.maxThreadSessions = maxThreadSessions;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getAgentIdHeader() {
        return agentIdHeader;
    }

    public void setAgentIdHeader(String agentIdHeader) {
        this.agentIdHeader = agentIdHeader;
    }

    public boolean isEnablePathRouting() {
        return enablePathRouting;
    }

    public void setEnablePathRouting(boolean enablePathRouting) {
        this.enablePathRouting = enablePathRouting;
    }

    public long getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(long sseTimeout) {
        this.sseTimeout = sseTimeout;
    }
}
