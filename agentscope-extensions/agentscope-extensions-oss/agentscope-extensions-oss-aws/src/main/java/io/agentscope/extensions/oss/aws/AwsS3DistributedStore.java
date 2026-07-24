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
package io.agentscope.extensions.oss.aws;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3-backed {@link DistributedStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(AwsS3DistributedStore.create(s3, "my-bucket", "agentscope/"))
 *     .build();
 * }</pre>
 */
public class AwsS3DistributedStore implements DistributedStore {

    private final S3Client s3Client;
    private final String bucketName;
    private final String keyPrefix;

    private AwsS3DistributedStore(S3Client s3Client, String bucketName, String keyPrefix) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    /**
     * Creates an S3 distributed store.
     *
     * @param s3Client initialized S3 client
     * @param bucketName target bucket name
     * @param keyPrefix object key prefix (e.g. {@code "agentscope/"})
     * @return a new S3 distributed store
     */
    public static AwsS3DistributedStore create(
            S3Client s3Client, String bucketName, String keyPrefix) {
        return new AwsS3DistributedStore(s3Client, bucketName, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return AwsS3AgentStateStore.builder()
                .s3Client(s3Client)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return AwsS3BaseStore.builder()
                .s3Client(s3Client)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new AwsS3SnapshotSpec(s3Client, bucketName, keyPrefix + "snapshot/");
    }
}
