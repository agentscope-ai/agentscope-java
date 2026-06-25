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
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.Objects;

/**
 * Tencent Cloud COS-backed {@link DistributedStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * COSClient cosClient = new COSClient(cred, clientConfig);
 *
 * HarnessAgent agent = HarnessAgent.builder()
 *     .name("my-agent")
 *     .model("dashscope:qwen-plus")
 *     .distributedStore(CosDistributedStore.create(cosClient, "my-bucket", "agentscope/"))
 *     .build();
 * }</pre>
 */
public class CosDistributedStore implements DistributedStore {

    private final COSClient cosClient;
    private final String bucketName;
    private final String keyPrefix;

    private CosDistributedStore(COSClient cosClient, String bucketName, String keyPrefix) {
        this.cosClient = Objects.requireNonNull(cosClient, "cosClient");
        this.bucketName = Objects.requireNonNull(bucketName, "bucketName");
        this.keyPrefix = keyPrefix != null ? keyPrefix : "agentscope/";
    }

    /**
     * Creates a COS distributed store.
     *
     * @param cosClient  initialized COS client
     * @param bucketName target bucket name
     * @param keyPrefix  object key prefix (e.g. {@code "agentscope/"})
     * @return a new COS distributed store
     */
    public static CosDistributedStore create(
            COSClient cosClient, String bucketName, String keyPrefix) {
        return new CosDistributedStore(cosClient, bucketName, keyPrefix);
    }

    @Override
    public AgentStateStore agentStateStore() {
        return CosAgentStateStore.builder()
                .cosClient(cosClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "state/")
                .build();
    }

    @Override
    public BaseStore baseStore() {
        return CosBaseStore.builder()
                .cosClient(cosClient)
                .bucketName(bucketName)
                .keyPrefix(keyPrefix + "store/")
                .build();
    }

    @Override
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new CosSnapshotSpec(cosClient, bucketName, keyPrefix + "snapshot/");
    }
}
