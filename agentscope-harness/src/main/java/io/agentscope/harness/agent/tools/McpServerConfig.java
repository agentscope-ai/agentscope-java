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

    /** http: whether StreamableHTTP should use resumable streams. */
    @JsonProperty("resumableStreams")
    private Boolean resumableStreams;

    /** http: whether StreamableHTTP should open a connection during startup. */
    @JsonProperty("openConnectionOnStartup")
    private Boolean openConnectionOnStartup;

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

    public Boolean getResumableStreams() {
        return resumableStreams;
    }

    public void setResumableStreams(Boolean resumableStreams) {
        this.resumableStreams = resumableStreams;
    }

    public Boolean getOpenConnectionOnStartup() {
        return openConnectionOnStartup;
    }

    public void setOpenConnectionOnStartup(Boolean openConnectionOnStartup) {
        this.openConnectionOnStartup = openConnectionOnStartup;
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
}
