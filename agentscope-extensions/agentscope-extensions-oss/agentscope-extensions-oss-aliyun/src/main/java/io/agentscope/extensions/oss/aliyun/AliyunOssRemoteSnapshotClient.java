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
package io.agentscope.extensions.oss.aliyun;

import com.aliyun.oss.OSS;
import io.agentscope.extensions.oss.base.AbstractOssRemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;

/** {@link RemoteSnapshotClient} backed by Alibaba Cloud OSS. */
public class AliyunOssRemoteSnapshotClient extends AbstractOssRemoteSnapshotClient {

    /**
     * Creates an OSS-backed snapshot client.
     *
     * @param ossClient initialized OSS client
     * @param bucketName bucket for snapshot objects
     * @param keyPrefix object key prefix (optional, may be {@code null}/blank)
     */
    public AliyunOssRemoteSnapshotClient(OSS ossClient, String bucketName, String keyPrefix) {
        super(new AliyunOssAdapter(ossClient, bucketName), bucketName, keyPrefix);
    }
}
