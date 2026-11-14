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
package io.agentscope.durable.worker;

import io.agentscope.durable.DurableTypes.ExecutionRequest;
import io.agentscope.durable.DurableTypes.ExecutionResult;
import io.agentscope.durable.DurableTypes.OutputPage;
import io.agentscope.durable.Executor;

/**
 * Brain-side local endpoint backed by a colocated {@link WorkerRuntime}.
 *
 * <p>Instances are borrowed from {@link WorkerRuntime#localExecutor()}; close the owning runtime to
 * stop Hands execution.
 */
public final class LocalWorkerExecutor implements Executor {
    private static final Capabilities CAPABILITIES = new Capabilities(true, true, true, true);

    private final WorkerRuntime worker;

    LocalWorkerExecutor(WorkerRuntime worker) {
        this.worker = worker;
    }

    @Override
    public Capabilities capabilities() {
        // These are ledger guarantees, not claims about external effects. A provider without an
        // output stream has an empty terminal page; captured bytes never require reconnecting it.
        return CAPABILITIES;
    }

    @Override
    public ExecutionResult startExecution(ExecutionRequest request) {
        return worker.admit(request);
    }

    @Override
    public ExecutionResult query(ExecutionRequest request, String handle) {
        return worker.queryExecution(request);
    }

    @Override
    public ExecutionResult cancel(ExecutionRequest request, String handle) {
        return worker.cancelExecution(request);
    }

    @Override
    public OutputPage output(ExecutionRequest request, long offset) {
        return worker.readOutput(request, offset);
    }
}
