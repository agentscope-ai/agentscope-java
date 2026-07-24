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

import io.agentscope.extensions.oss.base.AbstractOssRemoteSnapshotClient;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import software.amazon.awssdk.services.s3.S3Client;

/** {@link RemoteSnapshotClient} backed by AWS S3. */
public class AwsS3RemoteSnapshotClient extends AbstractOssRemoteSnapshotClient {

    /**
     * Creates an S3-backed snapshot client.
     *
     * @param s3Client initialized S3 client
     * @param bucketName bucket for snapshot objects
     * @param keyPrefix object key prefix (optional, may be {@code null}/blank)
     */
    public AwsS3RemoteSnapshotClient(S3Client s3Client, String bucketName, String keyPrefix) {
        super(new AwsS3OssAdapter(s3Client, bucketName), bucketName, keyPrefix);
    }
}
