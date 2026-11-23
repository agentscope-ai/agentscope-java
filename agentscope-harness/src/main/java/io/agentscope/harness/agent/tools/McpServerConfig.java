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
package io.agentscope.harness.agent.tools;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * One MCP server entry under {@code mcpServers.<name>} in {@code tools.json}.
 *
 * <p>Mirrors the standard MCP client configuration shape so that workspaces can be moved between
 * AgentScope and other MCP-aware tools with minimal edits. Field semantics map directly onto
 * {@link io.agentscope.core.tool.mcp.McpClientBuilder}.
 *
 * <p>{@code transport} is the discriminator:
 *
 * <ul>
 *   <li>{@code stdio} — uses {@link #command} + {@link #args} + {@link #env}.
 *   <li>{@code sse} — uses {@link #url} + {@link #headers} + {@link #queryParams}.
 *   <li>{@code http} — streamable HTTP, same fields as {@code sse}.
 * </ul>
 *
 * <p>Request-metadata propagation to the server is controlled by three optional fields mirroring
 * the three-level switches of the core MCP client: {@link #propagateMeta} (connection level),
 * {@link #propagateMetaDefault} (registration level) and {@link #propagateMetaOverrides}
 * (per-tool). All are absent by default, which keeps the core behavior unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpServerConfig {

    /** Transport type: {@code stdio}, {@code sse}, or {@code http}. */
    @JsonProperty("transport")
    private String transport;

    /** stdio: executable command. */
    @JsonProperty("command")
    private String command;

    /** stdio: command arguments. */
    @JsonProperty("args")
    private List<String> args;

    /** stdio: environment variables passed to the spawned process. */
    @JsonProperty("env")
    private Map<String, String> env;

    /** sse / http: server URL. */
    @JsonProperty("url")
    private String url;

    /** sse / http: HTTP headers attached to every request. */
    @JsonProperty("headers")
    private Map<String, String> headers;

    /** sse / http: query parameters merged into the request URL. */
    @JsonProperty("queryParams")
    private Map<String, String> queryParams;

    /**
     * Optional allowlist of tools to import from this server. When {@code null} or empty, all
     * tools the server advertises are registered.
     */
    @JsonProperty("enableTools")
    private List<String> enableTools;

    /** ISO-8601 duration for per-request timeout. {@code null} keeps the builder default. */
    @JsonProperty("timeout")
    private Duration timeout;

    /** ISO-8601 duration for client initialization timeout. {@code null} keeps the default. */
    @JsonProperty("initializationTimeout")
    private Duration initializationTimeout;

    /**
     * Connection-level switch for request-metadata propagation to this server. When {@code null},
     * the core default ({@code true}) applies. When {@code false}, no metadata leaves the process
     * for this server while the switch stays {@code false} (deployment-level cut-off). The switch
     * is evaluated live per call inside core, but the Harness currently exposes no handle to flip
     * it at runtime — an operator-facing accessor would have to come from core first, so treat
     * this as a build-time configuration.
     */
    @JsonProperty("propagateMeta")
    private Boolean propagateMeta;

    /**
     * Registration-level default for tools imported from this server. When {@code null}, tools
     * keep the core default ({@code true}) and only the connection-level switch gates
     * propagation. When {@code false}, every tool from this server is registered silent by
     * default, while individual tools may still be re-enabled via {@link #propagateMetaOverrides}.
     */
    @JsonProperty("propagateMetaDefault")
    private Boolean propagateMetaDefault;

    /**
     * Per-tool overrides applied on top of {@link #propagateMetaDefault}. Keys are remote tool
     * names as advertised by the server (before any {@code mcpToolNamePrefix}); a name that does
     * not match a tool actually registered from this server fails the registration with an
     * {@link IllegalArgumentException}, so an override is never lost silently. {@code null}
     * values are rejected the same way.
     *
     * <p>Note the ceiling: an explicit {@code true} here cannot overrule a {@code false}
     * {@link #propagateMeta} connection-level switch while that switch stays {@code false}. The
     * entry is remembered and takes effect only if the connection level is ever re-enabled.
     */
    @JsonProperty("propagateMetaOverrides")
    private Map<String, Boolean> propagateMetaOverrides;

    private boolean defaultToolsEnabled = true;
    private List<String> disableTools;
    private boolean prefixToolNames;
    private boolean required;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private java.util.function.Consumer<McpConnectionException> connectionFailureHandler;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.function.Consumer<McpConnectionException> getConnectionFailureHandler() {
        return connectionFailureHandler;
    }

    public void setConnectionFailureHandler(
            java.util.function.Consumer<McpConnectionException> handler) {
        connectionFailureHandler = handler;
    }

    public boolean isDefaultToolsEnabled() {
        return defaultToolsEnabled;
    }

    public void setDefaultToolsEnabled(boolean value) {
        defaultToolsEnabled = value;
    }

    public List<String> getDisableTools() {
        return disableTools;
    }

    public void setDisableTools(List<String> value) {
        disableTools = value;
    }

    public boolean isPrefixToolNames() {
        return prefixToolNames;
    }

    public void setPrefixToolNames(boolean value) {
        prefixToolNames = value;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean value) {
        required = value;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public Map<String, String> getQueryParams() {
        return queryParams;
    }

    public void setQueryParams(Map<String, String> queryParams) {
        this.queryParams = queryParams;
    }

    public List<String> getEnableTools() {
        return enableTools;
    }

    public void setEnableTools(List<String> enableTools) {
        this.enableTools = enableTools;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Duration getInitializationTimeout() {
        return initializationTimeout;
    }

    public void setInitializationTimeout(Duration initializationTimeout) {
        this.initializationTimeout = initializationTimeout;
    }

    public Boolean getPropagateMeta() {
        return propagateMeta;
    }

    public void setPropagateMeta(Boolean propagateMeta) {
        this.propagateMeta = propagateMeta;
    }

    public Boolean getPropagateMetaDefault() {
        return propagateMetaDefault;
    }

    public void setPropagateMetaDefault(Boolean propagateMetaDefault) {
        this.propagateMetaDefault = propagateMetaDefault;
    }

    public Map<String, Boolean> getPropagateMetaOverrides() {
        return propagateMetaOverrides;
    }

    public void setPropagateMetaOverrides(Map<String, Boolean> propagateMetaOverrides) {
        this.propagateMetaOverrides = propagateMetaOverrides;
    }
}
