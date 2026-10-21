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
package io.agentscope.builder.portal.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.portal.persistence.PortalSessionEntity;
import io.agentscope.builder.portal.persistence.PortalSessionRepository;
import io.agentscope.builder.runtime.managed.ManagedAdmissionStore;
import io.agentscope.builder.runtime.managed.ManagedScope;
import io.agentscope.builder.runtime.protocol.RuntimeBootstrapDigest;
import io.agentscope.builder.runtime.protocol.RuntimeSessionAdmission;
import io.agentscope.builder.runtime.protocol.RuntimeSessionBootstrap;
import io.agentscope.builder.runtime.protocol.RuntimeSessionReady;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.SessionEventCursor;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** A local registry transaction followed by a retryable remote commit, without a distributed transaction. */
@Service
public class PortalSessionProvisioning {
    @Value("${builder.user-files-enabled:${BUILDER_USER_FILES_ENABLED:true}}")
    private boolean userFilesEnabled = true;

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);
    private final PortalSessionRepository repository;
    private final TransactionTemplate tx;
    private final VaultCrypto crypto;
    private final ObjectMapper mapper;
    private final PortalRuntimeBackends backends;
    private final String internalToken;
    private final PortalSessionPermissions permissions;
    private final PortalPolicies policies;
    private final ManagedAdmissionStore managed;
    private final EntityManager entityManager;

    public PortalSessionProvisioning(
            PortalSessionRepository repository,
            TransactionTemplate tx,
            VaultCrypto crypto,
            ObjectMapper mapper,
            PortalSessionPermissions permissions,
            PortalPolicies policies,
            PortalRuntimeBackends backends,
            ManagedAdmissionStore managed,
            EntityManager entityManager,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        this.repository = repository;
        this.tx = tx;
        this.crypto = crypto;
        this.mapper = mapper;
        this.permissions = permissions;
        this.policies = policies;
        this.managed = managed;
        this.entityManager = entityManager;
        this.backends = backends;
        this.internalToken = internalToken;
    }

    public Map<String, Object> provision(RuntimeSessionAdmission admission) {
        backends.kind(admission.bootstrap().runtimeKind());
        stage(admission);
        deliver(admission.bootstrap().context().sessionId());
        return status(admission.bootstrap().context().sessionId());
    }

    public void stage(RuntimeSessionAdmission admission) {
        var request = admission.bootstrap();
        var context = request.context();
        backends.kind(request.runtimeKind());
        String digest = RuntimeBootstrapDigest.of(mapper, request);
        var existing = repository.findById(context.sessionId());
        if (existing.isPresent()) {
            checkReplay(existing.get(), request, digest);
            return;
        }
        if (context.permissionExpiresAt() <= System.currentTimeMillis())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admission permission expired");
        if (!context.tenant().equals(admission.policy().get("tenant"))
                || !context.namespace().equals(admission.policy().get("namespace"))
                || !context.sessionId().equals(admission.publicView().get("id")))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Admission identity mismatch");
        var row = new PortalSessionEntity();
        row.id = context.sessionId();
        row.tenant = context.tenant();
        row.namespace = context.namespace();
        row.ownerId = request.ownerId();
        row.applicationId = context.applicationId();
        row.runtimeKind = request.runtimeKind();
        row.commandId = context.commandId();
        row.revision = request.configurationRevision();
        row.digest = digest;
        row.state = "provisioning";
        row.lifecycle = "active";
        row.policy = crypto.encrypt(json(admission.policy()));
        var view = new LinkedHashMap<String, Object>(admission.publicView());
        view.put("environmentId", request.environmentId());
        row.publicView = json(view);
        row.bootstrapPayload = crypto.encrypt(json(request));
        row.createdAt = System.currentTimeMillis();
        row.updatedAt = row.createdAt;
        row.policyId = policies.prepare(row, admission.policy(), request.configuration());
        try {
            tx.executeWithoutResult(
                    ignored -> {
                        // Registration is insert-only: merge could overwrite a concurrent winner's
                        // random ciphertext and make the already registered ma configuration
                        // differ.
                        entityManager.persist(row);
                        repository.flush();
                        permissions.replace(row, admission.policy());
                    });
        } catch (DataIntegrityViolationException race) {
            var winner =
                    repository
                            .findById(row.id)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Bootstrap command belongs to another"
                                                            + " Session"));
            checkReplay(winner, request, digest);
        }
    }

    public Map<String, Object> status(String id) {
        var row = require(id);
        return Map.of(
                "id",
                row.id,
                "state",
                row.state,
                "runtimeKind",
                row.runtimeKind,
                "configurationRevision",
                row.revision,
                "failureCode",
                row.failureCode == null ? "" : row.failureCode);
    }

    public Map<String, Object> readyView(String id) {
        var row = require(id);
        if ("managed".equals(row.runtimeKind)) {
            registerManaged(row);
            return view(require(id));
        }
        if (!List.of("ready", "checkpointing").contains(row.state))
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Session provisioning has not completed");
        return view(row);
    }

    /** Managed admission saves the fixed encrypted configuration; no Brain instance is contacted. */
    private void registerManaged(PortalSessionEntity row) {
        var scope = new ManagedScope(row.tenant, row.namespace, row.id);
        if ("created".equals(row.state)) {
            managed.session(scope);
            return;
        }
        managed.createSession(
                scope,
                json(
                        Map.of(
                                "configuration_revision",
                                row.revision,
                                "definition_digest",
                                row.digest,
                                "encrypted_definition",
                                row.bootstrapPayload,
                                "owner_id",
                                row.ownerId,
                                "application_id",
                                row.applicationId == null ? "" : row.applicationId,
                                "root_session_id",
                                row.rootSessionId == null ? "" : row.rootSessionId)));
        tx.executeWithoutResult(
                ignored -> {
                    var current = repository.lockById(row.id).orElseThrow();
                    current.state = "created";
                    current.claimToken = null;
                    current.claimUntil = 0;
                    current.failureCode = null;
                    current.updatedAt = System.currentTimeMillis();
                    repository.saveAndFlush(current);
                });
    }

    public Map<String, Object> capabilities(String id) {
        var row = require(id);
        boolean managed = "managed".equals(row.runtimeKind);
        return Map.of(
                "sessionId",
                id,
                "runtime_kind",
                row.runtimeKind,
                "configuration_revision",
                row.revision,
                "capabilities",
                Map.of(
                        "files",
                        managed && userFilesEnabled,
                        "file_input",
                        managed && userFilesEnabled,
                        "artifacts",
                        userFilesEnabled,
                        "budget",
                        managed,
                        "checkpoints",
                        managed,
                        "checkpoint_restore",
                        managed && row.rootSessionId == null,
                        "fork",
                        managed && row.rootSessionId == null));
    }

    public Map<String, Object> view(PortalSessionEntity row) {
        var result = new LinkedHashMap<String, Object>(read(row.publicView, Map.class));
        result.put("status", row.lifecycle);
        result.put("lifecycle_revision", row.lifecycleRevision);
        result.put("state", row.state);
        result.put("createdAt", row.createdAt);
        result.put("updatedAt", row.updatedAt);
        result.put("turnCount", row.nextTurnSequence);
        result.put("cursor", SessionEventCursor.encode(row.id, row.nextEventSequence));
        result.put("usage", read(row.usageSummary, Map.class));
        return result;
    }

    @Scheduled(
            fixedDelayString = "${builder.portal.provisioning-poll-ms:1000}",
            initialDelayString = "${builder.portal.provisioning-poll-ms:1000}",
            scheduler = "portalProvisioningScheduler")
    public void retryPending() {
        var due = repository.findDue(System.currentTimeMillis(), PageRequest.of(0, 8));
        Flux.fromIterable(due)
                .flatMap(
                        id ->
                                Mono.fromRunnable(() -> deliver(id))
                                        .subscribeOn(Schedulers.boundedElastic()),
                        4)
                .blockLast();
    }

    public void deliver(String id) {
        var resource = require(id);
        if ("managed".equals(resource.runtimeKind)) {
            registerManaged(resource);
            return;
        }
        var claim =
                tx.execute(
                        ignored -> {
                            var row = repository.lockById(id).orElseThrow();
                            long now = System.currentTimeMillis();
                            if (!"provisioning".equals(row.state)
                                    || row.claimUntil > now
                                    || row.nextAttemptAt > now) return null;
                            row.claimToken = UUID.randomUUID().toString();
                            row.claimUntil = now + 15000;
                            row.attemptCount++;
                            repository.saveAndFlush(row);
                            return new Claim(
                                    row.id, row.claimToken, row.bootstrapPayload, row.attemptCount);
                        });
        if (claim == null) return;
        try {
            var request = read(crypto.decrypt(claim.payload()), RuntimeSessionBootstrap.class);
            var ready =
                    backends.kind(request.runtimeKind())
                            .post()
                            .uri("/api/internal/runtime/sessions/bootstrap")
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken)
                            .bodyValue(request)
                            .retrieve()
                            .bodyToMono(RuntimeSessionReady.class)
                            .block(DELIVERY_TIMEOUT);
            if (ready == null
                    || !id.equals(ready.sessionId())
                    || !request.context().commandId().equals(ready.commandId())
                    || !request.configurationRevision().equals(ready.configurationRevision())
                    || !RuntimeBootstrapDigest.of(mapper, request)
                            .equals(ready.configurationDigest()))
                finish(claim, false, true, "invalid_runtime_receipt");
            else finish(claim, true, false, null);
        } catch (WebClientResponseException error) {
            int code = error.getStatusCode().value();
            finish(
                    claim,
                    false,
                    code >= 400 && code < 500 && code != 408 && code != 429,
                    "runtime_http_" + code);
        } catch (Exception error) {
            // Do not persist exception messages: upstream bodies and configuration may contain
            // secrets.
            finish(claim, false, false, "runtime_unavailable");
        }
    }

    private void finish(Claim claim, boolean ready, boolean permanent, String failure) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = repository.lockById(claim.id()).orElseThrow();
                    if (!claim.token().equals(row.claimToken)
                            || row.claimUntil <= System.currentTimeMillis()) return;
                    row.updatedAt = System.currentTimeMillis();
                    row.claimUntil = 0;
                    row.claimToken = null;
                    row.failureCode = failure;
                    if (ready) {
                        row.state = "ready";
                        row.bootstrapPayload = null;
                    } else if (permanent) {
                        row.state = "failed";
                        row.bootstrapPayload = null;
                    } else {
                        long backoff = Math.min(30000, 500L << Math.min(claim.attempt(), 6));
                        row.nextAttemptAt =
                                row.updatedAt + backoff + ThreadLocalRandom.current().nextLong(500);
                    }
                    repository.saveAndFlush(row);
                });
    }

    private void checkReplay(
            PortalSessionEntity row, RuntimeSessionBootstrap request, String digest) {
        if (!row.commandId.equals(request.context().commandId()) || !row.digest.equals(digest))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Session bootstrap content differs from its admission");
    }

    private PortalSessionEntity require(String id) {
        return repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode Session admission", error);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read Session admission", error);
        }
    }

    private record Claim(String id, String token, String payload, int attempt) {}
}
