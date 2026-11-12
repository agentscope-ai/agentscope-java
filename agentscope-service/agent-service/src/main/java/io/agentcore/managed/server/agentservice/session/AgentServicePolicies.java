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
package io.agentcore.managed.server.agentservice.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.protocol.RuntimeConfigurationOverlay;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import io.agentcore.managed.server.web.managed.service.VaultCrypto;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Shared finite-lived authorization; remote refresh never holds Session or execution locks. */
@Service
public class AgentServicePolicies {
    private final AgentServicePolicyRepository policies;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceSessionPermissions permissions;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final VaultCrypto crypto;
    private final WebClient control;
    private final String token;
    private final long maxTtlMs;
    private final Semaphore slots;

    public AgentServicePolicies(
            AgentServicePolicyRepository policies,
            AgentServiceSessionRepository sessions,
            AgentServiceSessionPermissions permissions,
            TransactionTemplate tx,
            ObjectMapper mapper,
            VaultCrypto crypto,
            @Value("${managed-agents.control-plane-url:http://localhost:8081}") String controlUrl,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token,
            @Value("${managed-agents.agent-service.policy-max-ttl-ms:120000}") long maxTtlMs,
            @Value("${managed-agents.agent-service.policy-refresh-slots:16}") int slots) {
        if (maxTtlMs < 10000 || maxTtlMs > 600000 || slots < 1 || slots > 128)
            throw new IllegalArgumentException("Invalid policy refresh limits");
        this.policies = policies;
        this.sessions = sessions;
        this.permissions = permissions;
        this.tx = tx;
        this.mapper = mapper;
        this.crypto = crypto;
        this.control =
                WebClient.builder()
                        .baseUrl(controlUrl)
                        .codecs(config -> config.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build();
        this.token = token;
        this.maxTtlMs = maxTtlMs;
        this.slots = new Semaphore(slots);
    }

    /** Initial shared policy creation precedes the Session transaction; later joins cannot rewind it. */
    public String prepare(AgentServiceSessionEntity session, Map<String, Object> projection) {
        return prepare(session, projection, null);
    }

    public String prepare(
            AgentServiceSessionEntity session,
            Map<String, Object> projection,
            Map<String, Object> configuration) {
        Object supplied = projection.get("scope_id");
        if (supplied == null) return null;
        if (!(supplied instanceof String id) || !id.matches("[0-9a-f]{64}"))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid policy scope");
        validate(projection, id, session.tenant, session.namespace);
        var existing = policies.findById(id);
        if (existing.isPresent()) {
            checkOwner(existing.get(), session.tenant, session.namespace);
            return id;
        }
        var row = new AgentServicePolicyEntity();
        row.id = id;
        row.tenant = session.tenant;
        row.namespace = session.namespace;
        row.applicationId = session.applicationId;
        row.policyVersion = 1;
        row.createdAt = System.currentTimeMillis();
        row.updatedAt = row.createdAt;
        setPolicy(row, withOverlay(session.runtimeKind, projection, configuration));
        try {
            tx.executeWithoutResult(
                    ignored -> {
                        policies.saveAndFlush(row);
                        permissions.replaceGroup(row, readEncrypted(row.policy));
                    });
        } catch (DataIntegrityViolationException race) {
            var winner = policies.findById(id).orElseThrow(() -> race);
            checkOwner(winner, session.tenant, session.namespace);
        }
        return id;
    }

    public Map<String, Object> read(AgentServiceSessionEntity session) {
        if (session.policyId == null) return readEncrypted(session.policy);
        var row =
                policies.readAuthorization(session.policyId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "Authorization policy unavailable"));
        return projection(session, row);
    }

