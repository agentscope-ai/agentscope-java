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

import io.agentscope.builder.web.persistence.jpa.EnvironmentEntity;
import io.agentscope.builder.web.persistence.jpa.EnvironmentEntityRepository;
import io.agentscope.builder.web.persistence.jpa.ManagedSessionEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** CRUD and lifecycle operations for execution environment templates. */
@Service
@Transactional
public class EnvironmentService {

    public static final String TYPE_LOCAL = "local";
    public static final String TYPE_SANDBOX = "sandbox";
    public static final String TYPE_REMOTE = "remote";

    /**
     * Remote-worker variant: the agent's filesystem/state still run against the shared remote
     * {@link io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec} store (same wiring
     * as {@link #TYPE_REMOTE}), but the config is marked {@code selfHosted=true} so operators can
     * distinguish "runs on our own remote worker fleet" environments from the platform-managed
     * remote default when listing/filtering environments.
     */
    public static final String TYPE_SELF_HOSTED = "self_hosted";

    /** Resource-type key used for {@link ResourceAccessService} share grants. */
    public static final String RESOURCE_TYPE = "environment";

    private static final Set<String> ALLOWED_TYPES =
            Set.of(TYPE_LOCAL, TYPE_SANDBOX, TYPE_REMOTE, TYPE_SELF_HOSTED);
    private static final Set<String> ALLOWED_ISOLATION_SCOPES =
            Set.of("SESSION", "USER", "AGENT", "GLOBAL");

    private final EnvironmentEntityRepository repository;
    private final ManagedSessionEntityRepository sessionRepository;
    private final ManagedJsonHelper jsonHelper;
    private final ResourceAccessService resourceAccessService;

    public EnvironmentService(
            EnvironmentEntityRepository repository,
            ManagedSessionEntityRepository sessionRepository,
            ManagedJsonHelper jsonHelper,
            ResourceAccessService resourceAccessService) {
        this.repository = repository;
        this.sessionRepository = sessionRepository;
        this.jsonHelper = jsonHelper;
        this.resourceAccessService = resourceAccessService;
    }

    /** Request body for creating an environment. */
    public record CreateEnvironmentRequest(String name, String type, Map<String, Object> config) {}

    /** Lists non-archived environments owned by the user. */
    @Transactional(readOnly = true)
    public List<EnvironmentDto> list(String ownerId) {
        return repository.findByOwnerIdAndArchivedAtIsNullOrderByCreatedAtAsc(ownerId).stream()
                .map(this::toDto)
                .toList();
    }

    /** Returns a single environment when owned by, or shared (at least RUN) with, the caller. */
    @Transactional(readOnly = true)
    public EnvironmentDto get(String ownerId, String environmentId) {
        return toDto(requireAccess(ownerId, environmentId, Tier.RUN));
    }

    /** Creates a new environment template. */
    public EnvironmentDto create(String ownerId, CreateEnvironmentRequest request) {
        validateCreateRequest(request);
        if (repository.existsByOwnerIdAndName(ownerId, request.name())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Environment name already exists: " + request.name());
        }
        long now = System.currentTimeMillis();
        EnvironmentEntity entity = new EnvironmentEntity();
        entity.setEnvironmentId(ManagedJsonHelper.randomId("env_"));
        entity.setOwnerId(ownerId);
        entity.setName(request.name());
        entity.setType(request.type());
        entity.setConfigJson(
                jsonHelper.writeJson(normalizeConfig(request.type(), request.config())));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toDto(repository.save(entity));
    }

    /** Archives an environment (soft delete). */
    public EnvironmentDto archive(String ownerId, String environmentId) {
        EnvironmentEntity entity = requireAccess(ownerId, environmentId, Tier.EDIT);
        if (entity.getArchivedAt() != null) {
            return toDto(entity);
        }
        long now = System.currentTimeMillis();
        entity.setArchivedAt(now);
        entity.setUpdatedAt(now);
        return toDto(repository.save(entity));
    }

