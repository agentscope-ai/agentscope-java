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
package io.agentscope.extensions.sandbox.agentrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Execution channel for an AgentRun sandbox built on the AgentScope MCP client.
 *
 * <p>Reuses {@link McpClientBuilder#streamableHttpTransport(String)} with the AgentRun API-key
 * header, and exposes the three tool names that an AgentRun sandbox template enables for
 * AgentScope: {@code process_exec_cmd}, {@code read_file}, {@code write_file}.
 */
final class AgentRunMcpChannel implements AutoCloseable {

    /** MCP tool name for shell-style command execution. */
    static final String TOOL_EXEC = "process_exec_cmd";

    /** MCP tool name for reading a file from the sandbox filesystem. */
    static final String TOOL_READ_FILE = "read_file";

    /** MCP tool name for writing a file to the sandbox filesystem. */
    static final String TOOL_WRITE_FILE = "write_file";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentRunSandboxClientOptions opt;
    private final String url;
    private volatile McpClientWrapper client;

    AgentRunMcpChannel(AgentRunSandboxClientOptions opt) {
        this.opt = Objects.requireNonNull(opt, "opt");
        this.url = resolveUrl(opt);
    }

    /** Connects the MCP client. Idempotent — repeated calls are a no-op. */
    void connect() {
        if (client != null) {
            return;
        }
        McpClientWrapper c =
                McpClientBuilder.create("agentrun-" + opt.getTemplateName())
                        .streamableHttpTransport(url)
                        .header("X-API-Key", requireApiKey())
                        .header("X-Acs-Parent-Id", nullToEmpty(opt.getAccountId()))
                        .timeout(Duration.ofSeconds(Math.max(60, opt.getReadTimeoutSeconds())))
                        .protocolVersions("2024-11-05", "2025-03-26")
                        .buildSync();
        c.initialize().block();
        this.client = c;
    }

    /** Result of a shell command executed in the sandbox. */
    static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
            this.stderr = stderr != null ? stderr : "";
        }
    }

    /** Runs {@code command} via the AgentRun {@code process_exec_cmd} MCP tool. */
    ExecResult exec(String command, String cwd, int timeoutSeconds) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("command", command);
        if (cwd != null && !cwd.isBlank()) {
            args.put("cwd", cwd);
        }
        args.put("timeout", Math.max(1, timeoutSeconds));
        McpSchema.CallToolResult result =
                client.callTool(TOOL_EXEC, args)
                        .block(Duration.ofSeconds(Math.max(5, timeoutSeconds) + 30L));
        if (result == null) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.EXEC_TIMEOUT, "AgentRun MCP exec returned null");
        }
        if (Boolean.TRUE.equals(result.isError())) {
            String msg = extractText(result);
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "AgentRun MCP " + TOOL_EXEC + " error: " + msg);
        }
        return parseExecPayload(extractText(result));
    }

    /** Reads a file via the AgentRun {@code read_file} MCP tool, returning its text content. */
    String readFile(String absolutePath) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        McpSchema.CallToolResult result =
                client.callTool(TOOL_READ_FILE, args).block(Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_READ_ERROR,
                    "AgentRun MCP "
                            + TOOL_READ_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
        return extractText(result);
    }

    /** Writes a file via the AgentRun {@code write_file} MCP tool. */
    void writeFile(String absolutePath, String content) {
        ensureConnected();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", absolutePath);
        args.put("content", content != null ? content : "");
        McpSchema.CallToolResult result =
                client.callTool(TOOL_WRITE_FILE, args).block(Duration.ofSeconds(120));
        if (result == null || Boolean.TRUE.equals(result.isError())) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_ARCHIVE_WRITE_ERROR,
                    "AgentRun MCP "
                            + TOOL_WRITE_FILE
                            + " failed for "
                            + absolutePath
                            + ": "
                            + (result == null ? "null" : extractText(result)));
        }
    }

    /** Returns the MCP endpoint URL this channel uses. */
    String getUrl() {
        return url;
    }

    @Override
    public void close() {
        McpClientWrapper c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // best-effort
            }
            client = null;
        }
    }

    private void ensureConnected() {
        if (client == null) {
            connect();
        }
    }

    private String requireApiKey() {
        String key = opt.getApiKey();
        if (key == null || key.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun API key is required (set AgentRunSandboxClientOptions#setApiKey)");
        }
        return key;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String resolveUrl(AgentRunSandboxClientOptions opt) {
        String base = opt.getMcpServerUrl();
        if (base == null || base.isBlank()) {
            throw new SandboxException.SandboxConfigurationException(
                    "AgentRun MCP server URL is required (set #setMcpServerUrl)");
        }
        String endpoint = opt.getMcpEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return base;
        }
        // Treat base as either a host root or already including the endpoint.
        if (base.endsWith(endpoint) || base.contains(endpoint + "?")) {
            return base;
        }
        String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String tail = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return trimmed + tail;
    }

    private static String extractText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content c : result.content()) {
            if (c instanceof McpSchema.TextContent t && t.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /**
     * Parses an AgentRun exec MCP response. AgentRun returns either a JSON object such as
     * {@code {"exitCode":0,"stdout":"...","stderr":"..."}} or a plain stdout/stderr string. We
     * accept both shapes, including the nested format {@code {"result":{"exitCode":...}}} and
     * responses with markdown banner prefixes before the JSON payload.
     */
    private static ExecResult parseExecPayload(String text) {
        if (text == null) {
            return new ExecResult(0, "", "");
        }
        String trimmed = text.strip();

        int braceIdx = trimmed.indexOf('{');
        if (braceIdx >= 0) {
            String jsonText = braceIdx == 0 ? trimmed : trimmed.substring(braceIdx);
            try {
                JsonNode node = JSON.readTree(jsonText);
                JsonNode result = node.path("result");
                JsonNode source = result.isObject() ? result : node;
                if (source.has("exitCode") || source.has("exit_code")) {
                    int exit =
                            source.has("exitCode")
                                    ? source.path("exitCode").asInt(0)
                                    : source.path("exit_code").asInt(0);
                    String stdout =
                            source.has("stdout")
                                    ? source.path("stdout").asText("")
                                    : source.path("output").asText("");
                    String stderr = source.path("stderr").asText("");
                    return new ExecResult(exit, stdout, stderr);
                }
            } catch (Exception ignore) {
                // fall through to plain-text handling
            }
        }
        return new ExecResult(0, text, "");
    }
}
