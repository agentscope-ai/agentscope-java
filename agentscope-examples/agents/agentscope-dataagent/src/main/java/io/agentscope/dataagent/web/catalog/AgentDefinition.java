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
package io.agentscope.dataagent.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import java.util.List;

/**
 * API representation of an agent definition visible to the current user.
 *
 * <p>Two scopes exist:
 *
 * <ul>
 *   <li>{@code global} — defined in the project's {@code agentscope.json}, visible to every user.
 *       A user cannot modify or delete global agents, but can start isolated conversations with
 *       them.
 *   <li>{@code user} — defined by a specific user and only visible to that user. The owner can
 *       create, update, and delete their own agents.
 * </ul>
 *
 * @param id agent identifier (unique within its scope)
 * @param name human-readable display name
 * @param description optional short description of the agent's purpose
 * @param sysPrompt system prompt (may be null/hidden for global agents)
 * @param model optional model id override
 * @param maxIters maximum reasoning iterations (null = runtime default)
 * @param tools list of built-in tool names this agent has access to (null = unknown)
 * @param toolsAllow explicit tool allowlist (null = not configured)
 * @param toolsDeny explicit tool denylist (null = not configured)
 * @param identityName display name override (null = use agent name)
 * @param identityEmoji emoji shorthand (null = not set)
 * @param groupChatMentionPatterns mention patterns for group chat (null = not configured)
 * @param groupChatRequireMention whether mention is required in group chat
 * @param skillsAllow explicit skills allowlist (null = not configured)
 * @param skillsDeny explicit skills denylist (null = not configured)
 * @param scope {@code "global"} or {@code "user"}
 * @param ownerId userId of the creator; {@code null} for global agents
 * @param createdAt creation timestamp (epoch ms); {@code 0} for global agents loaded from config
 * @param updatedAt last-updated timestamp (epoch ms)
 * @param shares per-agent share grants (null/empty means unshared)
 * @param runAs identity the agent runs as ({@code "INVOKER"} default; reserved for v2 Claws
 *     semantics where {@code "OWNER"} would impersonate the agent's owner across grantees)
 * @param forkOf source {@code agentId} if this entry was produced by clone; {@code null} otherwise
 * @param workspacePath user-chosen workspace location for this agent's data root. If absolute, used
 *     as-is. If relative or blank, resolved against {@code ~/.agentscope/} (blank defaults to the
 *     agent id). Set at creation time only; not editable afterwards.
 * @param sandboxMode optional execution isolation backing ({@code "local"} or {@code "sandbox"}).
 *     When {@code null} the platform-wide default is used at runtime.
 * @param sandboxScope optional sharing scope when {@code sandboxMode == "sandbox"} ({@code
 *     "SESSION"} / {@code "USER"} / {@code "AGENT"} / {@code "GLOBAL"}). Maps to {@link
 *     io.agentscope.harness.agent.IsolationScope}.
 * @param tierForCurrentUser transient: the calling user's effective tier
 *     ({@code "CLONE"}/{@code "RUN"}/{@code "EDIT"}); only populated on read paths and only when
 *     the caller is authenticated. Never persisted.
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
        List<String> skillsAllow,
        List<String> skillsDeny,
        String scope,
        String ownerId,
        long createdAt,
        long updatedAt,
        List<AgentShareGrant> shares,
        String runAs,
        String forkOf,
        String workspacePath,
        String sandboxMode,
        String sandboxScope,
        String tierForCurrentUser) {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_USER = "user";

    public static final String RUN_AS_INVOKER = "INVOKER";
    public static final String RUN_AS_OWNER = "OWNER";

    /** Returns a copy with {@code tierForCurrentUser} replaced. */
    public AgentDefinition withTierForCurrentUser(String tier) {
        return new AgentDefinition(
                id,
                name,
                description,
                sysPrompt,
                model,
                maxIters,
                tools,
                toolsAllow,
                toolsDeny,
                identityName,
                identityEmoji,
                groupChatMentionPatterns,
                groupChatRequireMention,
                skillsAllow,
                skillsDeny,
                scope,
                ownerId,
                createdAt,
                updatedAt,
                shares,
                runAs,
                forkOf,
                workspacePath,
                sandboxMode,
                sandboxScope,
                tier);
    }
}