    /** Hard-deletes an environment when no active sessions reference it. */
    public void delete(String ownerId, String environmentId) {
        requireAccess(ownerId, environmentId, Tier.EDIT);
        long active = sessionRepository.countByEnvironmentIdAndArchivedAtIsNull(environmentId);
        if (active > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Environment is referenced by "
                            + active
                            + " active session(s); archive sessions first");
        }
        repository.findByEnvironmentId(environmentId).ifPresent(repository::delete);
    }

    /** Lists share grants on an environment. Owner/EDIT-tier only. */
    @Transactional(readOnly = true)
    public List<ResourceShareDto> listShares(String ownerId, String environmentId) {
        requireAccess(ownerId, environmentId, Tier.EDIT);
        return resourceAccessService.listShares(RESOURCE_TYPE, environmentId);
    }

    /** Shares an environment with a user or the whole workspace. Owner/EDIT-tier only. */
    public ResourceShareDto share(
            String ownerId, String environmentId, String granteeType, String granteeId, Tier tier) {
        EnvironmentEntity entity = requireAccess(ownerId, environmentId, Tier.EDIT);
        return resourceAccessService.share(
                RESOURCE_TYPE,
                environmentId,
                entity.getOwnerId(),
                granteeType,
                granteeId,
                tier,
                ownerId);
    }

    /** Revokes a share grant on an environment. Owner/EDIT-tier only. */
    public void unshare(String ownerId, String environmentId, String shareId) {
        requireAccess(ownerId, environmentId, Tier.EDIT);
        resourceAccessService.unshare(RESOURCE_TYPE, environmentId, shareId);
    }

    /**
     * Ensures a default environment exists for the owner, creating {@code default-local} or
     * {@code default-remote} when missing.
     */
    public EnvironmentDto ensureDefaultEnvironment(String ownerId, String preferredType) {
        String type = TYPE_REMOTE.equalsIgnoreCase(preferredType) ? TYPE_REMOTE : TYPE_LOCAL;
        String defaultName = TYPE_REMOTE.equals(type) ? "default-remote" : "default-local";
        return repository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .filter(e -> defaultName.equals(e.getName()) && e.getArchivedAt() == null)
                .findFirst()
                .map(this::toDto)
                .orElseGet(
                        () ->
                                create(
                                        ownerId,
                                        new CreateEnvironmentRequest(defaultName, type, Map.of())));
    }

    /** Resolves the environment and verifies {@code callerId} holds at least {@code required}. */
    private EnvironmentEntity requireAccess(String callerId, String environmentId, Tier required) {
        EnvironmentEntity entity =
                repository
                        .findByEnvironmentId(environmentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Environment not found: " + environmentId));
        resourceAccessService.require(
                callerId, entity.getOwnerId(), RESOURCE_TYPE, environmentId, required);
        return entity;
    }

    private void validateCreateRequest(CreateEnvironmentRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (request.type() == null || !ALLOWED_TYPES.contains(request.type())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "type must be one of: local, sandbox, remote, self_hosted");
        }
        normalizeConfig(request.type(), request.config());
    }

    private Map<String, Object> normalizeConfig(String type, Map<String, Object> config) {
        if (TYPE_SELF_HOSTED.equals(type)) {
            Map<String, Object> normalized =
                    config != null ? new HashMap<>(config) : new HashMap<>();
            normalized.putIfAbsent("selfHosted", Boolean.TRUE);
            return normalized;
        }
        if (config == null || config.isEmpty()) {
            return config;
        }
        if (!TYPE_SANDBOX.equals(type)) {
            return config;
        }
        Map<String, Object> normalized = new HashMap<>(config);
        Object scope = normalized.get("isolationScope");
        if (scope != null && !ALLOWED_ISOLATION_SCOPES.contains(String.valueOf(scope))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "isolationScope must be one of: SESSION, USER, AGENT, GLOBAL");
        }
        return normalized;
    }

    private EnvironmentDto toDto(EnvironmentEntity entity) {
        return new EnvironmentDto(
                entity.getEnvironmentId(),
                entity.getName(),
                entity.getType(),
                jsonHelper.readMap(entity.getConfigJson()),
                entity.getOwnerId(),
                entity.getArchivedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
