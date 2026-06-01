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

import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.InputStream;

/**
 * Snapshot backed by a {@link RemoteSnapshotClient} (e.g. S3, OSS, GCS).
 *
 * <p>This class delegates all operations to the provided client. The client is responsible
 * for authentication, retry logic, and network error handling.
 *
 * <p>Note: {@code RemoteSandboxSnapshot} is not directly serializable to JSON because
 * {@link RemoteSnapshotClient} cannot be serialized. When persisting session state,
 * only the {@code id} is needed — the client is re-injected from the builder at resume time.
 */
public class RemoteSandboxSnapshot implements SandboxSnapshot {

    private final RemoteSnapshotClient client;
    private final String id;

    /**
     * Creates a remote snapshot.
     *
     * @param client the remote storage client to delegate operations to
     * @param id unique identifier for this snapshot
     */
    public RemoteSandboxSnapshot(RemoteSnapshotClient client, String id) {
        this.client = client;
        this.id = id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uploads the archive via {@link RemoteSnapshotClient#upload}.
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        try {
            client.upload(id, workspaceArchive);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote upload failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Downloads the archive via {@link RemoteSnapshotClient#download}.
     */
    @Override
    public InputStream restore() throws Exception {
        try {
            return client.download(id);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote download failed", e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Checks existence via {@link RemoteSnapshotClient#exists}.
     */
    @Override
    public boolean isRestorable() throws Exception {
        try {
            return client.exists(id);
        } catch (Exception e) {
            throw new SandboxException.SnapshotException(id, "Remote exists check failed", e);
        }
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getType() {
        return "remote";
    }
}
