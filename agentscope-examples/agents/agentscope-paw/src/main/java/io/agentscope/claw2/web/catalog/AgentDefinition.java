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
package io.agentscope.claw2.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * API representation of an agent definition surfaced by the local catalog.
 *
 * <p>agentscope-claw is single-user. There are two kinds of agents that live side by side and
 * share the same workspace layout:
 *
 * <ul>
 *   <li><b>built-in</b> — defined in {@code ${clawHome}/agentscope.json}. Built-in entries are
 *       always present and cannot be deleted through the API.
 *   <li><b>custom</b> — defined by the user through the UI and persisted in
 *       {@code ${clawHome}/agents.json}.
 * </ul>
 *
 * <p>The {@code builtin} flag tells the frontend which kind it is so it can hide the
 * "delete agent" affordance for built-ins.
 *
 * @param id agent identifier (unique across the catalog)
 * @param name human-readable display name
 * @param description optional short description of the agent's purpose
 * @param sysPrompt system prompt
 * @param model optional model id override
 * @param maxIters maximum reasoning iterations (null = runtime default)
 * @param tools list of built-in tool names this agent has access to (null = unknown)
 * @param toolsAllow explicit tool allowlist (null = not configured)
 * @param toolsDeny explicit tool denylist (null = not configured)
 * @param identityName display name override (null = use agent name)
 * @param identityEmoji emoji shorthand (null = not set)
 * @param groupChatMentionPatterns mention patterns for group chat (null = not configured)
 * @param groupChatRequireMention whether mention is required in group chat
 * @param sandboxMode reserved for future per-agent sandboxing (defaults to {@code "off"})
 * @param sandboxScope reserved for future per-agent sandboxing (defaults to {@code "agent"})
 * @param skillsAllow explicit skills allowlist (null = not configured)
 * @param skillsDeny explicit skills denylist (null = not configured)
 * @param createdAt creation timestamp (epoch ms); {@code 0} for built-in agents
 * @param updatedAt last-updated timestamp (epoch ms)
 * @param workspacePath optional workspace location override. When absolute, used as-is. When
 *     relative or blank, resolved against {@code ${clawHome}/agents/{id}/workspace}.
 * @param builtin {@code true} when this entry comes from the built-in {@code agentscope.json}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        String id,
        String name,
        String description,
        String sysPrompt,
        String model,
        Integer maxIters,
        List<String> tools,
        List<String> toolsAllow,
        List<String> toolsDeny,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
        String sandboxMode,
        String sandboxScope,
        List<String> skillsAllow,
        List<String> skillsDeny,
        long createdAt,
        long updatedAt,
        String workspacePath,
        boolean builtin) {}
