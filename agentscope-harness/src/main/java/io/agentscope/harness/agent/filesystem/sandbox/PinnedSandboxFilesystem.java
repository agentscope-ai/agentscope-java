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
import io.agentscope.harness.agent.sandbox.SandboxBackgroundWrites;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * acquire/release. A self-managed sandbox stops on release, so the mirror also takes a {@link
 * SandboxBackgroundWrites.Hold} that defers that release until the upload is done; if the
 * deferral budget runs out first, uploads are skipped instead of exec'ing into a stopped
 * container.
 */
public final class PinnedSandboxFilesystem extends SandboxBackedFilesystem {

    private static final Logger log = LoggerFactory.getLogger(PinnedSandboxFilesystem.class);

    private final SandboxBackgroundWrites.Hold hold;

    public PinnedSandboxFilesystem(Sandbox sandbox) {
        this(sandbox, null);
    }

    /**
     * @param sandbox the sandbox to pin
     * @param hold the hold keeping {@code sandbox} alive for this mirror, or {@code null}; the
     *     caller closes it when the mirror finishes
     */
    public PinnedSandboxFilesystem(Sandbox sandbox, SandboxBackgroundWrites.Hold hold) {
        Objects.requireNonNull(sandbox, "sandbox");
        super.setSandbox(sandbox);
        this.hold = hold;
    }

    @Override
    public synchronized void clearSandboxIfCurrent(Sandbox expected) {
        // Keep the pin for out-of-call mirror uploads.
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        if (hold != null && hold.isReleased()) {
            log.warn(
                    "[sandbox-fs] Skipping mirror upload of {} file(s): the pinned sandbox was"
                            + " released before the upload ran",
                    files.size());
            return files.stream()
                    .map(f -> FileUploadResponse.fail(f.getKey(), "Sandbox already released"))
                    .toList();
        }
        return super.uploadFiles(runtimeContext, files);
    }
}
