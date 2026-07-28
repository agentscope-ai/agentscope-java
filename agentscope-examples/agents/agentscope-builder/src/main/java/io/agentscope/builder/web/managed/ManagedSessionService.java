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

import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.persistence.jpa.AgentEntity;
import io.agentscope.builder.web.persistence.jpa.AgentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.EnvironmentEntity;
import io.agentscope.builder.web.persistence.jpa.EnvironmentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntity;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntityRepository;
import io.agentscope.builder.web.persistence.jpa.MemoryStoreEntityRepository;
import io.agentscope.builder.web.persistence.jpa.VaultEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Lifecycle operations for managed agent sessions. */
@Service
@Transactional
public class ManagedSessionService {

    public static final String STATUS_CREATED = "created";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_REQUIRES_ACTION = "requires_action";

    /** Unrecoverable turn failure (Claude {@code terminated}). */
    public static final String STATUS_TERMINATED = "terminated";

    public static final String STATUS_RESCHEDULED = "rescheduled";
    public static final String STATUS_ARCHIVED = "archived";

    public static final String REF_LATEST = "latest";
    public static final String REF_PINNED = "pinned";
    public static final String REF_OVERRIDES = "overrides";

    /** Request body for creating a managed session. */
    public record CreateSessionRequest(
            Object agent,
            String environmentId,
            List<String> memoryStoreIds,
            List<String> vaultIds,
            List<Map<String, Object>> resources) {
        /** Backward-compatible 4-arg form used by deployments / IM bridge. */
        public CreateSessionRequest(
                Object agent,
                String environmentId,
                List<String> memoryStoreIds,
                List<String> vaultIds) {
            this(agent, environmentId, memoryStoreIds, vaultIds, null);
        }
    }

    private final ManagedSessionEntityRepository repository;
    private final EnvironmentEntityRepository environmentRepository;
    private final AgentEntityRepository agentRepository;
    private final MemoryStoreEntityRepository memoryStoreRepository;
    private final VaultEntityRepository vaultRepository;
    private final AgentCatalogService catalogService;
    private final AgentVersionService versionService;
    private final SessionEventLog eventLog;
    private final ManagedJsonHelper jsonHelper;
    private final SessionTurnRunner turnRunner;
    private final ResourceAccessService resourceAccessService;

    public ManagedSessionService(
            ManagedSessionEntityRepository repository,
            EnvironmentEntityRepository environmentRepository,
            AgentEntityRepository agentRepository,
            MemoryStoreEntityRepository memoryStoreRepository,
            VaultEntityRepository vaultRepository,
            AgentCatalogService catalogService,
            AgentVersionService versionService,
            SessionEventLog eventLog,
            ManagedJsonHelper jsonHelper,
            SessionTurnRunner turnRunner,
            ResourceAccessService resourceAccessService) {
        this.repository = repository;
        this.environmentRepository = environmentRepository;
        this.agentRepository = agentRepository;
        this.memoryStoreRepository = memoryStoreRepository;
        this.vaultRepository = vaultRepository;
        this.catalogService = catalogService;
        this.versionService = versionService;
        this.eventLog = eventLog;
        this.jsonHelper = jsonHelper;
        this.turnRunner = turnRunner;
        this.resourceAccessService = resourceAccessService;
    }

