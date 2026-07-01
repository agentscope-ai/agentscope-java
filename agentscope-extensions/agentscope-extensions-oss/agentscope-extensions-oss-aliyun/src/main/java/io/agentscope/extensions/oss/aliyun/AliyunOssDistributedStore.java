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
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * Alibaba Cloud OSS-backed {@link DistributedStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(AliyunOssDistributedStore.create(ossClient, "my-bucket", "agentscope/"))
 *     .build();
 * }</pre>
 */
public class AliyunOssDistributedStore implements DistributedStore {

    private final OSS ossClient;
    private final String bucketName;
    private final String keyPrefix;

    private AliyunOssDistributedStore(OSS ossClient, String bucketName, String keyPrefix) {
        this.ossClient = Objects.requireNonNull(ossClient, "ossClient");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    /**
     * Creates an OSS distributed store.
     *
     * @param ossClient initialized OSS client
     * @param bucketName target bucket name
     * @param keyPrefix object key prefix (e.g. {@code "agentscope/"})
     * @return a new OSS distributed store
     */
    public static AliyunOssDistributedStore create(
            OSS ossClient, String bucketName, String keyPrefix) {
        return new AliyunOssDistributedStore(ossClient, bucketName, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return AliyunOssAgentStateStore.builder()
                .ossClient(ossClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return AliyunOssBaseStore.builder()
                .ossClient(ossClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new AliyunOssSnapshotSpec(ossClient, bucketName, keyPrefix + "snapshot/");
    }
}
