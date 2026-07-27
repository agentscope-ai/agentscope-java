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
package io.agentscope.builder.web.managed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry;
import io.agentscope.builder.web.catalog.UserAgentDefinitionStore;
import io.agentscope.builder.web.persistence.jpa.AgentEntity;
import io.agentscope.builder.web.persistence.jpa.AgentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntityRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Manages immutable version snapshots for user-custom agents. */
@Service
@Transactional
public class AgentVersionService {

    /**
     * Synthetic owner id used to key version rows for global (project-level) agents, which have
     * no {@code ownerId} of their own. Global agents share a single version-1 snapshot across all
     * users; see {@link #ensureGlobalVersion}.
     */
    public static final String GLOBAL_OWNER = "__global__";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<SkillRepositoryConfigEntry>> SKILL_REPO_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> POLICY_MAP = new TypeReference<>() {};

    private final AgentVersionEntityRepository versionRepository;
    private final AgentEntityRepository agentRepository;
    private final ObjectMapper objectMapper;

    public AgentVersionService(
            AgentVersionEntityRepository versionRepository,
            AgentEntityRepository agentRepository,
            ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    /** Builds a snapshot from a stored catalog entry (permission policies omitted). */
    public AgentVersionSnapshot snapshotFromStoredEntry(
            UserAgentDefinitionStore.StoredEntry entry) {
        return new AgentVersionSnapshot(
                entry.name(),
                entry.description(),
                entry.sysPrompt(),
                entry.model(),
                entry.maxIters(),
                entry.toolsAllow(),
                entry.toolsDeny(),
                entry.identityName(),
                entry.identityEmoji(),
                entry.groupChatMentionPatterns(),
                entry.groupChatRequireMention(),
                entry.skillsAllow(),
                entry.skillsDeny(),
                entry.skillRepositories(),
                entry.sandboxMode(),
                entry.sandboxScope(),
                entry.permissionPolicies());
    }

    /** Builds a snapshot from a persisted agent entity including permission policies. */
    public AgentVersionSnapshot snapshotFromEntity(AgentEntity entity) {
        return new AgentVersionSnapshot(
                entity.getName(),
                entity.getDescription(),
                entity.getSysPrompt(),
                entity.getModel(),
                entity.getMaxIters(),
                readStringList(entity.getToolsAllowJson()),
                readStringList(entity.getToolsDenyJson()),
                entity.getIdentityName(),
                entity.getIdentityEmoji(),
                readStringList(entity.getGroupChatMentionPatternsJson()),
                entity.getGroupChatRequireMention(),
                readStringList(entity.getSkillsAllowJson()),
                readStringList(entity.getSkillsDenyJson()),
                readSkillRepositories(entity.getSkillRepositoriesJson()),
                entity.getSandboxMode(),
                entity.getSandboxScope(),
                readPolicyMap(entity.getPermissionPoliciesJson()));
    }

    /** Serializes a snapshot to JSON. */
    public String toJson(AgentVersionSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize snapshot", ex);
        }
    }

    /** Deserializes a snapshot from JSON. */
    public AgentVersionSnapshot fromJson(String json) {
        try {
            return objectMapper.readValue(json, AgentVersionSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid snapshot JSON", ex);
        }
    }

    /** Creates version 1 for a newly created agent and bumps the head pointer. */
    public int createInitialVersion(String ownerId, String agentId, AgentVersionSnapshot snapshot) {
        AgentEntity agent =
                agentRepository
                        .findByOwnerIdAndAgentId(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        long now = System.currentTimeMillis();
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(ownerId);
        version.setAgentId(agentId);
        version.setVersion(1);
        version.setSnapshotJson(toJson(snapshot));
        version.setCreatedAt(now);
        versionRepository.save(version);
        agent.setHeadVersion(1);
        agentRepository.save(agent);
        return 1;
    }

    /**
     * Appends a new version when the snapshot changed. Returns the current head version (unchanged
     * when the snapshot is identical to the latest stored version).
     */
    public int appendVersion(String ownerId, String agentId, AgentVersionSnapshot snapshot) {
        AgentEntity agent =
                agentRepository
                        .findByOwnerIdAndAgentId(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        String newJson = toJson(snapshot);
        int head = agent.getHeadVersion();
        var latest = versionRepository.findByOwnerIdAndAgentIdAndVersion(ownerId, agentId, head);
        if (latest.isPresent() && Objects.equals(latest.get().getSnapshotJson(), newJson)) {
            return head;
        }
        int next = head + 1;
        long now = System.currentTimeMillis();
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(ownerId);
        version.setAgentId(agentId);
        version.setVersion(next);
        version.setSnapshotJson(newJson);
        version.setCreatedAt(now);
        versionRepository.save(version);
        agent.setHeadVersion(next);
        agent.setUpdatedAt(now);
        agentRepository.save(agent);
        return next;
    }

    /**
     * Materializes version 1 for a global agent (owner {@link #GLOBAL_OWNER}) if it does not
     * already exist, and returns the current head version. Global agents have no {@link
     * io.agentscope.builder.web.persistence.jpa.AgentEntity} row, so unlike {@link
     * #createInitialVersion} this does not touch a head-version pointer on that entity — the
     * version-1 row itself is the head until global version history is supported.
     */
    public int ensureGlobalVersion(String agentId, AgentVersionSnapshot snapshot) {
        if (versionRepository
                .findByOwnerIdAndAgentIdAndVersion(GLOBAL_OWNER, agentId, 1)
                .isPresent()) {
            return 1;
        }
        AgentVersionEntity version = new AgentVersionEntity();
        version.setOwnerId(GLOBAL_OWNER);
        version.setAgentId(agentId);
        version.setVersion(1);
        version.setSnapshotJson(toJson(snapshot));
        version.setCreatedAt(System.currentTimeMillis());
        versionRepository.save(version);
        return 1;
    }

    /** Lists all versions for an agent in ascending order. */
    @Transactional(readOnly = true)
    public List<AgentVersionEntity> listVersions(String ownerId, String agentId) {
        return versionRepository.findByOwnerIdAndAgentIdOrderByVersionAsc(ownerId, agentId);
    }

    /** Returns a specific version snapshot row. */
    @Transactional(readOnly = true)
    public AgentVersionEntity getVersion(String ownerId, String agentId, int version) {
        return versionRepository
                .findByOwnerIdAndAgentIdAndVersion(ownerId, agentId, version)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Agent version not found: " + agentId + "@" + version));
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private List<SkillRepositoryConfigEntry> readSkillRepositories(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SKILL_REPO_LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private Map<String, String> readPolicyMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, POLICY_MAP);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
