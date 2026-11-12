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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceInteractionRepository;
import io.agentcore.managed.server.agentservice.persistence.AgentServiceSessionRepository;
import io.agentcore.managed.server.runtime.protocol.InteractionCommand;
import io.agentcore.managed.server.runtime.protocol.RuntimeInteractionReceipt;
import io.agentcore.managed.server.web.auth.InternalTokenAuthFilter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Bounded input courier; a transport failure never proves that native acceptance failed. */
@Service
public class AgentServiceInputDelivery {
    private final AgentServiceInteractionRepository interactions;
    private final AgentServiceSessionRepository sessions;
    private final AgentServiceInputAdmission admission;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AgentServiceRuntimeBackends backends;
    private final String internalToken;
    private final Semaphore slots;

    public AgentServiceInputDelivery(
            AgentServiceInteractionRepository interactions,
            AgentServiceSessionRepository sessions,
            AgentServiceInputAdmission admission,
            TransactionTemplate tx,
            ObjectMapper mapper,
            AgentServiceRuntimeBackends backends,
            @Value("${managed-agents.internal-token:${MANAGED_AGENTS_INTERNAL_TOKEN:}}")
                    String internalToken,
            @Value("${managed-agents.agent-service.input-delivery-slots:32}") int slots) {
        if (slots < 1) throw new IllegalArgumentException("Input delivery slots must be positive");
        this.interactions = interactions;
        this.sessions = sessions;
        this.admission = admission;
        this.tx = tx;
        this.mapper = mapper;
        this.backends = backends;
        this.internalToken = internalToken;
        this.slots = new Semaphore(slots);
    }

    public Map<String, Object> submit(String id) {
        deliver(id);
        return admission.view(admission.row(id));
    }

    @Scheduled(
            fixedDelayString = "${managed-agents.agent-service.interaction-delivery-poll-ms:1000}",
            initialDelayString =
                    "${managed-agents.agent-service.interaction-delivery-poll-ms:1000}",
            scheduler = "agentServiceInteractionDeliveryScheduler")
    public void deliverPending() {
        Flux.fromIterable(interactions.findDue(System.currentTimeMillis(), PageRequest.of(0, 8)))
                .flatMap(
                        id ->
                                Mono.fromRunnable(() -> deliver(id))
                                        .subscribeOn(Schedulers.boundedElastic()),
                        4)
                .blockLast();
    }

    private void deliver(String id) {
        if (!slots.tryAcquire()) return;
        try {
            var claim = claim(id);
            if (claim == null) return;
            try {
                var row = admission.row(id);
                var original = admission.command(row);
                RuntimeInteractionReceipt receipt = null;
                try {
                    receipt =
                            backends.session(row.sessionId)
                                    .get()
                                    .uri(
                                            "/api/internal/runtime/commands/interaction/{session}/{id}",
                                            row.sessionId,
                                            row.id)
                                    .header(
                                            InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                                            internalToken)
                                    .retrieve()
                                    .bodyToMono(RuntimeInteractionReceipt.class)
                                    .block(Duration.ofSeconds(3));
                } catch (WebClientResponseException missing) {
                    if (missing.getStatusCode().value() != 404
                            && missing.getStatusCode().value() != 409) throw missing;
                }
                if (receipt == null) {
                    var refreshed = admission.forDelivery(row);
                    receipt =
                            backends.session(row.sessionId)
                                    .post()
                                    .uri("/api/internal/runtime/commands/interaction")
                                    .header(
                                            InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER,
                                            internalToken)
                                    .bodyValue(refreshed)
                                    .retrieve()
                                    .bodyToMono(RuntimeInteractionReceipt.class)
                                    .block(Duration.ofSeconds(5));
                }
                validate(original, row.payloadDigest, receipt);
                confirm(receipt);
            } catch (WebClientResponseException error) {
                retry(claim, "runtime_http_" + error.getStatusCode().value());
            } catch (Exception error) {
                retry(claim, "runtime_confirmation_pending");
            }
        } finally {
            slots.release();
        }
    }

    private Claim claim(String id) {
        return tx.execute(
                ignored -> {
                    var row = interactions.lockById(id).orElseThrow();
                    long now = System.currentTimeMillis();
                    if (!"pending".equals(row.deliveryStatus)
                            || row.claimUntil > now
                            || row.nextAttemptAt > now) return null;
                    row.claimToken = UUID.randomUUID().toString();
                    row.claimUntil = now + 15000;
                    row.attemptCount++;
                    interactions.saveAndFlush(row);
                    return new Claim(row.id, row.claimToken, row.attemptCount);
                });
    }

    private void validate(
            InteractionCommand command, String digest, RuntimeInteractionReceipt receipt) {
        var context = command.context();
        if (receipt == null
                || !context.sessionId().equals(receipt.sessionId())
                || !Objects.equals(context.turnId(), receipt.turnId())
                || !context.commandId().equals(receipt.commandId())
                || !command.kind().equals(receipt.kind())
                || !digest.equals(receipt.payloadDigest()))
            throw new IllegalStateException("Runtime input receipt does not match its intent");
    }

    private void confirm(RuntimeInteractionReceipt receipt) {
        tx.executeWithoutResult(
                ignored -> {
                    sessions.lockById(receipt.sessionId()).orElseThrow();
                    var row = interactions.lockById(receipt.commandId()).orElseThrow();
                    // A valid ACK may race a source fact or outlive a courier claim. It cannot
                    // rewind a
                    // terminal input outcome, and a stale error callback cannot undo this
                    // confirmation.
                    if (!List.of("applied", "rejected", "failed").contains(row.nativeStatus))
                        row.nativeStatus =
                                receipt.inputStatus() == null
                                        ? "runtime_accepted"
                                        : receipt.inputStatus();
                    row.deliveryStatus = "delivered";
                    row.receipt = json(receipt);
                    row.failureCode = null;
                    row.claimToken = null;
                    row.claimUntil = 0;
                    row.updatedAt = System.currentTimeMillis();
                    interactions.saveAndFlush(row);
                });
    }

    private void retry(Claim claim, String code) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = interactions.lockById(claim.id()).orElseThrow();
                    if (!claim.token().equals(row.claimToken)
                            || !"pending".equals(row.deliveryStatus)) return;
                    long now = System.currentTimeMillis();
                    row.claimToken = null;
                    row.claimUntil = 0;
                    row.failureCode = code;
                    row.nextAttemptAt =
                            now
                                    + Math.min(30000, 250L << Math.min(claim.attempt(), 7))
                                    + ThreadLocalRandom.current().nextLong(250);
                    row.updatedAt = now;
                    interactions.saveAndFlush(row);
                });
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot encode input receipt", error);
        }
    }

    private record Claim(String id, String token, int attempt) {}
}
