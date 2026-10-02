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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.MultiagentSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.util.List;
import java.util.Map;

/**
 * API representation of an agent definition visible to the current user.
 *
 * <p>Definition fields ({@code system}, {@code tools}, {@code mcpServers}, {@code skills}) are the
 * versioned Agent body — see {@code docs/API_REFACTOR.md}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDefinition(
        String id,
        String name,
        String description,
        String system,
        String model,
        Integer maxIters,
        List<AgentToolset> tools,
        List<McpServerSpec> mcpServers,
        List<SkillRef> skills,
        MultiagentSpec multiagent,
        String identityName,
        String identityEmoji,
        List<String> groupChatMentionPatterns,
        Boolean groupChatRequireMention,
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
        Integer version,
        Long archivedAt,
        Map<String, Object> metadata,
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
                system,
                model,
                maxIters,
                tools,
                mcpServers,
                skills,
                multiagent,
                identityName,
                identityEmoji,
                groupChatMentionPatterns,
                groupChatRequireMention,
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
                version,
                archivedAt,
                metadata,
                tier);
    }
}
