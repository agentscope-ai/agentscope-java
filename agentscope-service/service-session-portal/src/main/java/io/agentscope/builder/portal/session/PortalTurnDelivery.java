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
import io.agentscope.builder.portal.persistence.PortalTurnRepository;
import io.agentscope.builder.runtime.protocol.RuntimeCommandReceipt;
import io.agentscope.builder.runtime.protocol.TurnCommand;
import io.agentscope.builder.web.auth.InternalTokenAuthFilter;
import io.agentscope.builder.web.managed.service.VaultCrypto;
import java.time.Duration;
import java.util.UUID;
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

/** Ordered, bounded command courier. Only runtime facts can change a Turn's execution status. */
@Service
public class PortalTurnDelivery {
    private final PortalTurnRepository turns;
    private final TransactionTemplate tx;
    private final VaultCrypto crypto;
    private final ObjectMapper mapper;
    private final PortalRuntimeBackends backends;
    private final String internalToken;

    public PortalTurnDelivery(
            PortalTurnRepository turns,
            TransactionTemplate tx,
            VaultCrypto crypto,
            ObjectMapper mapper,
            PortalRuntimeBackends backends,
            @Value("${builder.internal-token:${BUILDER_INTERNAL_TOKEN:}}") String internalToken) {
        this.turns = turns;
        this.tx = tx;
        this.crypto = crypto;
        this.mapper = mapper;
        this.backends = backends;
        this.internalToken = internalToken;
    }

    @Scheduled(
            fixedDelayString = "${builder.portal.turn-delivery-poll-ms:1000}",
            initialDelayString = "${builder.portal.turn-delivery-poll-ms:1000}",
            scheduler = "portalTurnDeliveryScheduler")
    public void deliverPending() {
        var due = turns.findDue(System.currentTimeMillis(), PageRequest.of(0, 8));
        Flux.fromIterable(due)
                .flatMap(
                        id ->
                                Mono.fromRunnable(() -> deliver(id))
                                        .subscribeOn(Schedulers.boundedElastic()),
                        4)
                .blockLast();
    }

    private void deliver(String id) {
        var claim =
                tx.execute(
                        ignored -> {
                            var row = turns.lockById(id).orElseThrow();
                            long now = System.currentTimeMillis();
                            if (!"pending".equals(row.deliveryStatus)
                                    || row.claimUntil > now
                                    || row.nextAttemptAt > now) return null;
                            row.claimToken = UUID.randomUUID().toString();
                            row.claimUntil = now + 15000;
                            row.attemptCount++;
                            turns.saveAndFlush(row);
                            return new Claim(row.id, row.claimToken, row.command, row.attemptCount);
                        });
        if (claim == null) return;
        try {
            var command = read(crypto.decrypt(claim.payload()));
            var receipt =
                    backends.session(command.context().sessionId())
                            .post()
                            .uri("/api/internal/runtime/commands/turn")
                            .header(InternalTokenAuthFilter.INTERNAL_TOKEN_HEADER, internalToken)
                            .bodyValue(command)
                            .retrieve()
                            .bodyToMono(RuntimeCommandReceipt.class)
                            .block(Duration.ofSeconds(5));
            if (receipt == null
                    || !id.equals(receipt.commandId())
                    || !command.context().sessionId().equals(receipt.sessionId())
                    || !command.context().turnId().equals(receipt.turnId())
                    || !command.inputDigest().equals(receipt.inputDigest())
                    || command.admissionSequence() != receipt.admissionSequence())
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
            finish(claim, false, false, "runtime_unavailable");
        }
    }

    private void finish(Claim claim, boolean accepted, boolean blocked, String errorCode) {
        tx.executeWithoutResult(
                ignored -> {
                    var row = turns.lockById(claim.id()).orElseThrow();
                    if (!claim.token().equals(row.claimToken)
                            || row.claimUntil <= System.currentTimeMillis()) return;
                    row.updatedAt = System.currentTimeMillis();
                    row.claimToken = null;
                    row.claimUntil = 0;
                    row.failureCode = errorCode;
                    if (accepted) row.deliveryStatus = "delivered";
                    else if (blocked) row.deliveryStatus = "blocked";
                    else {
                        long delay = Math.min(30000, 500L << Math.min(claim.attempt(), 6));
                        row.nextAttemptAt =
                                row.updatedAt + delay + ThreadLocalRandom.current().nextLong(500);
                    }
                    // A failed HTTP confirmation may follow an earlier successful commit.
                    // Keep the logical Turn and reservation until execution facts or cancellation
                    // resolve it.
                    turns.saveAndFlush(row);
                });
    }

    private TurnCommand read(String payload) {
        try {
            return mapper.readValue(payload, TurnCommand.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read durable Turn command", error);
        }
    }

    private record Claim(String id, String token, String payload, int attempt) {}
}
