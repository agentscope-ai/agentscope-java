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

package io.agentscope.core.mcp.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Optional;

/**
 * Base class for JSON-RPC 2.0 messages.
 *
 * <p>Represents both requests and responses as per JSON-RPC 2.0 specification.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION, defaultImpl = JsonRpcRequest.class)
@JsonSubTypes({
    @JsonSubTypes.Type(JsonRpcRequest.class),
    @JsonSubTypes.Type(JsonRpcResponse.class),
    @JsonSubTypes.Type(JsonRpcNotification.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class JsonRpcMessage {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    protected JsonRpcMessage() {}

    protected JsonRpcMessage(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    /**
     * Get the message ID if this is a request or response.
     *
     * @return optional ID
     */
    @JsonIgnore
    public abstract Optional<Object> getId();

    /**
     * Get the method name if this is a request or notification.
     *
     * @return optional method name
     */
    @JsonIgnore
    public abstract Optional<String> getMethod();
}
