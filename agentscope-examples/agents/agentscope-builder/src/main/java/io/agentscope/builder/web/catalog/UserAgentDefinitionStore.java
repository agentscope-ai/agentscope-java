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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.runtime.config.AgentConfigEntry;
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.MultiagentSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.share.AgentShareGrant;
import java.util.List;
import java.util.Optional;

/**
 * Abstraction over the per-user custom agent definition registry.
 *
 * <p>Agent definitions carry the user-facing metadata for every custom agent (id, name,
 * description, system prompt, model overrides, tools/mcpServers/skills, identity config, sharing
 * grants, ...). Workspace files (skills, subagents, memory, ...) are stored separately under the
 * agent's namespaced workspace directory; only the lightweight definition lives here.
 *
 * <p>The only bundled implementation is
 * {@link io.agentscope.builder.web.persistence.jpa.JpaUserAgentDefinitionStore}, which persists
 * via Spring Data JPA with a soft foreign key to the user table. The default DataSource is the
 * embedded H2 database at {@code ${user.home}/.agentscope-builder/db}; activate the
 * {@code jdbc} Spring profile (or set {@code BUILDER_DB_URL}) to point at MySQL / PostgreSQL.
 *
 * <p>Implementations are expected to be thread-safe.
 */
public interface UserAgentDefinitionStore {

    /** Returns all agent definitions owned by {@code userId}. Insertion-ordered. */
    List<StoredEntry> list(String userId);

    /** Finds a single agent definition by id for the given user. */
    Optional<StoredEntry> findById(String userId, String agentId);

    /**
     * Saves (creates or updates) an agent definition for the given user. Implementations persist
     * atomically.
     */
    StoredEntry save(String userId, StoredEntry entry);

    /** Deletes an agent definition. Returns {@code true} if the entry existed and was removed. */
    boolean delete(String userId, String agentId);

    // -----------------------------------------------------------------
    //  Stored data model — stable wire format consumed across the app
    // -----------------------------------------------------------------

    /**
     * JSON-serializable agent definition stored per user. All fields are optional except {@code
     * id}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredEntry(
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
            long createdAt,
            long updatedAt,
            List<AgentShareGrant> shares,
            String runAs,
            String forkOf,
            String workspacePath,
            List<SkillRepositoryConfigEntry> skillRepositories,
            String sandboxMode,
            String sandboxScope,
            int version,
            Long archivedAt) {

        public AgentDefinition toDefinition(String ownerId) {
            return new AgentDefinition(
                    id,
                    name != null ? name : id,
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
                    AgentDefinition.SCOPE_USER,
                    ownerId,
                    createdAt,
                    updatedAt,
                    shares,
                    runAs != null ? runAs : AgentDefinition.RUN_AS_INVOKER,
                    forkOf,
                    workspacePath,
                    sandboxMode,
                    sandboxScope,
                    version,
                    archivedAt,
                    null, // metadata
                    null); // tierForCurrentUser — populated by the controller
        }

        /** Convert to a partial {@link AgentConfigEntry} for runtime agent construction. */
        public AgentConfigEntry toConfigEntry() {
            AgentConfigEntry e = new AgentConfigEntry();
            e.setName(name);
            e.setDescription(description);
            e.setSysPrompt(system);
            e.setModel(model);
            e.setMaxIters(maxIters);
            var toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
            if (toolsConfig.getAllow() != null || toolsConfig.getDeny() != null) {
                AgentConfigEntry.ToolsConfig tc = new AgentConfigEntry.ToolsConfig();
                tc.setAllow(toolsConfig.getAllow());
                tc.setDeny(toolsConfig.getDeny());
                e.setTools(tc);
            }
            if (identityName != null || identityEmoji != null) {
                AgentConfigEntry.IdentityConfig ic = new AgentConfigEntry.IdentityConfig();
                ic.setName(identityName);
                ic.setEmoji(identityEmoji);
                e.setIdentity(ic);
            }
            if (groupChatMentionPatterns != null || groupChatRequireMention != null) {
                AgentConfigEntry.GroupChatConfig gc = new AgentConfigEntry.GroupChatConfig();
                gc.setMentionPatterns(groupChatMentionPatterns);
                gc.setRequireMention(groupChatRequireMention);
                e.setGroupChat(gc);
            }
            List<String> skillNames = AgentSpecCodec.workspaceSkillNames(skills);
            if (!skillNames.isEmpty()) {
                AgentConfigEntry.SkillsConfig sk = new AgentConfigEntry.SkillsConfig();
                sk.setAllow(skillNames);
                e.setSkills(sk);
            }
            if (skillRepositories != null && !skillRepositories.isEmpty()) {
                e.setSkillRepositories(skillRepositories);
            }
            return e;
        }
    }
}
