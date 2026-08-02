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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.PermissionPolicy;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.ToolDefaultConfig;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts Agent body tool/MCP/skills structures to harness {@link ToolsConfig} and policy maps. */
public final class AgentSpecCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentSpecCodec() {}

    /** Default toolset: all builtins enabled, always_allow. */
    public static List<AgentToolset> defaultToolsets() {
        return List.of(
                new AgentToolset(
                        AgentSpecTypes.TOOLSET_AGENT,
                        new ToolDefaultConfig(
                                true, PermissionPolicy.of(AgentSpecTypes.POLICY_ALWAYS_ALLOW)),
                        null,
                        null));
    }

    /** Derives harness {@code tools.json} content from Agent body toolsets + mcpServers. */
    public static ToolsConfig toToolsConfig(
            List<AgentToolset> tools, List<McpServerSpec> mcpServers) {
        ToolsConfig cfg = new ToolsConfig();
        List<String> allow = new ArrayList<>();
        List<String> deny = new ArrayList<>();
        boolean hasAgentToolset = false;

        if (tools != null) {
            for (AgentToolset ts : tools) {
                if (ts == null || ts.type() == null) {
                    continue;
                }
                if (AgentSpecTypes.TOOLSET_AGENT.equals(ts.type())) {
                    hasAgentToolset = true;
                    boolean defaultEnabled =
                            ts.defaultConfig() == null
                                    || ts.defaultConfig().enabled() == null
                                    || Boolean.TRUE.equals(ts.defaultConfig().enabled());
                    if (ts.configs() != null && !ts.configs().isEmpty()) {
                        for (ToolConfigEntry c : ts.configs()) {
                            if (c == null || c.name() == null || c.name().isBlank()) {
                                continue;
                            }
                            boolean enabled = c.enabled() != null ? c.enabled() : defaultEnabled;
                            String harnessName = toHarnessToolName(c.name());
                            if (enabled) {
                                allow.add(harnessName);
                            } else {
                                deny.add(harnessName);
                            }
                        }
                    }
                }
            }
        }

        if (hasAgentToolset && !allow.isEmpty()) {
            cfg.setAllow(allow);
        }
        if (!deny.isEmpty()) {
            cfg.setDeny(deny);
        }

        Map<String, McpServerConfig> mcp = new LinkedHashMap<>();
        if (mcpServers != null) {
            for (McpServerSpec s : mcpServers) {
                if (s == null || s.name() == null || s.name().isBlank()) {
                    continue;
                }
                mcp.put(s.name(), toMcpServerConfig(s));
            }
        }
        if (!mcp.isEmpty()) {
            cfg.setMcpServers(mcp);
        }
        return cfg;
    }

    /** Flattens per-tool permission policies for ToolConfirmationMiddleware. */
    public static Map<String, String> toPermissionPolicyMap(List<AgentToolset> tools) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tools == null) {
            return out;
        }
        for (AgentToolset ts : tools) {
            if (ts == null || !AgentSpecTypes.TOOLSET_AGENT.equals(ts.type())) {
                continue;
            }
            String defaultPolicy = policyType(ts.defaultConfig());
            if (ts.configs() != null) {
                for (ToolConfigEntry c : ts.configs()) {
                    if (c == null || c.name() == null || c.name().isBlank()) {
                        continue;
                    }
                    String p =
                            c.permissionPolicy() != null && c.permissionPolicy().type() != null
                                    ? c.permissionPolicy().type()
                                    : defaultPolicy;
                    if (p != null) {
                        out.put(toHarnessToolName(c.name()), p);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Maps product catalog tool ids (Claude-aligned names and legacy aliases) to Harness tool
     * names.
     */
    public static String toHarnessToolName(String productName) {
        if (productName == null || productName.isBlank()) {
            return productName;
        }
        return switch (productName) {
            case "bash", "shell" -> "execute";
            case "read" -> "read_file";
            case "write" -> "write_file";
            case "edit" -> "edit_file";
            case "glob" -> "glob_files";
            case "grep" -> "grep_files";
            case "list_dir" -> "list_files";
            default -> productName;
        };
    }

    /** Builds agent_toolset from a flat allow list (e.g. AI draft suggested tools). */
    public static List<AgentToolset> toolsetsFromAllowList(List<String> allow) {
        if (allow == null || allow.isEmpty()) {
            return defaultToolsets();
        }
        List<ToolConfigEntry> configs = new ArrayList<>();
        for (String name : allow) {
            if (name == null || name.isBlank()) {
                continue;
            }
            configs.add(
                    new ToolConfigEntry(
                            name, true, PermissionPolicy.of(AgentSpecTypes.POLICY_ALWAYS_ALLOW)));
        }
        return List.of(
                new AgentToolset(
                        AgentSpecTypes.TOOLSET_AGENT,
                        new ToolDefaultConfig(
                                true, PermissionPolicy.of(AgentSpecTypes.POLICY_ALWAYS_ALLOW)),
                        configs,
                        null));
    }

    public static List<SkillRef> workspaceSkills(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<SkillRef> out = new ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            out.add(new SkillRef(AgentSpecTypes.SKILL_WORKSPACE, n, null, null));
        }
        return out;
    }

    public static List<String> workspaceSkillNames(List<SkillRef> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (SkillRef s : skills) {
            if (s == null) {
                continue;
            }
            // Marketplace installs are materialized into workspace skills/; enable by name/id.
            if (AgentSpecTypes.SKILL_WORKSPACE.equals(s.type())
                    || AgentSpecTypes.SKILL_MARKETPLACE.equals(s.type())
                    || s.type() == null
                    || s.type().isBlank()) {
                String n = s.name() != null && !s.name().isBlank() ? s.name() : s.id();
                if (n != null && !n.isBlank()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    public static String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize agent spec field", e);
        }
    }

    public static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse agent spec field", e);
        }
    }

    public static List<AgentToolset> readTools(String json) {
        List<AgentToolset> list = readJson(json, new TypeReference<>() {});
        return list != null ? list : List.of();
    }

    public static List<McpServerSpec> readMcpServers(String json) {
        List<McpServerSpec> list = readJson(json, new TypeReference<>() {});
        return list != null ? list : List.of();
    }

    public static List<SkillRef> readSkills(String json) {
        List<SkillRef> list = readJson(json, new TypeReference<>() {});
        return list != null ? list : List.of();
    }

    public static AgentSpecTypes.MultiagentSpec readMultiagent(String json) {
        return readJson(json, new TypeReference<>() {});
    }

    private static String policyType(ToolDefaultConfig cfg) {
        if (cfg == null || cfg.permissionPolicy() == null) {
            return null;
        }
        return cfg.permissionPolicy().type();
    }

    private static McpServerConfig toMcpServerConfig(McpServerSpec s) {
        McpServerConfig c = new McpServerConfig();
        String transport = s.transport();
        if (transport == null || transport.isBlank()) {
            if (s.url() != null && !s.url().isBlank()) {
                transport = "http";
            } else if (s.command() != null && !s.command().isBlank()) {
                transport = "stdio";
            } else {
                transport = "http";
            }
        }
        c.setTransport(transport);
        c.setUrl(s.url());
        c.setCommand(s.command());
        c.setArgs(s.args());
        c.setEnv(s.env());
        c.setHeaders(s.headers());
        c.setQueryParams(s.queryParams());
        c.setEnableTools(s.enableTools());
        if (s.timeout() != null && !s.timeout().isBlank()) {
            c.setTimeout(java.time.Duration.parse(s.timeout()));
        }
        return c;
    }
}
