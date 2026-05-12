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
package io.agentscope.harness.agent;

import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;

/**
 * Controls how agent state is isolated and shared across calls.
 *
 * <p>This enum is the canonical isolation-scope definition used by both the sandbox filesystem
 * backend ({@link io.agentscope.harness.agent.sandbox.SandboxContext}) and the remote filesystem
 * backend ({@link RemoteFilesystemSpec}).
 *
 * <p><b>Sandbox semantics</b>: the scope determines which key is used when persisting and loading
 * {@code _sandbox.json} state. Calls that resolve to the <em>same</em> scope key will
 * sequentially reuse the same sandbox (each call resumes the persisted state from the previous
 * one).
 *
 * <p><b>Store namespace semantics</b>: the scope determines the namespace prefix used by
 * {@link RemoteFilesystem} when routing files to the shared
 * key-value store. Different scopes produce different namespace prefixes, controlling which calls
 * share the same view of stored files.
 *
 * <p>Scope selection:
 * <ul>
 *   <li>{@link #SESSION} – isolated per session; the default.</li>
 *   <li>{@link #USER} – shared across all sessions of the same user.</li>
 *   <li>{@link #AGENT} – shared across all users and sessions of the same agent.</li>
 *   <li>{@link #GLOBAL} – globally shared within the same workspace/store instance.</li>
 * </ul>
 *
 * <p><b>Concurrency note:</b> for sandbox mode this is sequential-reuse sharing, not
 * live-instance sharing. Concurrent calls at the same scope each get their own running container;
 * they converge on the last persisted snapshot at the end of the call.
 */
public enum IsolationScope {

    /**
     * Isolate by session identifier.
     *
     * <p>This is the default behavior. Each distinct session gets its own sandbox state /
     * store namespace.  If no session key is present in the
     * {@link io.agentscope.core.agent.RuntimeContext}, state lookup is skipped and a fresh
     * sandbox is created (or a default store namespace is used).
     */
    SESSION,

    /**
     * Share across all sessions belonging to the same
     * {@link io.agentscope.core.agent.RuntimeContext#getUserId() userId}.
     *
     * <p>If {@code userId} is blank, a warning is logged and state lookup / namespace resolution
     * degrades to the default (fresh sandbox create, or an anonymous-user namespace).
     */
    USER,

    /**
     * Share across all users and sessions of the same agent (identified by agent name).
     *
     * <p>The agent name is fixed at build time and is always available; this scope never
     * degrades due to a missing context field.
     */
    AGENT,

    /**
     * One shared state / namespace globally within the same workspace store instance.
     *
     * <p>Use with care: all agents and users that share the same store will compete to write
     * the global slot.
     */
    GLOBAL
}
