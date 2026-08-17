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
package io.agentscope.spring.boot.admin.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit record for one admin operation.
 *
 * <p>Emitted by {@code AdminAuditLogger} both as an SLF4J line and (when configured) as a Spring
 * {@code ApplicationEvent}, so downstream sinks (Loki, RocketMQ, Nacos audit, ...) can subscribe.
 */
public record AdminAuditEvent(
        Instant timestamp,
        String commandId,
        String operator,
        String target,
        boolean writes,
        String outcome,
        Map<String, Object> attributes) {

    public AdminAuditEvent {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId required");
        }
        timestamp = timestamp == null ? Instant.now() : timestamp;
        operator = operator == null ? "anonymous" : operator;
        target = target == null ? "" : target;
        outcome = outcome == null ? "ok" : outcome;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
