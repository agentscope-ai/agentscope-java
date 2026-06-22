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
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * Convenience {@link SandboxSnapshotSpec} for Tencent Cloud COS snapshot storage.
 */
public class CosSnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates a COS snapshot spec from an existing COS client.
     *
     * @param cosClient  initialized COS client
     * @param bucketName target bucket
     * @param keyPrefix  key prefix (optional, may be null/blank)
     */
    public CosSnapshotSpec(COSClient cosClient, String bucketName, String keyPrefix) {
        super(new CosRemoteSnapshotClient(cosClient, bucketName, keyPrefix));
    }
}
