/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

/** Per-call credentials used by task-scoped collaboration tools. */
public record AgentTaskToolContext(String taskId, String taskToken) {}
