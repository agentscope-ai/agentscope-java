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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

/**
 * DashScope API response DTO.
 *
 * <p>This class represents the top-level response structure from DashScope's
 * text-generation and multimodal-generation APIs.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "request_id": "xxx-xxx-xxx",
 *   "output": {
 *     "choices": [...]
 *   },
 *   "usage": {
 *     "input_tokens": 10,
 *     "output_tokens": 20
 *   }
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DashScopeResponse {

    /** Unique request identifier. */
    @JsonProperty("request_id")
    private String requestId;

    /** The output containing choices. */
    @JsonProperty("output")
    @JsonDeserialize(using = DashScopeOutputDeserializer.class)
    private DashScopeOutput output;

    /** Token usage statistics. */
    @JsonProperty("usage")
    private DashScopeUsage usage;

    /** Error code (if request failed). */
    @JsonProperty("code")
    private String code;

    /** Error message (if request failed). */
    @JsonProperty("message")
    private String message;

    public DashScopeResponse() {}

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public DashScopeOutput getOutput() {
        return output;
    }

    public void setOutput(DashScopeOutput output) {
        this.output = output;
    }

    public DashScopeUsage getUsage() {
        return usage;
    }

    public void setUsage(DashScopeUsage usage) {
        this.usage = usage;
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
     * Custom deserializer for DashScopeOutput that handles both string (encrypted) and object
     * (decrypted) values.
     *
     * <p>When the output field is a string (encrypted), it returns null since the value should
     * have been decrypted by decryptResponse() before deserialization. If it's still a string at
     * this point, it means decryption failed or wasn't performed, so we skip it.
     */
    static class DashScopeOutputDeserializer extends JsonDeserializer<DashScopeOutput> {

        @Override
        public DashScopeOutput deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            } else if (token == JsonToken.VALUE_STRING) {
                // String value indicates encrypted output that wasn't decrypted.
                // Skip the string value (read and discard) and return null.
                // The decryptResponse() method should have handled this, but if decryption
                // failed or wasn't performed, we gracefully handle it here.
                p.getValueAsString(); // Read and skip the string value
                return null;
            } else if (token == JsonToken.START_OBJECT) {
                // Object value - normal deserialization
                // Use readTree and treeToValue for standard deserialization
                // This handles the case where output is a decrypted JSON object
                JsonNode node = p.getCodec().readTree(p);
                if (node == null || node.isNull() || !node.isObject()) {
                    return null;
                }
                return p.getCodec().treeToValue(node, DashScopeOutput.class);
            }
            // Unexpected token type
            throw new IOException(
                    "Cannot deserialize DashScopeOutput from token: "
                            + token
                            + ". Expected object or string.");
        }
    }
}
