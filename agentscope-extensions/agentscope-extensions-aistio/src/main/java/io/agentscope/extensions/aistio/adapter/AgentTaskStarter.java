/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import reactor.core.publisher.Mono;

/** Starts an isolated framework execution for a durable AgentTask delivery. */
@FunctionalInterface
public interface AgentTaskStarter {

    Mono<Void> start(AgentTaskAssignment assignment);
}
