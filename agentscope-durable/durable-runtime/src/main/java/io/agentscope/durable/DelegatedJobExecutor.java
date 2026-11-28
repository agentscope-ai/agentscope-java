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

import io.agentscope.durable.DurableTypes.DelegationTarget;
import io.agentscope.durable.DurableTypes.DelegationView;
import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.ExecutionState;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Runs start_job, get_job and await_job against child Turns in the same Session backend. */
public final class DelegatedJobExecutor implements Executor {
    public static final String START = "start_job";
    public static final String GET = "get_job";
    public static final String AWAIT = "await_job";

    private final SessionBackend backend;
    private final Function<ExecutionRequest, DelegationTarget> targets;

    /** The start target must use DelegationExecutor.childThread(request). */
    public DelegatedJobExecutor(
            SessionBackend backend, Function<ExecutionRequest, DelegationTarget> targets) {
        this.backend = Objects.requireNonNull(backend);
        this.targets = Objects.requireNonNull(targets);
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true);
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        if (!START.equals(request.call().name())) return observe(request);
        return receipt(
                backend.delegation(request)
                        .orElseGet(
                                () -> {
                                    var target = targets.apply(request);
                                    return backend.delegate(
                                            request,
                                            new DelegationTarget(
                                                    target.child(), target.input(), true));
                                }));
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        if (START.equals(request.call().name()))
            return backend.delegation(request)
                    .map(DelegatedJobExecutor::receipt)
                    .orElseGet(ExecutionResult::missing);
        return observe(request);
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        if (START.equals(request.call().name()))
            return receipt(backend.cancelDelegation(request, null));
        return new ExecutionResult(ExecutionState.CANCELLED, handle, null, null);
    }

    private ExecutionResult observe(ExecutionRequest request) {
        var arguments = request.call().arguments();
        var id = arguments.path("jobId");
        try {
            String jobId = DurableJson.text(id.isTextual() ? id.asText() : null, "jobId");
            return switch (request.call().name()) {
                case GET ->
                        new ExecutionResult(
                                ExecutionState.SUCCEEDED,
                                jobId,
                                DurableJson.value(backend.job(request.thread(), jobId)),
                                null);
                case AWAIT -> {
                    var timeout = arguments.path("timeoutMillis");
                    if (!timeout.isIntegralNumber() || !timeout.canConvertToLong())
                        throw new DurableException(
                                DurableException.Code.INVALID,
                                "timeoutMillis must be a positive integer");
                    yield backend.awaitJob(request, jobId, timeout.asLong());
                }
                default ->
                        throw new DurableException(
                                DurableException.Code.INVALID, "Unknown job operation");
            };
        } catch (DurableException error) {
            if (error.code() != DurableException.Code.INVALID
                    && error.code() != DurableException.Code.NOT_FOUND) throw error;
            return new ExecutionResult(ExecutionState.FAILED, null, null, error.getMessage());
        }
    }

    private static ExecutionResult receipt(DelegationView view) {
        if (view.child() == null)
            return new ExecutionResult(ExecutionState.CANCELLED, null, null, null);
        String jobId = view.parent().executionId();
        return new ExecutionResult(
                ExecutionState.SUCCEEDED,
                jobId,
                DurableJson.value(
                        Map.of("jobId", jobId, "child", view.child(), "status", "ACCEPTED")),
                null);
    }
}
