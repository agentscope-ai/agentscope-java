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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Pushes {@link SkillCandidate} metadata to an external system (Slack webhook, ticket service,
 * Nacos config item, …). {@code NotifyAndWaitGate} fires its sinks once per draft submission;
 * sinks are best-effort and never block the gate.
 */
public interface NotificationSink {

    Mono<Void> notify(SkillCandidate candidate);

    /** No-op — useful as the "I'll wire my own sink later" default. */
    static NotificationSink noOp() {
        return c -> Mono.empty();
    }

    /** Logs the candidate at INFO; production setups can route via slf4j → ELK / Loki. */
    static NotificationSink logging() {
        Logger log = LoggerFactory.getLogger("SkillNotificationSink.logging");
        return candidate -> {
            log.info(
                    "[skill-promote-request] name={} desc={} scan={} scripts={}",
                    candidate.name(),
                    candidate.description(),
                    candidate.securityScan() == null
                            ? "n/a"
                            : candidate.securityScan().verdict(),
                    candidate.scriptFiles() == null ? 0 : candidate.scriptFiles().size());
            return Mono.empty();
        };
    }
}
