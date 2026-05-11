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
package io.agentscope.harness.agent.sandbox;

import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * Factory for creating and resuming {@link Sandbox} instances.
 *
 * @param <O> the type of client options for this implementation
 */
public interface SandboxClient<O extends SandboxClientOptions> {

    /**
     * Creates a new sandbox with the given workspace spec and snapshot spec.
     *
     * <p>Returned in a pre-start state; call {@link Sandbox#start()} before use.
     */
    Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec, O options);

    /**
     * Resumes a sandbox from previously serialized {@link SandboxState}.
     */
    Sandbox resume(SandboxState state);

    void delete(Sandbox sandbox);

    String serializeState(SandboxState state);

    SandboxState deserializeState(String json);
}
