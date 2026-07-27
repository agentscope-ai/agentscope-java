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
import io.agentscope.builder.web.managed.ManagedSessionService.CreateSessionRequest;
import io.agentscope.builder.web.persistence.jpa.DeploymentEntity;
import io.agentscope.builder.web.persistence.jpa.DeploymentEntityRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * CRUD and firing logic for deployments: an agent + version + environment bound to a trigger
 * ({@code cron}, {@code webhook}, or {@code manual}). Firing a deployment creates a fresh managed
 * session and runs one turn against it, so every run gets its own transcript.
 */
@Service
@Transactional
public class DeploymentService {

    public static final String TRIGGER_CRON = "cron";
    public static final String TRIGGER_WEBHOOK = "webhook";
    public static final String TRIGGER_MANUAL = "manual";

    private static final Set<String> ALLOWED_TRIGGER_TYPES =
            Set.of(TRIGGER_CRON, TRIGGER_WEBHOOK, TRIGGER_MANUAL);

    private static final String DEFAULT_RUN_PROMPT = "Run the configured task for this deployment.";

    /** Request body for creating a deployment. */
    public record CreateDeploymentRequest(
            String name,
            String agentId,
            Integer agentVersion,
            String environmentId,
            String triggerType,
            String cronExpression) {}

    /** Request body for updating a deployment's mutable fields. */
    public record UpdateDeploymentRequest(
            String name,
            Boolean enabled,
            String cronExpression,
            String environmentId,
            Integer agentVersion) {}

    private final DeploymentEntityRepository repository;
    private final AgentCatalogService catalogService;
    private final EnvironmentService environmentService;
    private final ManagedSessionService sessionService;

