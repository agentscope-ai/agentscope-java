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
package io.agentscope.extensions.oss.base;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.InputStream;
import java.util.Objects;

/**
 * Vendor-independent implementation of {@link RemoteSnapshotClient} on top of an
 * {@link OssAdapter}.
 *
 * <p>Snapshot archives are stored as {@code {keyPrefix}{snapshotId}.tar} objects.
 */
public abstract class AbstractOssRemoteSnapshotClient implements RemoteSnapshotClient {

    protected static final String SNAPSHOT_SUFFIX = ".tar";

    protected final OssAdapter adapter;
    protected final String bucketName;
    protected final String keyPrefix;

    /**
     * @param adapter vendor-specific OSS adapter
     * @param bucketName bucket in which snapshots are stored (must be non-blank)
     * @param keyPrefix optional prefix; empty string when {@code null} or blank
     */
    protected AbstractOssRemoteSnapshotClient(
            OssAdapter adapter, String bucketName, String keyPrefix) {
        this.adapter = Objects.requireNonNull(adapter, "adapter must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = PathUtils.normalizePrefix(keyPrefix, "");
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        // Snapshot archives can reach hundreds of MB (whole sandbox workspace tar). Delegate to
        // putStream so the vendor adapter streams to the network (or spools to disk) instead of
        // buffering the whole payload — putBytes(readAllBytes()) would OOM on large workspaces.
        adapter.putStream(objectKey(snapshotId), data);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        return adapter.openStream(objectKey(snapshotId));
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        return adapter.exists(objectKey(snapshotId));
    }

    protected String objectKey(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return keyPrefix + snapshotId + SNAPSHOT_SUFFIX;
    }
}
