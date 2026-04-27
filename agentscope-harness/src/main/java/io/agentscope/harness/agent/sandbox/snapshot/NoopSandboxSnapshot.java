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
package io.agentscope.harness.agent.sandbox.snapshot;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * No-op snapshot that does not persist anything.
 *
 * <p>When using {@code NoopSandboxSnapshot}, workspace state is NOT preserved between
 * session stops. Each time a session is started fresh, the full manifest is applied
 * (Branch D of the start logic). Use this when workspace durability is not required.
 */
public class NoopSandboxSnapshot implements SandboxSnapshot {

    private static final String ID = "noop";

    /** Creates a noop snapshot. */
    public NoopSandboxSnapshot() {}

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code false} — workspace archiving is skipped entirely when this
     * snapshot is in use, so this method is never called in normal operation.
     */
    @Override
    public boolean isPersistenceEnabled() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation discards the archive stream entirely.
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        // Intentionally discard — no-op snapshot does not persist anything
        if (workspaceArchive != null) {
            workspaceArchive.transferTo(OutputStream.nullOutputStream());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always throws {@link io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException}
     * since noop snapshots are never restorable.
     */
    @Override
    public InputStream restore() throws Exception {
        throw new io.agentscope.harness.agent.sandbox.SandboxException.SnapshotException(ID);
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@code false}
     */
    @Override
    public boolean isRestorable() {
        return false;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return "noop";
    }
}
