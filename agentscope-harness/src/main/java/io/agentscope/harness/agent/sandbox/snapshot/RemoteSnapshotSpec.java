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

/**
 * Snapshot spec that creates {@link RemoteSandboxSnapshot} instances backed by a
 * {@link RemoteSnapshotClient}.
 *
 * <p>The same client instance is shared across all sessions created by this spec.
 * Implement {@link RemoteSnapshotClient} to connect to your remote storage backend
 * (e.g. AWS S3, Alibaba OSS, Google GCS).
 */
public class RemoteSnapshotSpec implements SandboxSnapshotSpec {

    private final RemoteSnapshotClient client;

    /**
     * Creates a remote snapshot spec.
     *
     * @param client the remote storage client implementation to use
     */
    public RemoteSnapshotSpec(RemoteSnapshotClient client) {
        this.client = client;
    }

    /**
     * {@inheritDoc}
     *
     * @return a new {@link RemoteSandboxSnapshot} using this spec's client
     */
    @Override
    public SandboxSnapshot build(String snapshotId) {
        return new RemoteSandboxSnapshot(client, snapshotId);
    }
}