    /** A public list page fetches each shared policy once, in one bounded database read. */
    public Map<String, Map<String, Object>> readPage(List<AgentServiceSessionEntity> page) {
        if (page.size() > 200)
            throw new IllegalArgumentException("Policy page exceeds 200 Sessions");
        var ids =
                page.stream().map(row -> row.policyId).filter(Objects::nonNull).distinct().toList();
        var shared = new HashMap<String, AgentServicePolicyRepository.PolicyView>();
        policies.readAuthorizations(ids).forEach(row -> shared.put(row.getId(), row));
        var decoded = new HashMap<String, Map<String, Object>>();
        var result = new HashMap<String, Map<String, Object>>();
        for (var session : page) {
            if (session.policyId == null) {
                result.put(session.id, readEncrypted(session.policy));
                continue;
            }
            var row = shared.get(session.policyId);
            if (row == null)
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Authorization policy unavailable");
            if (!session.tenant.equals(row.getTenant())
                    || !session.namespace.equals(row.getNamespace()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            result.put(
                    session.id,
                    decoded.computeIfAbsent(row.getId(), ignored -> projection(session, row)));
        }
        return result;
    }

    private Map<String, Object> projection(
            AgentServiceSessionEntity session, AgentServicePolicyRepository.PolicyView row) {
        if (!session.tenant.equals(row.getTenant())
                || !session.namespace.equals(row.getNamespace()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var projection = new LinkedHashMap<>(readEncrypted(row.getPolicy()));
        projection.put("valid_until", row.getValidUntil());
        projection.put("policy_version", row.getPolicyVersion());
        return projection;
    }

    public long version(AgentServiceSessionEntity session) {
        return session.policyId == null
                ? session.policyVersion
                : policies.readAuthorization(session.policyId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "Authorization policy unavailable"))
                        .getPolicyVersion();
    }

    public RuntimeConfigurationOverlay configurationOverlay(
            AgentServiceSessionEntity session, long version) {
        if (session.policyId == null) return null;
        var row = policies.findById(session.policyId).orElseThrow();
        checkOwner(row, session.tenant, session.namespace);
        if (row.policyVersion != version || row.validUntil <= System.currentTimeMillis())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Resource policy changed during activation");
        return row.configurationOverlay == null
                ? null
                : new RuntimeConfigurationOverlay(
                        row.id, row.policyVersion, readEncrypted(row.configurationOverlay));
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.agent-service.policy-refresh-poll-ms:1000}",
            initialDelayString = "${managed-agents.agent-service.policy-refresh-poll-ms:1000}",
            scheduler = "agentServicePolicyRefreshScheduler")
    public void refreshDue() {
        var due = policies.findDue(System.currentTimeMillis(), PageRequest.of(0, 8));
        Flux.fromIterable(due)
                .flatMap(
                        id ->
                                Mono.fromRunnable(() -> refresh(id))
                                        .subscribeOn(Schedulers.boundedElastic()),
                        4)
                .blockLast();
    }

    public void refresh(String id) {
        if (!slots.tryAcquire()) return;
        Claim claim = null;
        try {
            claim =
                    tx.execute(
                            ignored -> {
                                var row = policies.lockById(id).orElseThrow();
                                long now = System.currentTimeMillis();
                                if (row.claimUntil > now || row.nextAttemptAt > now) return null;
                                row.claimToken = UUID.randomUUID().toString();
                                row.claimUntil = now + 20000;
                                row.attemptCount++;
                                policies.saveAndFlush(row);
                                return new Claim(row.id, row.claimToken, row.tenant, row.namespace);
                            });
            if (claim == null) return;
            var representative =
                    sessions.policyRepresentative(id, PageRequest.of(0, 1)).stream()
                            .findFirst()
                            .orElseThrow(
                                    () -> new IllegalStateException("Policy has no ready Session"));
            Claim active = claim;
            var response =
                    control.get()
                            .uri(
                                    uri ->
                                            uri.path(
                                                            "/api/internal/runtime-sessions/{id}/projection")
                                                    .queryParam("tenant", active.tenant)
                                                    .queryParam(
                                                            "configuration",
                                                            "managed"
                                                                    .equals(
                                                                            representative
                                                                                    .runtimeKind))
                                                    .build(representative.id))
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(Duration.ofSeconds(5));
            if (response == null
                    || !(Boolean.TRUE.equals(response.get("managed"))
                            || representative.runtimeKind.equals(response.get("runtimeKind")))
                    || !(response.get("policy") instanceof Map<?, ?> body))
                throw new IllegalStateException("Invalid shared authorization projection");
            Map<String, Object> projection = mapper.convertValue(body, new TypeReference<>() {});
            validate(projection, id, claim.tenant, claim.namespace);
            if ("managed".equals(representative.runtimeKind)
                    && !(response.get("resolved") instanceof Map<?, ?>))
                throw new IllegalStateException("Managed resource configuration is required");
            Map<String, Object> updated =
                    withOverlay(
                            representative.runtimeKind,
                            projection,
                            response.get("resolved") instanceof Map<?, ?> resolved
                                    ? mapper.convertValue(resolved, new TypeReference<>() {})
                                    : null);
            tx.executeWithoutResult(
                    ignored -> {
                        var row = policies.lockById(id).orElseThrow();
                        if (!Objects.equals(row.claimToken, active.token)
                                || row.claimUntil <= System.currentTimeMillis()) return;
                        row.policyVersion++;
                        setPolicy(row, updated);
                        row.claimToken = null;
                        row.claimUntil = 0;
                        row.attemptCount = 0;
                        row.failureCode = null;
                        row.updatedAt = System.currentTimeMillis();
                        policies.saveAndFlush(row);
                        permissions.replaceGroup(row, readEncrypted(row.policy));
                    });
        } catch (Exception failure) {
            if (claim != null) retry(claim);
        } finally {
            slots.release();
        }
    }

    private Map<String, Object> withOverlay(
            String runtimeKind, Map<String, Object> projection, Map<String, Object> configuration) {
        var result = new LinkedHashMap<>(projection);
        result.remove("configuration_overlay");
        if (!"managed".equals(runtimeKind) || configuration == null) return result;
        var resources = new LinkedHashMap<String, Object>();
        for (String field : RuntimeConfigurationOverlay.FIELDS)
            resources.put(field, configuration.get(field));
        var validated =
                new RuntimeConfigurationOverlay(
                        String.valueOf(projection.get("scope_id")), 1, resources);
        if (json(validated).getBytes(StandardCharsets.UTF_8).length
                > RuntimeConfigurationOverlay.MAX_BYTES)
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "Configuration overlay exceeds 1 MiB");
        result.put("configuration_overlay", validated.configuration());
        return result;
    }

    private void retry(Claim claim) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = policies.lockById(claim.id).orElseThrow();
                    if (!Objects.equals(row.claimToken, claim.token)) return;
                    row.claimToken = null;
                    row.claimUntil = 0;
                    row.failureCode = "policy_refresh_failed";
                    long delay = Math.min(20000, 1000L << Math.min(row.attemptCount, 5));
                    row.nextAttemptAt =
                            System.currentTimeMillis()
                                    + delay
                                    + ThreadLocalRandom.current().nextLong(500);
                    // Failure never extends validUntil or alters the last committed grants.
                    policies.saveAndFlush(row);
                });
    }

    private void setPolicy(AgentServicePolicyEntity row, Map<String, Object> projection) {
        long now = System.currentTimeMillis();
        row.validUntil =
                Math.min(((Number) projection.get("valid_until")).longValue(), now + maxTtlMs);
        var bounded = new LinkedHashMap<>(projection);
        bounded.put("valid_until", row.validUntil);
        var resources = bounded.remove("configuration_overlay");
        row.configurationOverlay = resources == null ? null : crypto.encrypt(json(resources));
        row.policy = crypto.encrypt(json(bounded));
        long lifetime = row.validUntil - now;
        row.nextAttemptAt =
                now
                        + Math.max(
                                100,
                                (long)
                                        (lifetime
                                                * ThreadLocalRandom.current()
                                                        .nextDouble(0.5, 0.65)));
    }

    private static void validate(
            Map<String, Object> projection, String id, String tenant, String namespace) {
        if (!id.equals(projection.get("scope_id"))
                || !tenant.equals(projection.get("tenant"))
                || !namespace.equals(projection.get("namespace"))
                || !(projection.get("valid_until") instanceof Number expiry)
                || expiry.longValue() <= System.currentTimeMillis())
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Shared policy scope or validity mismatch");
    }

    private static void checkOwner(AgentServicePolicyEntity row, String tenant, String namespace) {
        if (!tenant.equals(row.tenant) || !namespace.equals(row.namespace))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private Map<String, Object> readEncrypted(String value) {
        try {
            return mapper.readValue(crypto.decrypt(value), new TypeReference<>() {});
        } catch (Exception error) {
            throw new IllegalStateException("Cannot read authorization policy", error);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot encode authorization policy", error);
        }
    }

    private record Claim(String id, String token, String tenant, String namespace) {}
}
