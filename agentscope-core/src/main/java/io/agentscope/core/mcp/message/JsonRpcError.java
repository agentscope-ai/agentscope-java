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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 Error object.
 *
 * <p>Represents an error in a JSON-RPC response.
 */
public class JsonRpcError {

    @JsonProperty("code")
    private int code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private Object data;

    public JsonRpcError() {}

    public JsonRpcError(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public JsonRpcError(int code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }

    // JSON-RPC 2.0 Error Codes
    public static class ErrorCode {
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
        public static final int SERVER_ERROR_START = -32099;
        public static final int SERVER_ERROR_END = -32000;
    }

    @Override
    public String toString() {
        return "JsonRpcError{"
                + "code="
                + code
                + ", message='"
                + message
                + '\''
                + ", data="
                + data
                + '}';
    }
}
