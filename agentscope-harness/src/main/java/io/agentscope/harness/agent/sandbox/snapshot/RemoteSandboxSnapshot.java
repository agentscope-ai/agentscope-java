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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.sandbox.SandboxException;
import java.io.InputStream;

/**
 * Snapshot backed by a {@link RemoteSnapshotClient} (e.g. S3, OSS, GCS).
 *
 * <p>This class delegates all operations to the provided client. The client is responsible
 * for authentication, retry logic, and network error handling.
 *
 * <p>JSON serialization preserves only the {@code id}; the {@code client} is transient and
 * re-injected by the owning {@link RemoteSnapshotSpec} at resume time. The {@link JsonCreator}
 * constructor is used by Jackson; callers should use
 * {@link #RemoteSandboxSnapshot(RemoteSnapshotClient, String)} at runtime.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RemoteSandboxSnapshot implements SandboxSnapshot {

    @JsonIgnore private final RemoteSnapshotClient client;
    private final String id;

    /**
     * Creates a remote snapshot with a live client.
     *
     * @param client the remote storage client to delegate operations to
     * @param id unique identifier for this snapshot
     */
    public RemoteSandboxSnapshot(RemoteSnapshotClient client, String id) {
        this.client = client;
        this.id = id;
    }

    /**
     * Deserialization constructor — restores the snapshot from persisted state.
     *
     * <p>The {@code client} is {@code null} after deserialization; it is re-injected by
     * {@link RemoteSnapshotSpec#build(String)} when the sandbox resumes.
     *
     * @param id unique identifier for this snapshot
     */
    @JsonCreator
    public RemoteSandboxSnapshot(@JsonProperty("id") String id) {
        this.client = null;
        this.id = id;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uploads the archive via {@link RemoteSnapshotClient#upload}.
     *
     * @throws SandboxException.SnapshotException if client is null (snapshot not yet hydrated)
     */
    @Override
    public void persist(InputStream workspaceArchive) throws Exception {
        if (client == null) {
            throw new SandboxException.SnapshotException(
                    id + ": RemoteSnapshotClient not available");
        }
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
     *
     * @throws SandboxException.SnapshotException if client is null (snapshot not yet hydrated)
     */
    @JsonIgnore
    @Override
    public InputStream restore() throws Exception {
        if (client == null) {
            throw new SandboxException.SnapshotException(
                    id + ": RemoteSnapshotClient not available");
        }
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
     * Returns {@code false} when the client is null (snapshot not yet hydrated after
     * deserialization), causing the sandbox start logic to degrade to a fresh initialisation.
     */
    @JsonIgnore
    @Override
    public boolean isRestorable() throws Exception {
        if (client == null) {
            return false;
        }
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

    /**
     * Returns the remote storage client, or {@code null} when this instance was restored from
     * persisted state and the client has not yet been re-injected.
     *
     * @return the client, or {@code null} after deserialization
     */
    @JsonIgnore
    public RemoteSnapshotClient getClient() {
        return client;
    }

    @JsonIgnore
    @Override
    public String getType() {
        return "remote";
    }
}