    /** Creates a session after validating agent, environment, and mount references. */
    public ManagedSessionDto create(String ownerId, CreateSessionRequest request) {
        AgentRef agentRef = parseAgentRef(request.agent());
        AgentDefinition agentDef =
                catalogService
                        .findVisible(ownerId, agentRef.agentId())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentRef.agentId()));
        assertAgentNotArchived(agentDef);
        EnvironmentEntity environment = requireActiveEnvironment(ownerId, request.environmentId());
        validateMounts(ownerId, request.memoryStoreIds(), request.vaultIds());

        Integer resolvedVersion = resolveVersion(agentDef, agentRef);
        long now = System.currentTimeMillis();
        ManagedSessionEntity entity = new ManagedSessionEntity();
        entity.setSessionId(ManagedJsonHelper.randomId("ses_"));
        entity.setOwnerId(ownerId);
        entity.setAgentId(agentRef.agentId());
        entity.setAgentOwnerId(agentDef.ownerId());
        entity.setAgentVersion(resolvedVersion);
        entity.setAgentRefType(agentRef.refType());
        if (agentRef.overridesJson() != null) {
            entity.setAgentOverridesJson(agentRef.overridesJson());
        }
        entity.setEnvironmentId(environment.getEnvironmentId());
        entity.setMemoryStoreIdsJson(jsonHelper.writeJson(request.memoryStoreIds()));
        entity.setVaultIdsJson(jsonHelper.writeJson(request.vaultIds()));
        entity.setResourcesJson(jsonHelper.writeJson(request.resources()));
        entity.setStatus(STATUS_CREATED);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        ManagedSessionDto dto = toDto(repository.save(entity));
        eventLog.append(
                entity.getSessionId(), "session.status_created", Map.of("status", STATUS_CREATED));
        return dto;
    }

    /** Returns a session owned by the caller. */
    @Transactional(readOnly = true)
    public ManagedSessionDto get(String ownerId, String sessionId) {
        return toDto(requireOwned(ownerId, sessionId));
    }

    /** Looks up a session by id without owner scoping (worker / internal use). */
    public ManagedSessionDto requireById(String sessionId) {
        return toDto(
                repository
                        .findBySessionId(sessionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Session not found: " + sessionId)));
    }

    /** Lists sessions for an owner, optionally filtered by agent id. */
    @Transactional(readOnly = true)
    public List<ManagedSessionDto> list(String ownerId, String agentId) {
        List<ManagedSessionEntity> rows =
                agentId == null || agentId.isBlank()
                        ? repository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                        : repository.findByOwnerIdAndAgentIdOrderByCreatedAtDesc(ownerId, agentId);
        return rows.stream().map(this::toDto).toList();
    }

    /** Archives a session and records the terminal status. */
    public ManagedSessionDto archive(String ownerId, String sessionId) {
        ManagedSessionEntity entity = requireOwned(ownerId, sessionId);
        if (entity.getArchivedAt() != null) {
            return toDto(entity);
        }
        long now = System.currentTimeMillis();
        entity.setArchivedAt(now);
        entity.setUpdatedAt(now);
        entity.setStatus(STATUS_ARCHIVED);
        ManagedSessionDto dto = toDto(repository.save(entity));
        eventLog.append(sessionId, "session.status_archived", Map.of("status", STATUS_ARCHIVED));
        return dto;
    }

    /** Hard-deletes a session and its event log. */
    public void delete(String ownerId, String sessionId) {
        requireOwned(ownerId, sessionId);
        eventLog.append(sessionId, SessionEventTypes.SESSION_DELETED, Map.of("status", "deleted"));
        eventLog.deleteBySessionId(sessionId);
        repository.findBySessionId(sessionId).ifPresent(repository::delete);
    }

    /**
     * Merges session-scoped agent overrides (e.g. {@code system.message}) without writing back to
     * the Agent resource. Takes effect on the next turn.
     */
    public ManagedSessionDto mergeAgentOverrides(
            String ownerId, String sessionId, Map<String, Object> patch) {
        ManagedSessionEntity entity = requireOwned(ownerId, sessionId);
        Map<String, Object> current = jsonHelper.readMap(entity.getAgentOverridesJson());
        if (current == null) {
            current = new LinkedHashMap<>();
        } else {
            current = new LinkedHashMap<>(current);
        }
        if (patch != null) {
            current.putAll(patch);
        }
        entity.setAgentOverridesJson(jsonHelper.writeJson(current));
        entity.setUpdatedAt(System.currentTimeMillis());
        ManagedSessionDto dto = toDto(repository.save(entity));
        eventLog.append(sessionId, SessionEventTypes.SESSION_UPDATED, Map.of("overrides", current));
        return dto;
    }

    /** Updates session status and optional stop reason metadata. */
    public ManagedSessionDto updateStatus(
            String ownerId, String sessionId, String status, Map<String, Object> stopReason) {
        ManagedSessionEntity entity = requireOwned(ownerId, sessionId);
        entity.setStatus(status);
        entity.setStopReasonJson(jsonHelper.writeJson(stopReason));
        entity.setUpdatedAt(System.currentTimeMillis());
        ManagedSessionDto dto = toDto(repository.save(entity));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        if (stopReason != null) {
            payload.put("stopReason", stopReason);
        }
        eventLog.append(sessionId, "session.status_" + status, payload);
        return dto;
    }

    /** Runs one harness turn for the session asynchronously. */
    public void runTurn(String ownerId, String sessionId, Map<String, Object> messagePayload) {
        ManagedSessionDto session = get(ownerId, sessionId);
        String userMessage = extractUserMessage(messagePayload);
        // Turn lease is acquired inside runTurnAsync on this thread; CONFLICT throws before
        // status flips to running.
        turnRunner.runTurnAsync(session, userMessage);
    }

    private static String extractUserMessage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        for (String key : List.of("text", "message", "content")) {
            Object value = payload.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return String.valueOf(payload);
    }

    private ManagedSessionEntity requireOwned(String ownerId, String sessionId) {
        ManagedSessionEntity entity =
                repository
                        .findBySessionId(sessionId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Session not found: " + sessionId));
        if (!ownerId.equals(entity.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Session access denied");
        }
        return entity;
    }

    private EnvironmentEntity requireActiveEnvironment(String ownerId, String environmentId) {
        EnvironmentEntity environment =
                environmentRepository
                        .findByEnvironmentId(environmentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Environment not found: " + environmentId));
        resourceAccessService.require(
                ownerId,
                environment.getOwnerId(),
                EnvironmentService.RESOURCE_TYPE,
                environmentId,
                Tier.RUN);
        if (environment.getArchivedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Environment is archived: " + environmentId);
        }
        return environment;
    }

    private void assertAgentNotArchived(AgentDefinition agentDef) {
        if (agentDef.ownerId() == null) {
            return;
        }
        agentRepository
                .findByOwnerIdAndAgentId(agentDef.ownerId(), agentDef.id())
                .ifPresent(
                        entity -> {
                            if (entity.getArchivedAt() != null) {
                                throw new ResponseStatusException(
                                        HttpStatus.CONFLICT, "Agent is archived: " + agentDef.id());
                            }
                        });
    }

    private void validateMounts(
            String ownerId, List<String> memoryStoreIds, List<String> vaultIds) {
        if (memoryStoreIds != null) {
            for (String storeId : memoryStoreIds) {
                var store =
                        memoryStoreRepository
                                .findByStoreId(storeId)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Memory store not found: " + storeId));
                resourceAccessService.require(
                        ownerId,
                        store.getOwnerId(),
                        MemoryStoreService.RESOURCE_TYPE,
                        storeId,
                        Tier.RUN);
            }
        }
        if (vaultIds != null) {
            for (String vaultId : vaultIds) {
                var vault =
                        vaultRepository
                                .findByVaultId(vaultId)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "Vault not found: " + vaultId));
                resourceAccessService.require(
                        ownerId, vault.getOwnerId(), VaultService.RESOURCE_TYPE, vaultId, Tier.RUN);
            }
        }
    }

    private Integer resolveVersion(AgentDefinition agentDef, AgentRef agentRef) {
        if (REF_PINNED.equals(agentRef.refType()) || REF_OVERRIDES.equals(agentRef.refType())) {
            if (agentRef.version() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Pinned/overrides agent reference requires version");
            }
            String versionOwner =
                    agentDef.ownerId() != null
                            ? agentDef.ownerId()
                            : AgentVersionService.GLOBAL_OWNER;
            versionService.getVersion(versionOwner, agentDef.id(), agentRef.version());
            return agentRef.version();
        }
        if (agentDef.ownerId() == null) {
            // Global agent, unpinned reference: head is materialized (and kept fresh) by
            // AgentCatalogService#globalDefinitions via AgentVersionService#ensureGlobalVersion.
            return agentDef.version();
        }
        return agentRepository
                .findByOwnerIdAndAgentId(agentDef.ownerId(), agentDef.id())
                .map(AgentEntity::getHeadVersion)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private AgentRef parseAgentRef(Object agent) {
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent is required");
        }
        if (agent instanceof String agentId) {
            if (agentId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent id is required");
            }
            return new AgentRef(REF_LATEST, agentId, null, null);
        }
        if (agent instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String type = stringValue(map.get("type"));
            String id = stringValue(map.get("id"));
            if (id == null || id.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent.id is required");
            }
            if ("agent".equals(type)) {
                Integer version = intValue(map.get("version"));
                return new AgentRef(REF_PINNED, id, version, null);
            }
            if ("agent_with_overrides".equals(type)) {
                Integer version = intValue(map.get("version"));
                Map<String, Object> overrides = new LinkedHashMap<>(map);
                overrides.remove("type");
                overrides.remove("id");
                return new AgentRef(REF_OVERRIDES, id, version, jsonHelper.writeJson(overrides));
            }
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "agent.type must be 'agent' or 'agent_with_overrides' when agent is an object");
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "agent must be a string id or an object reference");
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private ManagedSessionDto toDto(ManagedSessionEntity entity) {
        return new ManagedSessionDto(
                entity.getSessionId(),
                entity.getOwnerId(),
                entity.getAgentId(),
                entity.getAgentOwnerId(),
                entity.getAgentVersion(),
                entity.getAgentRefType(),
                entity.getAgentOverridesJson(),
                entity.getEnvironmentId(),
                jsonHelper.readStringList(entity.getMemoryStoreIdsJson()),
                jsonHelper.readStringList(entity.getVaultIdsJson()),
                jsonHelper.readObjectList(entity.getResourcesJson()),
                entity.getStatus(),
                jsonHelper.readMap(entity.getStopReasonJson()),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getArchivedAt());
    }

    private record AgentRef(
            String refType, String agentId, Integer version, String overridesJson) {}
}
