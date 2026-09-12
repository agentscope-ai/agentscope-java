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
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(PinnedSandboxFilesystem.class);
    private static final long RELEASE_GATE_TIMEOUT_MILLIS = 1_000;
    private static final ReferenceQueue<Sandbox> STALE_SANDBOXES = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, MirrorGate> MIRROR_GATES = new HashMap<>();

    private final Sandbox pinnedSandbox;
    private final MirrorGate mirrorGate;

    public PinnedSandboxFilesystem(Sandbox sandbox) {
        this.pinnedSandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.mirrorGate = gateFor(sandbox);
        super.setSandbox(sandbox);
    }

    /**
     * Starts a fresh mirror-gate generation for a newly acquired sandbox.
     *
     * <p>A manager may reuse the same {@link Sandbox} object for a later call. Replacing rather
     * than reopening the old gate keeps mirrors from the previous call permanently released while
     * ensuring they cannot attach themselves to the new call's lifecycle.
     */
    public static void markSandboxAcquired(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        synchronized (MIRROR_GATES) {
            expungeStaleGates();
            MIRROR_GATES.put(new IdentityWeakReference(sandbox, STALE_SANDBOXES), new MirrorGate());
        }
    }

    /** Prevents new mirror uploads and briefly waits for an in-flight upload before shutdown. */
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

    /** Returns whether this pinned filesystem's call generation has been released. */
    public boolean isSandboxReleased() {
        return mirrorGate.released;
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
            expungeStaleGates();
            IdentityWeakReference lookup = new IdentityWeakReference(sandbox);
            MirrorGate gate = MIRROR_GATES.get(lookup);
            if (gate == null) {
                gate = new MirrorGate();
                MIRROR_GATES.put(new IdentityWeakReference(sandbox, STALE_SANDBOXES), gate);
            }
            return gate;
        }
    }

    private static void expungeStaleGates() {
        IdentityWeakReference stale;
        while ((stale = (IdentityWeakReference) STALE_SANDBOXES.poll()) != null) {
            MIRROR_GATES.remove(stale);
        }
    }

    private static final class MirrorGate {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile boolean released;

        private void markReleased() {
            // Publish the latch before waiting so new uploads are rejected even if an existing
            // remote transfer is wedged. Release remains bounded because mirrors are best-effort.
            released = true;
            boolean acquired = false;
            try {
                acquired =
                        lock.writeLock()
                                .tryLock(RELEASE_GATE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    log.warn(
                            "Mirror upload still in flight after {} ms; releasing sandbox",
                            RELEASE_GATE_TIMEOUT_MILLIS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for an in-flight sandbox mirror upload");
            } finally {
                if (acquired) {
                    lock.writeLock().unlock();
                }
            }
        }
    }

    /** Weak key whose equality follows object identity rather than {@code equals/hashCode}. */
    private static final class IdentityWeakReference extends WeakReference<Sandbox> {
        private final int identityHash;

        private IdentityWeakReference(Sandbox sandbox) {
            super(sandbox);
            this.identityHash = System.identityHashCode(sandbox);
        }

        private IdentityWeakReference(
                Sandbox sandbox, ReferenceQueue<? super Sandbox> referenceQueue) {
            super(sandbox, referenceQueue);
            this.identityHash = System.identityHashCode(sandbox);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityWeakReference that)) {
                return false;
            }
            Sandbox sandbox = get();
            return sandbox != null && sandbox == that.get();
        }
    }
}
