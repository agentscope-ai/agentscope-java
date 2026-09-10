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
package io.agentscope.harness.agent.filesystem.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.Objects;

/**
 * {@link SandboxBackedFilesystem} that holds a fixed {@link Sandbox} reference for the lifetime of
 * the instance.
 *
 * <p>Used by asynchronous session mirrors so uploads can complete after the agent call releases
 * its per-call binding on the shared agent proxy. Unlike the agent-level {@link
 * SandboxBackedFilesystem}, {@link #clearSandboxIfCurrent} is a no-op — clearing the call proxy
 * must not unpin this mirror filesystem.
 *
 * <p>Safe for DataAgent-style <em>user-managed</em> sandboxes that stay alive across
 * acquire/release. For self-managed sandboxes, pair with {@link
 * io.agentscope.harness.agent.sandbox.SandboxMirrorReleaseCoordinator} so call teardown defers
 * {@code stop}/{@code shutdown} until outstanding pinned mirror tasks finish.
 */
public final class PinnedSandboxFilesystem extends SandboxBackedFilesystem {

    public PinnedSandboxFilesystem(Sandbox sandbox) {
        Objects.requireNonNull(sandbox, "sandbox");
        super.setSandbox(sandbox);
    }

    @Override
    protected boolean softFailWhenStopped() {
        // Async mirrors must not crash the mirror thread when call teardown already stopped the
        // sandbox; sync tool paths keep the hard-fail default on SandboxBackedFilesystem.
        return true;
    }

    @Override
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        // Keep the pin for out-of-call mirror uploads.
    }
}
