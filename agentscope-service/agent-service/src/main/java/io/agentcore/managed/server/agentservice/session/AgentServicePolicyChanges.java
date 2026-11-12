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

import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyChangeEntity;
import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyChangeRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServicePolicyRepository;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

/** Control-plane notifications are acknowledged only after a shared durable receipt exists. */
@Service
public class AgentServicePolicyChanges {
    private final AgentServicePolicyChangeRepository changes;
    private final AgentServicePolicyRepository policies;
    private final TransactionTemplate tx;
    private final WebClient control;
    private final String token;

    public AgentServicePolicyChanges(
            AgentServicePolicyChangeRepository changes,
            AgentServicePolicyRepository policies,
            TransactionTemplate tx,
            @Value("${managed-agents.control-plane-url:http://localhost:8081}") String controlUrl,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String token) {
        this.changes = changes;
        this.policies = policies;
        this.tx = tx;
        this.token = token;
        this.control =
                WebClient.builder()
                        .baseUrl(controlUrl)
                        .codecs(config -> config.defaultCodecs().maxInMemorySize(128 * 1024))
                        .build();
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.agent-service.policy-changes-poll-ms:2000}",
            initialDelayString = "${managed-agents.agent-service.policy-changes-poll-ms:2000}",
            scheduler = "agentServicePolicyChangeScheduler")
    public void receiveDue() {
        try {
            var response =
                    control.post()
                            .uri("/api/internal/policy-changes/claim")
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(Duration.ofSeconds(5));
            if (response == null
                    || !(response.get("changes") instanceof List<?> batch)
                    || batch.size() > 16) return;
            for (var raw : batch) {
                if (!(raw instanceof Map<?, ?> change)) continue;
                try {
                    receive(change);
                    control.post()
                            .uri("/api/internal/policy-changes/ack")
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, token)
                            .bodyValue(
                                    Map.of(
                                            "source",
                                            change.get("source"),
                                            "id",
                                            change.get("id"),
                                            "claim_token",
                                            change.get("claim_token")))
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(2));
                } catch (Exception failure) {
                    /* Source lease expires; the same receipt makes redelivery harmless. */
                }
            }
        } catch (Exception unavailable) {
            /* Finite policy expiry and independent refresh remain authoritative. */
        }
    }

    public String receive(Map<?, ?> input) {
        String source = text(input, "source", 8), rawId = text(input, "id", 36);
        if (!List.of("control", "product").contains(source)
                || !UUID.fromString(rawId).toString().equals(rawId)
                || !(input.get("schema_version") instanceof Number schema)
                || schema.intValue() != 1)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unsupported policy change");
        var row = new AgentServicePolicyChangeEntity();
        row.id = source + ":" + rawId;
        row.tenant = text(input, "tenant", 128);
        row.namespace = text(input, "namespace", 128);
        row.applicationId = text(input, "application_id", 256);
        if (!row.namespace.isEmpty() && row.tenant.isEmpty()
                || !row.applicationId.isEmpty() && row.tenant.isEmpty())
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Invalid policy change scope");
        row.createdAt = System.currentTimeMillis();
        row.updatedAt = row.createdAt;
        var existing = changes.findById(row.id);
        if (existing.isPresent()) {
            same(existing.get(), row);
            return row.id;
        }
        try {
            tx.executeWithoutResult(ignored -> changes.saveAndFlush(row));
        } catch (DataIntegrityViolationException race) {
            same(changes.findById(row.id).orElseThrow(() -> race), row);
        }
        return row.id;
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.agent-service.policy-changes-poll-ms:2000}",
            initialDelayString = "${managed-agents.agent-service.policy-changes-poll-ms:2000}",
            scheduler = "agentServicePolicyChangeScheduler")
    public void applyDue() {
        for (String id : changes.findPending(PageRequest.of(0, 8))) apply(id);
    }

    /** Short transactions traverse shared policies in key order; Session rows are never scanned. */
    public void apply(String id) {
        tx.executeWithoutResult(
                ignored -> {
                    var change = changes.lockById(id).orElseThrow();
                    if (!"pending".equals(change.state)) return;
                    var ids =
                            policies.matching(
                                    change.tenant,
                                    change.namespace,
                                    change.applicationId,
                                    change.nextPolicyId,
                                    PageRequest.of(0, 128));
                    long now = System.currentTimeMillis();
                    for (String policyId : ids) {
                        var policy = policies.lockById(policyId).orElseThrow();
                        policy.validUntil = 0;
                        policy.nextAttemptAt = now;
                        policy.claimToken = null;
                        policy.claimUntil = 0;
                        policy.failureCode = "policy_invalidated";
                        policy.updatedAt = now;
                        policies.save(policy);
                        change.nextPolicyId = policyId;
                    }
                    if (ids.size() < 128) change.state = "applied";
                    change.updatedAt = now;
                    changes.saveAndFlush(change);
                });
    }

    private static String text(Map<?, ?> input, String field, int max) {
        if (!(input.get(field) instanceof String value) || value.length() > max)
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Invalid policy change " + field);
        return value;
    }

    private static void same(
            AgentServicePolicyChangeEntity existing, AgentServicePolicyChangeEntity candidate) {
        if (!Objects.equals(existing.tenant, candidate.tenant)
                || !Objects.equals(existing.namespace, candidate.namespace)
                || !Objects.equals(existing.applicationId, candidate.applicationId))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Policy change ID conflicts with its scope");
    }
}
