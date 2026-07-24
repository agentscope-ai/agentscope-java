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
package io.agentscope.harness.agent.subagent.task;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Discriminated union describing how a background subagent task should execute.
 *
 * <p>{@link LocalTaskRunSpec} runs the supplier on a local executor. {@link RemoteTaskRunSpec}
 * delegates to an AgentScope task HTTP API (see {@code agentscope-extensions-agent-protocol}).
 * {@link AdoptedTaskRunSpec} wraps an already-running {@link CompletableFuture} (used when a sync
 * execution is promoted to async on timeout). {@link SuspensibleLocalTaskRunSpec} returns a typed
 * outcome so permission HITL pauses remain non-terminal.
 */
public sealed interface TaskRunSpec {

    /** In-process execution via {@link Supplier}. */
    record LocalTaskRunSpec(Supplier<String> execution) implements TaskRunSpec {}

    /** In-process child execution that may suspend for permission approval. */
    record SuspensibleLocalTaskRunSpec(Supplier<TaskRunOutcome> execution, String subSessionId)
            implements TaskRunSpec {}

    /**
     * Remote HTTP task execution. The {@code taskId} is chosen by the client and used as the
     * remote task key end-to-end.
     */
    record RemoteTaskRunSpec(
            String baseUrl, Map<String, String> headers, String agentId, String input)
            implements TaskRunSpec {}

    /**
     * Adopts an already-running {@link CompletableFuture} as a tracked background task. Used when
     * a sync execution exceeds its timeout and is promoted to async: the future is still in
     * progress and only needs status-tracking callbacks, not a new executor submission.
     */
    record AdoptedTaskRunSpec(CompletableFuture<String> future) implements TaskRunSpec {}
}
