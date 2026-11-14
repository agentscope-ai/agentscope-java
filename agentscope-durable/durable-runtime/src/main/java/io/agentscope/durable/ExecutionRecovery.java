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
import io.agentscope.durable.DurableTypes.ExecutionState;

/** Shared recovery decisions for a direct Brain Executor and an independently hosted Hands worker. */
public final class ExecutionRecovery {
    private ExecutionRecovery() {}

    /**
     * Resolve the original operation. The caller must persist the dispatch marker under ownership
     * before passing attempted=false, and persist the returned observation before applying it.
     * No exception or missing operation proves that an external effect did not happen.
     */
    public static ExecutionResult resolve(
            Executor executor,
            ExecutionRequest request,
            ExecutionResult previous,
            boolean attempted,
            boolean cancellationRequested) {
        if (previous.state().terminal()) return previous;
        if (cancellationRequested && !attempted)
            return new ExecutionResult(ExecutionState.CANCELLED, previous.handle(), null, null);
        ExecutionResult observed = previous;
        try {
            if (!attempted) {
                observed = retainHandle(executor.startExecution(request), observed);
            } else {
                var capabilities = executor.capabilities();
                boolean queryable = capabilities.lookupByExecutionId() || observed.handle() != null;
                if (cancellationRequested) {
                    if (queryable) {
                        // A failed status read must not prevent an independently supported
                        // cancellation.
                        try {
                            observed =
                                    retainHandle(
                                            executor.query(request, observed.handle()), observed);
                        } catch (RuntimeException unavailable) {
                            observed =
                                    unknown(observed, "Original operation status is unconfirmed");
                        }
                    }
                    if (observed.state().terminal()) return observed;
                    observed =
                            capabilities.cancellation()
                                    ? retainHandle(
                                            executor.cancel(request, observed.handle()), observed)
                                    : unknown(observed, "Provider cannot confirm cancellation");
                } else if (queryable) {
                    observed = retainHandle(executor.query(request, observed.handle()), observed);
                    if (observed.state() == ExecutionState.NOT_FOUND
                            && capabilities.idempotentStart())
                        observed = retainHandle(executor.startExecution(request), observed);
                } else if (capabilities.idempotentStart()) {
                    // The provider guarantees that repeating this exact identity reuses one
                    // operation.
                    observed = retainHandle(executor.startExecution(request), observed);
                } else {
                    observed = unknown(observed, "Provider cannot locate the original operation");
                }
            }
            return observed.state() == ExecutionState.NOT_FOUND
                    ? unknown(observed, "Original operation missing; outcome remains unconfirmed")
                    : observed;
        } catch (RuntimeException unavailable) {
            return unknown(observed, "External operation outcome is unconfirmed");
        }
    }

    private static ExecutionResult retainHandle(ExecutionResult result, ExecutionResult previous) {
        return result.handle() == null && previous.handle() != null
                ? new ExecutionResult(
                        result.state(), previous.handle(), result.result(), result.error())
                : result;
    }

    private static ExecutionResult unknown(ExecutionResult previous, String error) {
        return new ExecutionResult(ExecutionState.UNKNOWN, previous.handle(), null, error);
    }
}
