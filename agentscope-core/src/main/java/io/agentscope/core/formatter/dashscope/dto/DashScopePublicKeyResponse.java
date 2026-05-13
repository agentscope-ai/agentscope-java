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
package io.agentscope.core.formatter.dashscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DashScope public key response DTO.
 *
 * <p>This class represents the response structure from DashScope's public key API.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "request_id": "xxx-xxx-xxx",
 *   "data": {
 *     "public_key": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnojrB579xgPQN5f46SvoRAiQBPWBaPzWh7hp51fWI+OsQk7KqH0qMcw8i0eK5rfOvJIPujOQgnes1ph9/gKAst9NzXVIl9JJYUSPtzTvOabhp4yvS3KBf9g3xHYVjYgW33SOY74Ue/tgbCXn717rV6gXb4sVvq9XK/1BrDcGbEOQEZEgBTFkm/g3lpWLQtACwwqHffoA9eQtkkz15ZFKosAgbR8LedfIvxAl2zk15REzxXiRcFgc9/tLF0U1t2Sxt9FkQefxYwn6EZawTsRJvf4kqF3MaPdTcDbOp0iSNvCl2qzPSf/F+Oll2CUM1tFAEu81oa4l0WaDR3UtvqOtyQIDAQAB",
 *     "public_key_id": "1"
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopePublicKeyResponse {

    /** Unique request identifier. */
    @JsonProperty("request_id")
    private String requestId;

    /** Public key data. */
    @JsonProperty("data")
    private PublicKeyData data;

    /** Error code (if request failed). */
    @JsonProperty("code")
    private String code;

    /** Error message (if request failed). */
    @JsonProperty("message")
    private String message;

    public DashScopePublicKeyResponse() {}

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public PublicKeyData getData() {
        return data;
    }

    public void setData(PublicKeyData data) {
        this.data = data;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * Check if this response represents an error.
     *
     * @return true if the response contains an error code
     */
    public boolean isError() {
        return code != null && !code.isEmpty();
    }

    /**
     * Public key data containing the key ID and key value.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PublicKeyData {

        /** RSA public key value (Base64-encoded). */
        @JsonProperty("public_key")
        private String publicKey;

        /** RSA public key ID. */
        @JsonProperty("public_key_id")
        private String publicKeyId;

        public PublicKeyData() {}

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getPublicKeyId() {
            return publicKeyId;
        }

        public void setPublicKeyId(String publicKeyId) {
            this.publicKeyId = publicKeyId;
        }
    }
}
