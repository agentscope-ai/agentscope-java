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
package io.agentscope.builder.web.catalog.spec;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Wire types for the Managed Agents Agent body (tools / MCP / skills / multiagent), aligned with
 * Claude Managed Agents semantics. See {@code docs/API_REFACTOR.md}.
 */
public final class AgentSpecTypes {

    private AgentSpecTypes() {}

    public static final String TOOLSET_AGENT = "agent_toolset";
    public static final String TOOLSET_MCP = "mcp_toolset";

    public static final String POLICY_ALWAYS_ALLOW = "always_allow";
    public static final String POLICY_ALWAYS_ASK = "always_ask";
    public static final String POLICY_DENY = "deny";

    public static final String SKILL_WORKSPACE = "workspace";
    public static final String SKILL_MARKETPLACE = "marketplace";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PermissionPolicy(String type) {
        public PermissionPolicy {
            if (type == null || type.isBlank()) {
                type = POLICY_ALWAYS_ALLOW;
            }
        }

        public static PermissionPolicy of(String type) {
            return new PermissionPolicy(type);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolDefaultConfig(Boolean enabled, PermissionPolicy permissionPolicy) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolConfigEntry(
            String name, Boolean enabled, PermissionPolicy permissionPolicy) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentToolset(
            String type,
            ToolDefaultConfig defaultConfig,
            List<ToolConfigEntry> configs,
            String mcpServerName) {}

    /**
     * MCP server declaration on the Agent body. Maps to harness {@code tools.json} mcpServers
     * entries when derived to disk.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McpServerSpec(
            String name,
            String type,
            String url,
            String transport,
            String command,
            List<String> args,
            Map<String, String> env,
            Map<String, String> headers,
            Map<String, String> queryParams,
            List<String> enableTools,
            String timeout) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillRef(String type, String name, String id, String version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiagentAgentRef(String type, String id, Integer version) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MultiagentSpec(String type, List<MultiagentAgentRef> agents) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelConfig(String id) {}
}
