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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace-level tool configuration loaded from {@code workspace/tools.json}.
 *
 * <p>Two responsibilities:
 *
 * <ul>
 *   <li>{@link #allow} / {@link #deny} — filter the harness's built-in tool surface.
 *   <li>{@link #mcpServers} — declare additional tools served by external MCP servers.
 * </ul>
 *
 * <p>Filter semantics: when {@code allow} is non-empty, only tools whose names appear in it are
 * kept; {@code deny} always wins regardless of {@code allow}. Empty/absent values mean "no
 * filtering on this side".
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolsConfig {

    /** When non-empty, only tools whose name is in this list are exposed to the model. */
    @JsonProperty("allow")
    private List<String> allow;

    /** Tools whose name appears here are removed regardless of {@link #allow}. */
    @JsonProperty("deny")
    private List<String> deny;

    /** Map of MCP server identifier to its connection / tool-allowlist configuration. */
    @JsonProperty("mcpServers")
    private Map<String, McpServerConfig> mcpServers;

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public List<String> getDeny() {
        return deny;
    }

    public void setDeny(List<String> deny) {
        this.deny = deny;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers != null ? new LinkedHashMap<>(mcpServers) : null;
    }
}
