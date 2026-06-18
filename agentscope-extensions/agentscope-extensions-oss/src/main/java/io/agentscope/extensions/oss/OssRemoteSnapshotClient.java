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
package io.agentscope.extensions.oss;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/**
 * {@link RemoteSnapshotClient} backed by S3-compatible object storage.
 */
public class OssRemoteSnapshotClient implements RemoteSnapshotClient {

    private final S3Client s3Client;
    private final String bucketName;
    private final String keyPrefix;

    public OssRemoteSnapshotClient(S3Client s3Client, String bucketName, String keyPrefix) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = S3ObjectStoreSupport.normalizePrefix(keyPrefix, "");
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        s3Client.putObject(
                builder -> builder.bucket(bucketName).key(objectKey(snapshotId)).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        String key = objectKey(snapshotId);
        if (!exists(snapshotId)) {
            throw new FileNotFoundException("Snapshot not found in object storage: " + key);
        }
        ResponseBytes<GetObjectResponse> bytes =
                s3Client.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(bucketName).key(key).build());
        return new ByteArrayInputStream(bytes.asByteArray());
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        return S3ObjectStoreSupport.hasObjectsWithPrefix(
                s3Client, bucketName, objectKey(snapshotId));
    }

    private String objectKey(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return keyPrefix + snapshotId + ".tar";
    }
}
