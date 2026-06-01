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

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Single-replica HITL gate. Calls a user-supplied prompter function that returns
 * approve / reject from human input (terminal, CLI, IDE prompt, …). Defers if the prompter
 * times out.
 *
 * <p>The default constructor wires a no-op prompter that always defers. Real deployments
 * should supply a prompter that bridges to whatever HITL surface is available — typically
 * via {@code RequestStopEvent} so the agent's outer loop can pause for the prompt.
 */
public class LocalApprovalGate implements SkillPromotionGate {

    private static final Logger log = LoggerFactory.getLogger(LocalApprovalGate.class);

    @FunctionalInterface
    public interface Prompter
            extends Function<SkillCandidate, CompletableFuture<PromotionDecision>> {}

    private final Duration timeout;
    private final Prompter prompter;
    private final List<String> targetEnvironments;

    public LocalApprovalGate() {
        this(Duration.ofMinutes(30), defaultPrompter(), List.of("prod"));
    }

    public LocalApprovalGate(Duration timeout) {
        this(timeout, defaultPrompter(), List.of("prod"));
    }

    public LocalApprovalGate(Duration timeout, Prompter prompter, List<String> targetEnvironments) {
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(30);
        this.prompter = prompter != null ? prompter : defaultPrompter();
        this.targetEnvironments =
                targetEnvironments != null ? List.copyOf(targetEnvironments) : List.of("prod");
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.fromFuture(prompter.apply(candidate))
                .timeout(timeout)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "LocalApprovalGate prompter failed/timeout for skill={}: {}",
                                    candidate.name(),
                                    e.getMessage());
                            return Mono.just(
                                    new PromotionDecision.Defer(
                                            Duration.ofHours(1),
                                            "LocalApprovalGate: prompter timeout/error"));
                        });
    }

    /** Default prompter: never approves. Suitable for tests / when no prompter is wired. */
    public static Prompter defaultPrompter() {
        return candidate ->
                CompletableFuture.completedFuture(
                        new PromotionDecision.Defer(
                                Duration.ofHours(24), "LocalApprovalGate has no prompter wired"));
    }

    /** Sample prompter: stdin yes/no. Useful for CLI demos / tests. */
    public static Prompter stdinPrompter(java.io.InputStream in, java.io.PrintStream out) {
        return candidate -> {
            CompletableFuture<PromotionDecision> f = new CompletableFuture<>();
            new Thread(
                            () -> {
                                try {
                                    out.println(
                                            "[skill-promote] approve skill '"
                                                    + candidate.name()
                                                    + "'? [y/N]");
                                    var reader =
                                            new java.io.BufferedReader(
                                                    new java.io.InputStreamReader(in));
                                    String line = reader.readLine();
                                    if (line != null && line.trim().equalsIgnoreCase("y")) {
                                        f.complete(
                                                new PromotionDecision.Approve(
                                                        "stdin-user",
                                                        List.of("prod"),
                                                        Instant.now()));
                                    } else {
                                        f.complete(
                                                new PromotionDecision.Reject(
                                                        "user said no", "stdin-user"));
                                    }
                                } catch (Exception e) {
                                    f.complete(
                                            new PromotionDecision.Defer(
                                                    Duration.ofMinutes(5), e.getMessage()));
                                }
                            },
                            "skill-promote-stdin")
                    .start();
            return f.orTimeout(30, TimeUnit.MINUTES);
        };
    }

    public List<String> getTargetEnvironments() {
        return targetEnvironments;
    }
}
