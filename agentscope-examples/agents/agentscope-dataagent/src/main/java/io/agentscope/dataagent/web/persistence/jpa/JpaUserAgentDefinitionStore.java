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
package io.agentscope.dataagent.web.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.share.AgentShareGrant;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA-backed {@link UserAgentDefinitionStore}. This is the only {@link UserAgentDefinitionStore}
 * implementation shipped with the builder; it is always wired in by {@link JpaPersistenceConfig}.
 *
 * <p>Reads and writes go through a single transaction per call.
 *
 * <p>The {@code workspace_path} column persists the user-supplied workspace path verbatim (null
 * for "use the default location"). Combined with {@link
 * WorkspaceManagerFactory#resolveAgentDataPath}, operators can derive the on-disk root from a
 * single SQL query without re-deriving the convention in application code.
 */
@Transactional
public class JpaUserAgentDefinitionStore implements UserAgentDefinitionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SkillRepositoryConfigEntry>> SKILL_REPO_LIST =
            new TypeReference<>() {};

    private final AgentEntityRepository repository;

    public JpaUserAgentDefinitionStore(AgentEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredEntry> list(String userId) {
        return repository.findByOwnerIdOrderByCreatedAtAsc(userId).stream()
                .map(JpaUserAgentDefinitionStore::toStoredEntry)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredEntry> findById(String userId, String agentId) {
        return repository
                .findByOwnerIdAndAgentId(userId, agentId)
                .map(JpaUserAgentDefinitionStore::toStoredEntry);
    }

    @Override
    public StoredEntry save(String userId, StoredEntry entry) {
        AgentEntity entity =
                repository
                        .findByOwnerIdAndAgentId(userId, entry.id())
                        .orElseGet(
                                () -> {
                                    AgentEntity fresh = new AgentEntity();
                                    fresh.setOwnerId(userId);
                                    fresh.setAgentId(entry.id());
                                    return fresh;
                                });

        entity.setName(entry.name());
        entity.setDescription(entry.description());
        entity.setSysPrompt(entry.sysPrompt());
        entity.setModel(entry.model());
        entity.setMaxIters(entry.maxIters());
        entity.setToolsAllowJson(writeList(entry.toolsAllow()));
        entity.setToolsDenyJson(writeList(entry.toolsDeny()));
        entity.setIdentityName(entry.identityName());
        entity.setIdentityEmoji(entry.identityEmoji());
        entity.setGroupChatMentionPatternsJson(writeList(entry.groupChatMentionPatterns()));
        entity.setGroupChatRequireMention(entry.groupChatRequireMention());
        entity.setSkillsAllowJson(writeList(entry.skillsAllow()));
        entity.setSkillsDenyJson(writeList(entry.skillsDeny()));
        entity.setRunAs(entry.runAs());
        entity.setForkOf(entry.forkOf());
        entity.setSkillRepositoriesJson(writeSkillRepositories(entry.skillRepositories()));
        entity.setSandboxMode(entry.sandboxMode());
        entity.setSandboxScope(entry.sandboxScope());
        entity.setCreatedAt(entry.createdAt());
        entity.setUpdatedAt(entry.updatedAt());

        // Replace shares wholesale via orphan removal.
        entity.getShares().clear();
        if (entry.shares() != null) {
            for (AgentShareGrant g : entry.shares()) {
                entity.getShares()
                        .add(
                                new AgentShareEntity(
                                        entity,
                                        g.granteeType(),
                                        g.granteeId(),
                                        g.tier(),
                                        g.createdAt(),
                                        g.createdBy()));
            }
        }

        // Persist the user-supplied workspace path verbatim (null = use the default location).
        entity.setWorkspacePath(entry.workspacePath());

        AgentEntity saved = repository.save(entity);
        return toStoredEntry(saved);
    }

    @Override
    public boolean delete(String userId, String agentId) {
        // Load the entity first so the OneToMany cascade removes child share rows. A bulk JPQL
        // delete would bypass JPA lifecycle and trip the FK constraint on dataagent_agent_share.
        Optional<AgentEntity> entity = repository.findByOwnerIdAndAgentId(userId, agentId);
        if (entity.isEmpty()) {
            return false;
        }
        repository.delete(entity.get());
        return true;
    }

    // -----------------------------------------------------------------
    //  Mapping helpers
    // -----------------------------------------------------------------

    private static StoredEntry toStoredEntry(AgentEntity e) {
        return new StoredEntry(
                e.getAgentId(),
                e.getName(),
                e.getDescription(),
                e.getSysPrompt(),
                e.getModel(),
                e.getMaxIters(),
                readList(e.getToolsAllowJson()),
                readList(e.getToolsDenyJson()),
                e.getIdentityName(),
                e.getIdentityEmoji(),
                readList(e.getGroupChatMentionPatternsJson()),
                e.getGroupChatRequireMention(),
                readList(e.getSkillsAllowJson()),
                readList(e.getSkillsDenyJson()),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                mapShares(e.getShares(), AgentShareEntity::getCreatedBy),
                e.getRunAs(),
                e.getForkOf(),
                e.getWorkspacePath(),
                readSkillRepositories(e.getSkillRepositoriesJson()),
                e.getSandboxMode(),
                e.getSandboxScope());
    }

    private static List<AgentShareGrant> mapShares(
            List<AgentShareEntity> shares, Function<AgentShareEntity, String> createdByFn) {
        if (shares == null || shares.isEmpty()) {
            return null;
        }
        List<AgentShareGrant> out = new ArrayList<>(shares.size());
        for (AgentShareEntity s : shares) {
            out.add(
                    new AgentShareGrant(
                            s.getGranteeType(),
                            s.getGranteeId(),
                            s.getTier(),
                            s.getCreatedAt(),
                            createdByFn.apply(s)));
        }
        return out;
    }

    private static List<String> readList(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String writeList(List<String> list) {
        if (list == null) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static List<SkillRepositoryConfigEntry> readSkillRepositories(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, SKILL_REPO_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String writeSkillRepositories(List<SkillRepositoryConfigEntry> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
