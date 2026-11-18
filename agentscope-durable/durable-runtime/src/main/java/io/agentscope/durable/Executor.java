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
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;

/**
 * Starts and controls external tool executions.
 *
 * <p>This is an execution protocol, not a component lifecycle. Implementations that own transport
 * threads use {@link ManagedExecutor}; {@link DurableRuntime} opens and closes those transports.
 */
public interface Executor extends AutoCloseable {
    record Capabilities(
            boolean idempotentStart,
            boolean lookupByExecutionId,
            boolean cancellation,
            boolean output) {
        public Capabilities(
                boolean idempotentStart, boolean lookupByExecutionId, boolean cancellation) {
            this(idempotentStart, lookupByExecutionId, cancellation, false);
        }
    }

    Capabilities capabilities();

    /**
     * Read-only validation before the first dispatch marker. Never starts an external effect.
     * Existing executions bypass this new-start check so revoked admission cannot block cleanup.
     */
    default void validateStart(ExecutionRequest request) {}

    /** Starts or recovers the execution identified by the request. */
    ExecutionResult startExecution(ExecutionRequest request);

    ExecutionResult query(ExecutionRequest request, String handle);

    ExecutionResult cancel(ExecutionRequest request, String handle);

    default OutputPage output(ExecutionRequest request, long offset) {
        throw new DurableException(
                DurableException.Code.INCOMPATIBLE, "Executor has no output stream");
    }

    @Override
    default void close() {}
}
