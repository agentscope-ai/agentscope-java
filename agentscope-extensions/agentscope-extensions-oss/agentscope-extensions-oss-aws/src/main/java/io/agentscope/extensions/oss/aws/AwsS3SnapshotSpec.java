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

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import software.amazon.awssdk.services.s3.S3Client;

/** Convenience {@link SandboxSnapshotSpec} for AWS S3 snapshot storage. */
public class AwsS3SnapshotSpec extends RemoteSnapshotSpec {

    /**
     * Creates an S3 snapshot spec from an existing S3 client.
     *
     * @param s3Client initialized S3 client
     * @param bucketName target bucket
     * @param keyPrefix key prefix (optional, may be null/blank)
     */
    public AwsS3SnapshotSpec(S3Client s3Client, String bucketName, String keyPrefix) {
        super(new AwsS3RemoteSnapshotClient(s3Client, bucketName, keyPrefix));
    }
}
