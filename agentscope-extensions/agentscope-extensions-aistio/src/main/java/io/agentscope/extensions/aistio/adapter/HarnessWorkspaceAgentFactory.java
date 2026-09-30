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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Loads a published definition into a fresh attempt workspace. The application supplies a fresh
 * builder with its model registry and sandbox policy. Runtime files remain local to the application.
 * Applications needing custom tool/credential mapping can implement WorkspaceAgentFactory directly.
 */
public final class HarnessWorkspaceAgentFactory implements WorkspaceAgentFactory {
    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path root;
    private final Supplier<HarnessAgent.Builder> builders;

    public HarnessWorkspaceAgentFactory(Path root, Supplier<HarnessAgent.Builder> builders) {
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.builders = Objects.requireNonNull(builders);
    }

    @Override
    public HarnessAgent create(JsonNode definition, AgentTaskAssignment assignment) {
        try {
            Files.createDirectories(root);
            Path workspace = Files.createTempDirectory(root, "attempt-");
            materialize(workspace, definition.path("files"));
            HarnessAgent.Builder builder =
                    builders.get()
                            .workspace(workspace)
                            .agentId("workspace-" + assignment.attemptId())
                            .name(definition.path("name").asText("Workspace agent"))
                            .sysPrompt(definition.path("system").asText(""));
            if (definition.hasNonNull("model") && !definition.path("model").asText().isBlank())
                builder.model(definition.path("model").asText());
            if (definition.path("maxIters").asInt() > 0)
                builder.maxIters(definition.path("maxIters").asInt());
            builder.toolsConfig(tools(definition));
            builder.permissionContext(permissions(definition));
            List<String> skills = new ArrayList<>();
            for (JsonNode skill : definition.path("skills")) {
                String name = skill.path("name").asText(skill.path("id").asText());
                if (!name.isBlank()) skills.add(name);
            }
            builder.skillFilter(
                    skills.isEmpty()
                            ? SkillFilter.none()
                            : SkillFilter.only(skills.toArray(String[]::new)));
            return builder.build();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot prepare Workspace definition", e);
        }
    }

    static void materialize(Path root, JsonNode files) throws java.io.IOException {
        if (!files.isObject()) return;
        var fields = files.fields();
        while (fields.hasNext()) {
            var file = fields.next();
            Path relative = Path.of(file.getKey());
            Path path = root.resolve(relative).normalize();
            if (relative.isAbsolute() || !path.startsWith(root) || path.equals(root))
                throw new IllegalArgumentException("Definition file escapes attempt workspace");
            for (Path segment : relative) {
                if (List.of(
                                "..",
                                "sessions",
                                "memory",
                                "logs",
                                ".git",
                                "inputs",
                                "outputs",
                                "artifacts")
                        .contains(segment.toString().toLowerCase()))
                    throw new IllegalArgumentException(
                            "Runtime state cannot be a Workspace definition");
            }
            if (file.getKey().equalsIgnoreCase("MEMORY.md") || file.getKey().endsWith(".log.jsonl"))
                throw new IllegalArgumentException(
                        "Private memory cannot be a Workspace definition");
            for (Path current = path;
                    current != null && current.startsWith(root);
                    current = current.getParent())
                if (Files.isSymbolicLink(current))
                    throw new IllegalArgumentException("Definition path is a symbolic link");
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue().asText());
        }
    }

    static ToolsConfig tools(JsonNode definition) {
        ToolsConfig result = new ToolsConfig();
        List<String> allow = new ArrayList<>(), deny = new ArrayList<>();
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        for (JsonNode server : definition.path("mcpServers")) {
            String name = server.path("name").asText();
            if (!name.matches("[A-Za-z0-9_-]{1,64}")
                    || name.contains("__")
                    || servers.containsKey(name))
                throw new IllegalArgumentException("Invalid or duplicate MCP name");
            McpServerConfig config = JSON.convertValue(server, McpServerConfig.class);
            config.setTransport(
                    server.path("transport")
                            .asText(server.hasNonNull("command") ? "stdio" : "http"));
            config.setPrefixToolNames(true);
            config.setRequired(server.path("required").asBoolean(true));
            servers.put(name, config);
        }
        for (JsonNode toolset : definition.path("tools")) {
            boolean enabled = toolset.path("defaultConfig").path("enabled").asBoolean(true);
            String type = toolset.path("type").asText();
            if (type.equals("agent_toolset")) {
                result.setDefaultToolsEnabled(enabled);
                for (JsonNode tool : toolset.path("configs")) {
                    String name = toolName(tool.path("name").asText());
                    if (!tool.path("enabled").asBoolean(enabled)) deny.add(name);
                    else if (!enabled) allow.add(name);
                }
            } else if (type.equals("mcp_toolset")) {
                McpServerConfig server = servers.get(toolset.path("mcpServerName").asText());
                if (server == null) throw new IllegalArgumentException("Undeclared MCP server");
                server.setDefaultToolsEnabled(enabled);
                List<String> include = new ArrayList<>();
                List<String> exclude =
                        new ArrayList<>(
                                server.getDisableTools() == null
                                        ? List.of()
                                        : server.getDisableTools());
                for (JsonNode tool : toolset.path("configs")) {
                    if (!tool.path("enabled").asBoolean(enabled))
                        exclude.add(tool.path("name").asText());
                    else if (!enabled) include.add(tool.path("name").asText());
                }
                if (!enabled) {
                    if (server.getEnableTools() != null && !server.getEnableTools().isEmpty())
                        include.retainAll(server.getEnableTools());
                    server.setEnableTools(include);
                }
                server.setDisableTools(exclude);
            } else throw new IllegalArgumentException("Unsupported toolset: " + type);
        }
        result.setAllow(allow);
        result.setDeny(deny);
        result.setMcpServers(servers);
        return result;
    }

    static PermissionContextState permissions(JsonNode definition) {
        PermissionContextState.Builder result = PermissionContextState.builder();
        for (JsonNode toolset : definition.path("tools")) {
            boolean mcp = toolset.path("type").asText().equals("mcp_toolset");
            String prefix = mcp ? toolset.path("mcpServerName").asText() + "__" : "";
            String fallback =
                    toolset.path("defaultConfig")
                            .path("permissionPolicy")
                            .path("type")
                            .asText("always_ask");
            // Unspecified tool permissions remain subject to the normal Harness engine.
            for (JsonNode tool : toolset.path("configs")) {
                String name =
                        mcp
                                ? prefix + tool.path("name").asText()
                                : toolName(tool.path("name").asText());
                String policy = tool.path("permissionPolicy").path("type").asText(fallback);
                PermissionBehavior behavior =
                        switch (policy) {
                            case "always_allow" -> PermissionBehavior.ALLOW;
                            case "always_ask" -> PermissionBehavior.ASK;
                            case "deny" -> PermissionBehavior.DENY;
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unsupported permission policy: " + policy);
                        };
                PermissionRule rule = new PermissionRule(name, null, behavior, "workspace");
                switch (behavior) {
                    case ALLOW -> result.addAllowRule(name, rule);
                    case DENY -> result.addDenyRule(name, rule);
                    case ASK -> result.addAskRule(name, rule);
                    default ->
                            throw new IllegalArgumentException("Unsupported permission behavior");
                }
            }
        }
        return result.build();
    }

    private static String toolName(String name) {
        return switch (name) {
            case "bash", "shell" -> "execute";
            case "read" -> "read_file";
            case "write" -> "write_file";
            case "edit" -> "edit_file";
            case "glob" -> "glob_files";
            case "grep" -> "grep_files";
            case "list_dir" -> "list_files";
            default -> name;
        };
    }
}
