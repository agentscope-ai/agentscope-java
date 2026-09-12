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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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
 * acquire/release. Self-managed sandbox release takes an exclusive gate before shutdown, so an
 * upload either completes before release or is skipped without touching released resources.
 */
public final class PinnedSandboxFilesystem extends SandboxBackedFilesystem {

    private static final Map<Sandbox, MirrorGate> MIRROR_GATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Sandbox pinnedSandbox;
    private final MirrorGate mirrorGate;

    public PinnedSandboxFilesystem(Sandbox sandbox) {
        this.pinnedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.mirrorGate = gateFor(sandbox);
        super.setSandbox(sandbox);
    }

    /** Prevents new mirror uploads and waits for any in-flight upload before sandbox shutdown. */
    public static void markSandboxReleased(Sandbox sandbox) {
        if (sandbox != null) {
            gateFor(sandbox).markReleased();
        }
    }

    /**
     * Whether the sandbox pinned for an asynchronous mirror is still running.
     *
     * <p>A self-managed sandbox is stopped as part of call release. In that case a session
     * mirror is already best-effort and must not attempt a transfer against released resources.
     */
    public boolean isSandboxRunning() {
        mirrorGate.lock.readLock().lock();
        try {
            return !mirrorGate.released && pinnedSandbox.isRunning();
        } finally {
            mirrorGate.lock.readLock().unlock();
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        mirrorGate.lock.readLock().lock();
        try {
            if (mirrorGate.released || !pinnedSandbox.isRunning()) {
                List<FileUploadResponse> failed = new ArrayList<>(files.size());
                for (Map.Entry<String, byte[]> file : files) {
                    failed.add(FileUploadResponse.fail(file.getKey(), "Sandbox has been released"));
                }
                return failed;
            }
            return super.uploadFiles(runtimeContext, files);
        } finally {
            mirrorGate.lock.readLock().unlock();
        }
    }

    @Override
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        // Keep the pin for out-of-call mirror uploads.
    }

    private static MirrorGate gateFor(Sandbox sandbox) {
        synchronized (MIRROR_GATES) {
            return MIRROR_GATES.computeIfAbsent(sandbox, ignored -> new MirrorGate());
        }
    }

    private static final class MirrorGate {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private boolean released;

        private void markReleased() {
            lock.writeLock().lock();
            try {
                released = true;
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
}
