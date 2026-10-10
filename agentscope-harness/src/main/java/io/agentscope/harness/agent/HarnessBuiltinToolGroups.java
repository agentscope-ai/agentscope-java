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
package io.agentscope.harness.agent;

import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Assigns optional Harness built-ins to inactive, agent-managed tool groups. */
final class HarnessBuiltinToolGroups {

    private static final List<GroupDefinition> GROUPS =
            List.of(
                    new GroupDefinition(
                            "workspace_files",
                            "Read, search, create, edit, execute in, and deliver files from the"
                                    + " workspace.",
                            Set.of(
                                    "read_file",
                                    "write_file",
                                    "edit_file",
                                    "grep_files",
                                    "glob_files",
                                    "list_files",
                                    "execute",
                                    "deliver_artifact")),
                    new GroupDefinition(
                            "subagents",
                            "Delegate work to subagents or teams and manage their tasks and async"
                                    + " results.",
                            Set.of(
                                    "agent_spawn",
                                    "agent_send",
                                    "agent_list",
                                    "agent_generate",
                                    "sessions_spawn",
                                    "sessions_send",
                                    "sessions_list",
                                    "sessions_history",
                                    "sessions_pending_completions",
                                    "task_output",
                                    "task_cancel",
                                    "task_list",
                                    "wait_async_results",
                                    "team",
                                    "listTasks",
                                    "listClaimableTasks",
                                    "createTask",
                                    "assignTask",
                                    "claimTask",
                                    "unclaimTask",
                                    "completeTask",
                                    "failTask",
                                    "sendMessage",
                                    "broadcastMessage",
                                    "listMessages",
                                    "listMembers",
                                    "spawnMember",
                                    "shutdownMember",
                                    "submitPlan",
                                    "approvePlan",
                                    "rejectPlan",
                                    "completeTeam")),
                    new GroupDefinition(
                            "session_history",
                            "Search, list, and read prior sessions.",
                            Set.of("session_search", "session_list", "session_history")),
                    new GroupDefinition(
                            "web",
                            "Search the web and fetch web pages.",
                            Set.of("web_search", "web_fetch")));

    private HarnessBuiltinToolGroups() {}

    static void registerBuiltin(Toolkit toolkit, Map<String, AgentTool> builtinTools, Object tool) {
        toolkit.registerTool(tool);
        if (tool instanceof AgentTool agentTool) {
            builtinTools.put(agentTool.getName(), toolkit.getTool(agentTool.getName()));
            return;
        }
        for (Method method : tool.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.name().isEmpty() ? method.getName() : annotation.name();
                builtinTools.put(name, toolkit.getTool(name));
            }
        }
    }

    static void apply(Toolkit toolkit, Map<String, AgentTool> builtinTools) {
        for (GroupDefinition group : GROUPS) {
            if (toolkit.getToolGroup(group.name()) != null) {
                throw new IllegalArgumentException(
                        "Tool group '"
                                + group.name()
                                + "' is reserved when progressive tool loading is enabled");
            }
        }

        Set<String> registeredBuiltins =
                builtinTools.entrySet().stream()
                        .filter(entry -> toolkit.getTool(entry.getKey()) == entry.getValue())
                        .map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> ungroupedTools =
                toolkit.getToolSchemas(List.of()).stream()
                        .map(schema -> schema.getName())
                        .collect(java.util.stream.Collectors.toSet());

        for (String toolName : registeredBuiltins) {
            if (!ungroupedTools.contains(toolName)) {
                throw new IllegalArgumentException(
                        "Harness built-in tool '"
                                + toolName
                                + "' already belongs to a tool group; remove that membership before"
                                + " enabling progressive tool loading");
            }
        }

        for (GroupDefinition group : GROUPS) {
            List<String> members =
                    group.toolNames().stream()
                            .filter(registeredBuiltins::contains)
                            .sorted()
                            .toList();
            if (members.isEmpty()) {
                continue;
            }
            toolkit.createToolGroup(group.name(), group.description(), false);
            members.forEach(toolName -> toolkit.addToolToGroup(group.name(), toolName));
        }
    }

    private record GroupDefinition(String name, String description, Set<String> toolNames) {}
}
