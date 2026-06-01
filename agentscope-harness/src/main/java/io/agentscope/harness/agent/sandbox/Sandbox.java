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

import io.agentscope.core.agent.RuntimeContext;
import java.io.InputStream;

/**
 * An active sandbox with a fully isolated workspace.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Acquire via {@link SandboxClient#create} (new) or {@link SandboxClient#resume} (existing)
 *   <li>Call {@link #start()} — initializes or restores the workspace
 *   <li>Use {@link #exec} for command execution, {@link #persistWorkspace}/{@link #hydrateWorkspace}
 *       for archive operations
 *   <li>Call {@link #stop()} — persists the snapshot (does NOT destroy resources)
 *   <li>Call {@link #shutdown()} — destroys backend resources (tmpdir, container)
 *   <li>Or use {@link #close()} which calls stop + shutdown in sequence
 * </ol>
 *
 * <p>The distinction between {@code stop()} and {@code shutdown()} is critical:
 * <ul>
 *   <li>{@code stop()}: persist snapshot only — safe for both self-managed and user-managed
 *       sandboxes</li>
 *   <li>{@code shutdown()}: destroy backend resources — only called on self-managed sandboxes</li>
 * </ul>
 */
public interface Sandbox extends AutoCloseable {

    void start() throws Exception;

    void stop() throws Exception;

    default void shutdown() throws Exception {
        // no-op by default
    }

    @Override
    void close() throws Exception;

    boolean isRunning();

    /**
     * Returns the current serializable state of this sandbox.
     *
     * @return state (may be modified by lifecycle methods)
     */
    SandboxState getState();

    /**
     * Runs a shell command in the sandbox workspace.
     *
     * @param runtimeContext per-call agent context (session, user, attributes); may be {@code null}
     * @param command shell command
     * @param timeoutSeconds max wait; {@code null} for implementation default
     */
    ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception;

    InputStream persistWorkspace() throws Exception;

    void hydrateWorkspace(InputStream archive) throws Exception;
}
