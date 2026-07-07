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
package io.agentscope.extensions.cos;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;

/**
 * {@link RemoteSnapshotClient} backed by Tencent Cloud COS.
 */
public class CosRemoteSnapshotClient implements RemoteSnapshotClient {

    private final COSClient cosClient;
    private final String bucketName;
    private final String keyPrefix;

    /**
     * Creates a COS-backed snapshot client.
     *
     * @param cosClient  initialized COS client
     * @param bucketName bucket for snapshot objects
     * @param keyPrefix  object key prefix (optional, may be null/blank)
     */
    public CosRemoteSnapshotClient(COSClient cosClient, String bucketName, String keyPrefix) {
        this.cosClient = Objects.requireNonNull(cosClient, "cosClient must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        byte[] bytes = data.readAllBytes();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        PutObjectRequest req =
                new PutObjectRequest(
                        bucketName,
                        objectKey(snapshotId),
                        new ByteArrayInputStream(bytes),
                        metadata);
        cosClient.putObject(req);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        String key = objectKey(snapshotId);
        try {
            cosClient.getObjectMetadata(bucketName, key);
        } catch (CosServiceException e) {
            if (e.getStatusCode() == 404) {
                throw new FileNotFoundException("Snapshot not found in COS: " + key);
            }
            throw e;
        }
        GetObjectRequest req = new GetObjectRequest(bucketName, key);
        return cosClient.getObject(req).getObjectContent();
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        try {
            cosClient.getObjectMetadata(bucketName, objectKey(snapshotId));
            return true;
        } catch (CosServiceException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private String objectKey(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return keyPrefix + snapshotId + ".tar";
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (!p.isEmpty() && !p.endsWith("/")) {
            p = p + "/";
        }
        return p;
    }
}
