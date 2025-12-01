/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.tool;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal registry for managing tool registration and lookup.
 *
 * <p>This class maintains mappings between tool names and their implementations, along with
 * metadata about registered tool functions. It is used internally by {@link Toolkit} to organize
 * and retrieve tools.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe, using {@link ConcurrentHashMap} for internal
 * storage to support concurrent tool registration and lookup operations.
 *
 * <p><b>Key Responsibilities:</b>
 * <ul>
 *   <li>Store and retrieve {@link AgentTool} implementations by name</li>
 *   <li>Maintain {@link RegisteredToolFunction} metadata for schema generation</li>
 *   <li>Support dynamic tool removal for group-based activation</li>
 * </ul>
 */
class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, RegisteredToolFunction> registeredTools = new ConcurrentHashMap<>();
    private final Map<String, Toolkit.AgentSkill> skills = new ConcurrentHashMap<>();

    /**
     * Register a tool with its metadata.
     *
     * @param toolName Tool name
     * @param tool AgentTool implementation
     * @param registered RegisteredToolFunction wrapper with metadata
     */
    void registerTool(String toolName, AgentTool tool, RegisteredToolFunction registered) {
        tools.put(toolName, tool);
        registeredTools.put(toolName, registered);
    }

    /**
     * Register an agent skill.
     *
     * @param skill AgentSkill to register
     * @throws IllegalArgumentException if a skill with the same name is already registered
     */
    void registerAgentSkill(Toolkit.AgentSkill skill) {
        String name = skill.getName();
        if (skills.containsKey(name)) {
            throw new IllegalArgumentException(
                    "An agent skill with name " + name + " already registered in the toolkit");
        }
        skills.put(name, skill);
    }

    /**
     * Get an agent skill by name.
     *
     * @param skillName Agent skill name
     * @return AgentSkill or null if not found
     */
    Toolkit.AgentSkill getAgentSkill(String skillName) {
        return skills.get(skillName);
    }

    /**
     * Get all agent skills.
     *
     * @return Map of agent skill name to AgentSkill
     */
    Set<Toolkit.AgentSkill> getAllAgentSkills() {
        return new HashSet<>(skills.values());
    }

    /**
     * Get all agent skill names.
     *
     * @return Set of agent skill names
     */
    Set<String> getAllAgentSkillNames() {
        return new HashSet<>(skills.keySet());
    }

    /**
     * Get tool by name.
     *
     * @param name Tool name
     * @return AgentTool or null if not found
     */
    AgentTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Get registered tool function by name.
     *
     * @param name Tool name
     * @return RegisteredToolFunction or null if not found
     */
    RegisteredToolFunction getRegisteredTool(String name) {
        return registeredTools.get(name);
    }

    /**
     * Get all tool names.
     *
     * @return Set of tool names
     */
    Set<String> getToolNames() {
        return new HashSet<>(tools.keySet());
    }

    /**
     * Get all registered tool functions.
     *
     * @return Map of tool name to RegisteredToolFunction
     */
    Map<String, RegisteredToolFunction> getAllRegisteredTools() {
        return new ConcurrentHashMap<>(registeredTools);
    }

    /**
     * Remove a tool by name.
     *
     * @param toolName Tool name to remove
     */
    void removeTool(String toolName) {
        tools.remove(toolName);
        registeredTools.remove(toolName);
    }

    /**
     * Remove multiple tools by names.
     *
     * @param toolNames Set of tool names to remove
     */
    void removeTools(Set<String> toolNames) {
        toolNames.forEach(this::removeTool);
    }

    /**
     * Remove an agent skill by name.
     *
     * @param skillName Agent skill name to remove
     */
    void removeAgentSkill(String skillName) {
        skills.remove(skillName);
    }

    /**
     * Remove multiple agent skills by names.
     *
     * @param skillNames Set of agent skill names to remove
     */
    void removeAgentSkills(Set<String> skillNames) {
        skillNames.forEach(this::removeAgentSkill);
    }
}
