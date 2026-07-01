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

import io.agentscope.extensions.oss.base.AbstractOssBaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 backed {@link BaseStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * S3Client s3 = S3Client.builder().region(Region.US_EAST_1).build();
 *
 * BaseStore store = AwsS3BaseStore.builder()
 *     .s3Client(s3)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/store/")
 *     .build();
 * }</pre>
 */
public class AwsS3BaseStore extends AbstractOssBaseStore {

    private AwsS3BaseStore(Builder builder) {
        super(
                new AwsS3OssAdapter(builder.s3Client, builder.bucketName),
                builder.bucketName,
                builder.keyPrefix);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private S3Client s3Client;
        private String bucketName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        public Builder s3Client(S3Client s3Client) {
            this.s3Client = s3Client;
            return this;
        }

        public Builder bucketName(String bucketName) {
            this.bucketName = bucketName;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public AwsS3BaseStore build() {
            return new AwsS3BaseStore(this);
        }
    }
}
