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
package io.agentscope.harness.agent.verification;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.TaskRequirement;
import io.agentscope.core.state.TaskVerification;
import io.agentscope.harness.agent.observation.StateStoreActionObserver;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Explicit caller-authorized verification over already persisted evidence. Never registered as
 * a model tool and never re-executes an action. Call inside the owning session transaction.
 */
public final class VerificationService {
    public static final String EVENT_NAME = "task_verification";
    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
    private final AgentStateStore store;
    private final StateStoreActionObserver observations;

    public VerificationService(AgentStateStore store) {
        this.store = Objects.requireNonNull(store);
        this.observations = new StateStoreActionObserver(store);
    }

    public static String key(String id) {
        return "verification_" + id;
    }

    public Optional<StoredVerification> load(String userId, String sessionId, String id) {
        return store.get(userId, sessionId, key(id), StoredVerification.class);
    }

    public Mono<TaskVerification> verify(
            RuntimeContext context,
            String requirementId,
            String actionId,
            DeterministicVerifier verifier) {
        return Mono.deferContextual(
                subscriber -> {
                    AtomicBoolean cancelled = new AtomicBoolean();
                    var emitter =
                            AgentEventEmitter.fromForwardingContext(subscriber)
                                    .or(() -> AgentEventEmitter.fromContext(subscriber));
                    return Mono.fromCallable(
                                    () -> {
                                        Objects.requireNonNull(context, "context");
                                        Objects.requireNonNull(verifier, "verifier");
                                        if (context.getSessionId() == null
                                                || context.getSessionId().isBlank()
                                                || context.getAgentState() == null) {
                                            throw new IllegalArgumentException(
                                                    "Current session identity and AgentState"
                                                            + " required");
                                        }
                                        var task = context.getAgentState().getTasksContext();
                                        var snapshot = task.snapshot();
                                        var requirement =
                                                snapshot.getRequirements().stream()
                                                        .filter(
                                                                item ->
                                                                        item.id()
                                                                                .equals(
                                                                                        requirementId))
                                                        .findFirst()
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalArgumentException(
                                                                                "Unknown"
                                                                                    + " criterion"));
                                        if (requirement.kind()
                                                        != TaskRequirement.Kind.ACCEPTANCE_CRITERION
                                                || requirement.status()
                                                        != TaskRequirement.Status.CONFIRMED) {
                                            throw new IllegalArgumentException(
                                                    "Only confirmed acceptance criteria can be"
                                                            + " checked");
                                        }
                                        var binding = snapshot.getEvidenceBinding();
                                        if (binding == null)
                                            throw new IllegalStateException(
                                                    "Pin the task subject version before"
                                                            + " execution");
                                        var evidence =
                                                observations
                                                        .load(
                                                                context.getUserId(),
                                                                context.getSessionId(),
                                                                actionId,
                                                                false)
                                                        .orElseThrow(
                                                                () ->
                                                                        new IllegalArgumentException(
                                                                                "Settled action"
                                                                                    + " evidence"
                                                                                    + " unavailable"));
                                        var action = evidence.observation();
                                        if (!actionId.equals(action.actionId())
                                                || !Objects.equals(
                                                        context.getUserId(), action.userId())
                                                || !context.getSessionId()
                                                        .equals(action.sessionId())) {
                                            throw new IllegalArgumentException(
                                                    "Evidence identity mismatch");
                                        }
                                        if (!binding.equals(action.evidenceBinding())) {
                                            throw new ConcurrentModificationException(
                                                    "Action belongs to a different or unversioned"
                                                            + " subject/contract");
                                        }
                                        DeterministicVerifier.Verdict verdict;
                                        try {
                                            verdict =
                                                    Objects.requireNonNull(
                                                            verifier.verify(evidence), "verdict");
                                        } catch (RuntimeException error) {
                                            verdict =
                                                    new DeterministicVerifier.Verdict(
                                                            TaskVerification.Outcome.ERROR,
                                                            "Verifier failed; no acceptance"
                                                                    + " conclusion");
                                        }
                                        var report =
                                                new TaskVerification(
                                                        UUID.randomUUID().toString(),
                                                        requirementId,
                                                        actionId,
                                                        verifier.id(),
                                                        verdict.outcome(),
                                                        verdict.reason(),
                                                        binding,
                                                        System.currentTimeMillis());
                                        // Persist before updating the model-facing task state. A
                                        // failed commit cannot pass.
                                        if (cancelled.get())
                                            throw new CancellationException(
                                                    "Verification cancelled");
                                        var stored =
                                                new StoredVerification(
                                                        report,
                                                        requirement,
                                                        StateStoreActionObserver.key(
                                                                actionId, false));
                                        if (store.supportsVersioning()) {
                                            if (store.saveIfVersion(
                                                            context.getUserId(),
                                                            context.getSessionId(),
                                                            key(report.id()),
                                                            stored,
                                                            0)
                                                    == AgentStateStore.UNVERSIONED) {
                                                throw new IllegalStateException(
                                                        "Verification commit was not acknowledged");
                                            }
                                        } else {
                                            store.save(
                                                    context.getUserId(),
                                                    context.getSessionId(),
                                                    key(report.id()),
                                                    stored);
                                        }
                                        if (cancelled.get())
                                            throw new CancellationException(
                                                    "Verification cancelled after commit");
                                        task.recordVerification(report, snapshot.getRevision());
                                        emitter.ifPresent(
                                                value -> {
                                                    try {
                                                        value.emit(
                                                                new CustomEvent(
                                                                        EVENT_NAME,
                                                                        Map.of(
                                                                                "verification_id",
                                                                                report.id(),
                                                                                "requirement_id",
                                                                                report
                                                                                        .requirementId(),
                                                                                "action_id",
                                                                                report.actionId(),
                                                                                "verifier_id",
                                                                                report.verifierId(),
                                                                                "outcome",
                                                                                report.outcome()
                                                                                        .name(),
                                                                                "evidence_binding",
                                                                                report.binding())));
                                                    } catch (RuntimeException error) {
                                                        log.warn(
                                                                "Verification persisted but event"
                                                                        + " delivery failed: {}",
                                                                report.id());
                                                    }
                                                });
                                        return report;
                                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnCancel(() -> cancelled.set(true));
                });
    }
}
