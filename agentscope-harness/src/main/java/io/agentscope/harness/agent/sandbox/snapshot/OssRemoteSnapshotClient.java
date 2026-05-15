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

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;

/**
 * {@link RemoteSnapshotClient} backed by Alibaba Cloud OSS.
 */
public class OssRemoteSnapshotClient implements RemoteSnapshotClient {

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    /**
     * Creates an OSS-backed snapshot client.
     *
     * @param ossClient initialized OSS client
     * @param bucketName bucket for snapshot objects
     * @param keyPrefix object key prefix (optional, may be null/blank)
     */
    public OssRemoteSnapshotClient(OSS ossClient, String bucketName, String keyPrefix) {
        this.ossClient = Objects.requireNonNull(ossClient, "ossClient must not be null");
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        this.bucketName = bucketName;
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        ossClient.putObject(bucketName, objectKey(snapshotId), data);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        String key = objectKey(snapshotId);
        if (!ossClient.doesObjectExist(bucketName, key)) {
            throw new FileNotFoundException("Snapshot not found in OSS: " + key);
        }
        OSSObject object = ossClient.getObject(bucketName, key);
        return object.getObjectContent();
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        return ossClient.doesObjectExist(bucketName, objectKey(snapshotId));
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
