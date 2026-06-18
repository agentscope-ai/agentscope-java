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

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3-compatible object storage-backed {@link DistributedStore}.
 */
public class OssDistributedStore implements DistributedStore {

    private final S3Client s3Client;
    private final String bucketName;
    private final String keyPrefix;

    private OssDistributedStore(S3Client s3Client, String bucketName, String keyPrefix) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    public static OssDistributedStore create(
            S3Client s3Client, String bucketName, String keyPrefix) {
        return new OssDistributedStore(s3Client, bucketName, keyPrefix);
    }

    public static OssDistributedStore create(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            String bucketName,
            String keyPrefix) {
        return create(
                S3ObjectStoreSupport.buildClient(
                        java.net.URI.create(endpoint), accessKeyId, accessKeySecret),
                bucketName,
                keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return OssAgentStateStore.builder()
                .s3Client(s3Client)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return OssBaseStore.builder()
                .s3Client(s3Client)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new OssSnapshotSpec(s3Client, bucketName, keyPrefix + "snapshot/");
    }
}
