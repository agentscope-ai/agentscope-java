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

package io.agentscope.core.mcp.schema;

/**
 * A uniquely identifying ID for a request in JSON-RPC.
 *
 * <p>The MCP and JSON-RPC specifications allow request ids to be either a string or an
 * integer value. This type models that union explicitly and validates values at construction
 * time.
 */
public record RequestId(Object value) {

    /**
     * Creates a request id and validates that the value is either a string or an integral
     * numeric type.
     */
    public RequestId {
        if (value == null) {
            throw new IllegalArgumentException("Request id value must not be null");
        }
        if (!(value instanceof String)) {
            if (!(value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long)) {
                throw new IllegalArgumentException(
                        "Request id value must be a String or an integral numeric type");
            }
        }
    }

    /**
     * Creates a numeric request id.
     *
     * @param numValue the numeric id value
     * @return a request id wrapping the numeric value
     */
    public static RequestId numeric(long numValue) {
        return new RequestId(numValue);
    }

    /**
     * Creates a string request id.
     *
     * @param strValue the string id value
     * @return a request id wrapping the string value
     */
    public static RequestId string(String strValue) {
        return new RequestId(strValue);
    }

    /**
     * Returns whether this request id contains a string value.
     *
     * @return {@code true} if the wrapped value is a string; otherwise {@code false}
     */
    public boolean isString() {
        return value instanceof String;
    }

    /**
     * Returns whether this request id contains a numeric value.
     *
     * @return {@code true} if the wrapped value is numeric; otherwise {@code false}
     */
    public boolean isNumber() {
        return value instanceof Number;
    }

    /**
     * Returns the wrapped value as a string.
     *
     * @return the string request id value
     * @throws IllegalStateException if this request id does not contain a string
     */
    public String asString() {
        if (!isString()) {
            throw new IllegalStateException("Request id does not contain a string value");
        }
        return (String) value;
    }

    /**
     * Returns the wrapped value as a long.
     *
     * @return the numeric request id value
     * @throws IllegalStateException if this request id does not contain a numeric value
     */
    public long asLong() {
        if (!isNumber()) {
            throw new IllegalStateException("Request id does not contain a numeric value");
        }
        return ((Number) value).longValue();
    }
}
