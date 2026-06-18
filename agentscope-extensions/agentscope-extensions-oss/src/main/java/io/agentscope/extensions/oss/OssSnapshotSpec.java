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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import java.net.URI;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Convenience {@link io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec} for
 * S3-compatible snapshot storage.
 */
public class OssSnapshotSpec extends RemoteSnapshotSpec {

    public OssSnapshotSpec(S3Client s3Client, String bucketName, String keyPrefix) {
        super(new OssRemoteSnapshotClient(s3Client, bucketName, keyPrefix));
    }

    public OssSnapshotSpec(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            String bucketName,
            String keyPrefix) {
        this(
                S3ObjectStoreSupport.buildClient(
                        URI.create(endpoint), Region.US_EAST_1, accessKeyId, accessKeySecret),
                bucketName,
                keyPrefix);
    }
}
