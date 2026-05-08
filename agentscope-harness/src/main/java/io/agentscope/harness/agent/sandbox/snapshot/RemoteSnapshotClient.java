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

/**
 * User-implemented interface for uploading and downloading snapshot archives to/from remote
 * storage (e.g. S3, OSS, GCS, or a custom blob store).
 *
 * <p>Implement this interface and pass it to {@link RemoteSnapshotSpec} to enable
 * remote snapshot storage. The implementation is responsible for authentication,
 * retry logic, and connection management.
 */
public interface RemoteSnapshotClient {

    /**
     * Uploads a snapshot archive to remote storage.
     *
     * @param snapshotId unique identifier for this snapshot
     * @param data the workspace tar archive stream to upload
     * @throws Exception if the upload fails
     */
    void upload(String snapshotId, InputStream data) throws Exception;

    /**
     * Downloads a snapshot archive from remote storage.
     *
     * @param snapshotId unique identifier for the snapshot to download
     * @return an {@link InputStream} over the downloaded tar archive
     * @throws Exception if the download fails or the snapshot does not exist
     */
    InputStream download(String snapshotId) throws Exception;

    /**
     * Checks whether a snapshot with the given ID exists in remote storage.
     *
     * @param snapshotId unique identifier to check
     * @return {@code true} if the snapshot exists and can be downloaded
     * @throws Exception if the check fails
     */
    boolean exists(String snapshotId) throws Exception;
}