    public DeploymentService(
            DeploymentEntityRepository repository,
            AgentCatalogService catalogService,
            EnvironmentService environmentService,
            @Lazy ManagedSessionService sessionService) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.environmentService = environmentService;
        this.sessionService = sessionService;
    }

    /** Creates a deployment after validating the agent, environment, and trigger config. */
    public DeploymentDto create(String ownerId, CreateDeploymentRequest request) {
        String name = requireNonBlank(request.name(), "name");
        String agentId = requireNonBlank(request.agentId(), "agentId");
        catalogService
                .findVisible(ownerId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
        String triggerType = requireValidTriggerType(request.triggerType());
        String environmentId = resolveEnvironmentId(ownerId, request.environmentId());

        long now = System.currentTimeMillis();
        DeploymentEntity entity = new DeploymentEntity();
        entity.setDeploymentId(ManagedJsonHelper.randomId("dep_"));
        entity.setOwnerId(ownerId);
        entity.setName(name);
        entity.setAgentId(agentId);
        entity.setAgentVersion(request.agentVersion());
        entity.setEnvironmentId(environmentId);
        entity.setTriggerType(triggerType);
        if (TRIGGER_CRON.equals(triggerType)) {
            entity.setCronExpression(requireValidCron(request.cronExpression()));
        } else if (TRIGGER_WEBHOOK.equals(triggerType)) {
            entity.setWebhookToken(ManagedJsonHelper.randomId("whk_"));
        }
        entity.setEnabled(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toDto(repository.save(entity));
    }

    /** Lists non-archived deployments owned by the caller. */
    @Transactional(readOnly = true)
    public List<DeploymentDto> list(String ownerId) {
        return repository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(DeploymentService::toDto)
                .toList();
    }

    /** Returns a single deployment owned by the caller. */
    @Transactional(readOnly = true)
    public DeploymentDto get(String ownerId, String deploymentId) {
        return toDto(requireOwned(ownerId, deploymentId));
    }

    /** Updates a deployment's name, enabled flag, cron expression, environment, or version. */
    public DeploymentDto update(
            String ownerId, String deploymentId, UpdateDeploymentRequest request) {
        DeploymentEntity entity = requireOwned(ownerId, deploymentId);
        if (request.name() != null) {
            entity.setName(requireNonBlank(request.name(), "name"));
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.cronExpression() != null) {
            if (!TRIGGER_CRON.equals(entity.getTriggerType())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "cronExpression can only be set on cron-triggered deployments");
            }
            entity.setCronExpression(requireValidCron(request.cronExpression()));
        }
        if (request.environmentId() != null) {
            entity.setEnvironmentId(resolveEnvironmentId(ownerId, request.environmentId()));
        }
        if (request.agentVersion() != null) {
            entity.setAgentVersion(request.agentVersion());
        }
        entity.setUpdatedAt(System.currentTimeMillis());
        return toDto(repository.save(entity));
    }

    /** Archives a deployment and disables further scheduled firing. */
    public DeploymentDto archive(String ownerId, String deploymentId) {
        DeploymentEntity entity = requireOwned(ownerId, deploymentId);
        if (entity.getArchivedAt() != null) {
            return toDto(entity);
        }
        long now = System.currentTimeMillis();
        entity.setArchivedAt(now);
        entity.setEnabled(false);
        entity.setUpdatedAt(now);
        return toDto(repository.save(entity));
    }

    /** Hard-deletes a deployment. */
    public void delete(String ownerId, String deploymentId) {
        DeploymentEntity entity = requireOwned(ownerId, deploymentId);
        repository.delete(entity);
    }

    /** Manually fires a deployment owned by the caller. */
    public DeploymentDto run(
            String ownerId, String deploymentId, Map<String, Object> messagePayload) {
        DeploymentEntity entity = requireOwned(ownerId, deploymentId);
        if (entity.getArchivedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Deployment is archived: " + deploymentId);
        }
        return fire(entity, messagePayload);
    }

    /** Fires a deployment via its webhook token; validates the token but requires no JWT. */
    public DeploymentDto runByWebhookToken(String token, Map<String, Object> messagePayload) {
        DeploymentEntity entity =
                repository
                        .findByWebhookToken(token)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Unknown webhook token"));
        if (entity.getArchivedAt() != null || !entity.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Deployment is disabled or archived");
        }
        return fire(entity, messagePayload);
    }

    /**
     * Fires every enabled, non-archived cron deployment whose next scheduled fire time has
     * passed. Called by {@link DeploymentScheduler} roughly once a minute.
     */
    public List<DeploymentDto> fireDueCronDeployments() {
        List<DeploymentDto> fired = new ArrayList<>();
        for (DeploymentEntity entity :
                repository.findByTriggerTypeAndEnabledTrueAndArchivedAtIsNull(TRIGGER_CRON)) {
            if (isCronDue(entity)) {
                try {
                    fired.add(fire(entity, null));
                } catch (Exception ex) {
                    entity.setLastStatus("errored");
                    entity.setUpdatedAt(System.currentTimeMillis());
                    repository.save(entity);
                }
            }
        }
        return fired;
    }

    private boolean isCronDue(DeploymentEntity entity) {
        if (entity.getCronExpression() == null || entity.getCronExpression().isBlank()) {
            return false;
        }
        try {
            CronExpression cron = CronExpression.parse(entity.getCronExpression());
            long anchorMs =
                    entity.getLastRunAt() != null ? entity.getLastRunAt() : entity.getCreatedAt();
            LocalDateTime anchor =
                    Instant.ofEpochMilli(anchorMs).atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime next = cron.next(anchor);
            return next != null && !next.isAfter(LocalDateTime.now());
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private DeploymentDto fire(DeploymentEntity entity, Map<String, Object> messagePayload) {
        Object agentRef =
                entity.getAgentVersion() != null
                        ? Map.of(
                                "type", "agent",
                                "id", entity.getAgentId(),
                                "version", entity.getAgentVersion())
                        : entity.getAgentId();
        CreateSessionRequest req =
                new CreateSessionRequest(agentRef, entity.getEnvironmentId(), null, null);
        ManagedSessionDto session = sessionService.create(entity.getOwnerId(), req);

        Map<String, Object> payload =
                messagePayload != null && !messagePayload.isEmpty()
                        ? messagePayload
                        : Map.of("text", DEFAULT_RUN_PROMPT);
        sessionService.runTurn(entity.getOwnerId(), session.id(), payload);

        long now = System.currentTimeMillis();
        entity.setLastRunAt(now);
        entity.setLastSessionId(session.id());
        entity.setLastStatus(ManagedSessionService.STATUS_RUNNING);
        entity.setUpdatedAt(now);
        return toDto(repository.save(entity));
    }

    private String resolveEnvironmentId(String ownerId, String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            return environmentService.ensureDefaultEnvironment(ownerId, null).id();
        }
        EnvironmentDto environment = environmentService.get(ownerId, environmentId);
        if (environment.archivedAt() != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Environment is archived: " + environmentId);
        }
        return environment.id();
    }

    private DeploymentEntity requireOwned(String ownerId, String deploymentId) {
        DeploymentEntity entity =
                repository
                        .findByDeploymentId(deploymentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Deployment not found: " + deploymentId));
        if (!ownerId.equals(entity.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Deployment access denied");
        }
        return entity;
    }

    private static String requireValidTriggerType(String triggerType) {
        if (triggerType == null || !ALLOWED_TRIGGER_TYPES.contains(triggerType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "triggerType must be one of: cron, webhook, manual");
        }
        return triggerType;
    }

    private static String requireValidCron(String cronExpression) {
        String trimmed = requireNonBlank(cronExpression, "cronExpression");
        if (!CronExpression.isValidExpression(trimmed)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid cron expression: " + trimmed);
        }
        return trimmed;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    private static DeploymentDto toDto(DeploymentEntity entity) {
        return new DeploymentDto(
                entity.getDeploymentId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getAgentId(),
                entity.getAgentVersion(),
                entity.getEnvironmentId(),
                entity.getTriggerType(),
                entity.getCronExpression(),
                entity.getWebhookToken(),
                entity.isEnabled(),
                entity.getLastRunAt(),
                entity.getLastSessionId(),
                entity.getLastStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getArchivedAt());
    }
}
