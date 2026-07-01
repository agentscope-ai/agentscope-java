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
package io.agentscope.extensions.oss.tencent;

import com.qcloud.cos.COSClient;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.oss.base.AbstractOssAgentStateStore;

/**
 * Tencent Cloud COS backed {@link AgentStateStore}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * COSClient cosClient = new COSClient(cred, clientConfig);
 *
 * AgentStateStore store = TencentCosAgentStateStore.builder()
 *     .cosClient(cosClient)
 *     .bucketName("my-agentscope-bucket")
 *     .keyPrefix("agentscope/state/")
 *     .build();
 * }</pre>
 */
public class TencentCosAgentStateStore extends AbstractOssAgentStateStore {

    private TencentCosAgentStateStore(Builder builder) {
        super(
                new TencentCosOssAdapter(builder.cosClient, builder.bucketName),
                builder.bucketName,
                builder.keyPrefix);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private COSClient cosClient;
        private String bucketName;
        private String keyPrefix = DEFAULT_KEY_PREFIX;

        public Builder cosClient(COSClient cosClient) {
            this.cosClient = cosClient;
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

        public TencentCosAgentStateStore build() {
            return new TencentCosAgentStateStore(this);
        }
    }
}
