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
import com.aliyun.oss.OSSClientBuilder;

/**
 * Convenience {@link SandboxSnapshotSpec} for Alibaba Cloud OSS snapshot storage.
 */
public class OssSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates an OSS snapshot spec from an existing OSS client.
     *
     * @param ossClient initialized OSS client
     * @param bucketName target bucket
     * @param keyPrefix key prefix (optional, may be null/blank)
     */
    public OssSnapshotSpec(OSS ossClient, String bucketName, String keyPrefix) {
        super(new OssRemoteSnapshotClient(ossClient, bucketName, keyPrefix));
    }

    /**
     * Creates an OSS snapshot spec from endpoint/credential settings.
     *
     * @param endpoint OSS endpoint (e.g. oss-cn-hangzhou.aliyuncs.com)
     * @param accessKeyId access key id
     * @param accessKeySecret access key secret
     * @param bucketName target bucket
     * @param keyPrefix key prefix (optional, may be null/blank)
     */
    public OssSnapshotSpec(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            String bucketName,
            String keyPrefix) {
        this(
                new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret),
                bucketName,
                keyPrefix);
    }
}
