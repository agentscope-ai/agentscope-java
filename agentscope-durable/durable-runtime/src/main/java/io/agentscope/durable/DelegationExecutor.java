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
import io.agentscope.durable.DurableTypes.ThreadBinding;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.ThreadSpec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Durable asynchronous child Turns through the ordinary Executor/result-application boundary. */
public final class DelegationExecutor implements Executor {
    private final SessionBackend backend;
    private final Function<ExecutionRequest, DelegationTarget> targets;

    /** Default isolation: one deterministic child Thread per parent execution. */
    public DelegationExecutor(
            SessionBackend backend, String harness, String definitionVersion, String stateVersion) {
        this(
                backend,
                request ->
                        new DelegationTarget(
                                new ThreadSpec(
                                        childThread(request),
                                        new ThreadBinding(
                                                harness, definitionVersion, stateVersion)),
                                request.call().arguments()));
    }

    /** Resolve against the parent's frozen definition. An explicit existing Thread reuses context. */
    public DelegationExecutor(
            SessionBackend backend, Function<ExecutionRequest, DelegationTarget> targets) {
        this.backend = Objects.requireNonNull(backend);
        this.targets = Objects.requireNonNull(targets);
    }

    public static ThreadKey childThread(ExecutionRequest request) {
        return new ThreadKey(
                request.session(),
                "child-" + DurableJson.hash(List.of(request.thread(), request.executionId())));
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, true);
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        var existing = backend.delegation(request);
        var view = existing.orElseGet(() -> backend.delegate(request, targets.apply(request)));
        return outcome(view, null);
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        Optional<DelegationView> view;
        try {
            view = backend.delegation(request);
        } catch (DurableException error) {
            if (error.code() == DurableException.Code.NOT_FOUND) return ExecutionResult.missing();
            throw error;
        }
        return view.isEmpty() ? ExecutionResult.missing() : outcome(view.get(), handle);
    }

    private ExecutionResult outcome(DelegationView view, String handle) {
        var child = view.child();
        if (handle != null && (child == null || !handle.equals(child.turnId())))
            throw new DurableException(DurableException.Code.CONFLICT, "Child Turn handle changed");
        if (child == null) {
            if (!view.cancelRequested())
                throw new DurableException(
                        DurableException.Code.CONFLICT, "Delegation has no child or cancellation");
            return new ExecutionResult(ExecutionState.CANCELLED, null, null, null);
        }
        var turn = backend.turn(child.thread(), child.turnId());
        ExecutionState state =
                switch (turn.status()) {
                    case SUCCEEDED -> ExecutionState.SUCCEEDED;
                    case FAILED -> ExecutionState.FAILED;
                    case CANCELLED -> ExecutionState.CANCELLED;
                    default -> ExecutionState.RUNNING;
                };
        return new ExecutionResult(state, turn.turnId(), turn.output(), turn.error());
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        return outcome(backend.cancelDelegation(request, handle), handle);
    }
}
