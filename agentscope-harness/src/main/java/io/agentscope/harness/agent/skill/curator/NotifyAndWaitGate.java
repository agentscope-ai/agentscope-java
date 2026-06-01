/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package io.agentscope.harness.agent.skill.curator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Multi-replica gate. On {@code review} writes a {@code .review_request.json} file next to the
 * draft skill (so the file is visible to whichever replica gets the eventual approval call) and
 * fires every configured {@link NotificationSink} concurrently. Always returns
 * {@link SkillPromotionGate.PromotionDecision.Defer} — the actual approval comes from the
 * external system calling {@code HarnessAgent.promoteSkill(...)} on any replica.
 *
 * <p>Sinks are best-effort. A failed sink is logged but does not block the gate or the agent.
 */
public class NotifyAndWaitGate implements SkillPromotionGate {

    private static final Logger log = LoggerFactory.getLogger(NotifyAndWaitGate.class);

    private static final ObjectMapper JSON =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private final List<NotificationSink> sinks;
    private final WorkspaceManager workspaceManager;
    private final String draftsDir;
    private final Duration retryAfter;

    public NotifyAndWaitGate(
            List<NotificationSink> sinks, WorkspaceManager workspaceManager, String draftsDir) {
        this(sinks, workspaceManager, draftsDir, Duration.ofHours(24));
    }

    public NotifyAndWaitGate(
            List<NotificationSink> sinks,
            WorkspaceManager workspaceManager,
            String draftsDir,
            Duration retryAfter) {
        this.sinks = sinks != null ? List.copyOf(sinks) : List.of();
        this.workspaceManager = workspaceManager;
        this.draftsDir = draftsDir != null && !draftsDir.isBlank() ? draftsDir : "skills/_drafts";
        this.retryAfter = retryAfter != null ? retryAfter : Duration.ofHours(24);
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return writeReviewRequest(candidate, ctx)
                .then(
                        Flux.fromIterable(sinks)
                                .flatMap(
                                        sink ->
                                                sink.notify(candidate)
                                                        .onErrorResume(
                                                                e -> {
                                                                    log.warn(
                                                                            "Notification sink {}"
                                                                                + " failed for"
                                                                                + " skill {}: {}",
                                                                            sink.getClass()
                                                                                    .getSimpleName(),
                                                                            candidate.name(),
                                                                            e.getMessage());
                                                                    return Mono.empty();
                                                                }))
                                .then())
                .thenReturn(
                        (PromotionDecision)
                                new PromotionDecision.Defer(
                                        retryAfter,
                                        "NotifyAndWaitGate: review_request.json written and"
                                                + " notifications dispatched; awaiting external"
                                                + " HarnessAgent.promoteSkill call"));
    }

    private Mono<Void> writeReviewRequest(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.fromRunnable(
                () -> {
                    if (workspaceManager == null) {
                        return;
                    }
                    try {
                        String json = JSON.writeValueAsString(candidate);
                        String relPath =
                                draftsDir + "/" + candidate.name() + "/.review_request.json";
                        workspaceManager.writeDraftSkillFile(ctx, relPath, json);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to write review_request.json for skill {}: {}",
                                candidate.name(),
                                e.getMessage());
                    }
                });
    }
}
