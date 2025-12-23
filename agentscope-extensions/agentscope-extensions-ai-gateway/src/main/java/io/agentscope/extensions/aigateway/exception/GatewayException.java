/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.aigateway.exception;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Exception thrown when AI Gateway operations fail.
 *
 * <p>Provides structured error information including error code, message, and optional context
 * details for debugging.
 */
public class GatewayException extends RuntimeException {

    private final String errorCode;
    private final Map<String, Object> context;

    /**
     * Creates a new GatewayException with a message.
     *
     * @param message the error message
     */
    public GatewayException(String message) {
        super(message);
        this.errorCode = "GATEWAY_ERROR";
        this.context = Collections.emptyMap();
    }

    /**
     * Creates a new GatewayException with a message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public GatewayException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "GATEWAY_ERROR";
        this.context = Collections.emptyMap();
    }

    /**
     * Creates a new GatewayException with an error code and message.
     *
     * @param errorCode the error code for categorization
     * @param message the error message
     */
    public GatewayException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = Collections.emptyMap();
    }

    /**
     * Creates a new GatewayException with an error code, message, and cause.
     *
     * @param errorCode the error code for categorization
     * @param message the error message
     * @param cause the underlying cause
     */
    public GatewayException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = Collections.emptyMap();
    }

    /**
     * Creates a new GatewayException with full details including context.
     *
     * @param errorCode the error code for categorization
     * @param message the error message
     * @param cause the underlying cause (can be null)
     * @param context additional context information for debugging
     */
    public GatewayException(
            String errorCode, String message, Throwable cause, Map<String, Object> context) {
        super(message, cause);
        this.errorCode = errorCode;
        this.context = context != null ? new HashMap<>(context) : Collections.emptyMap();
    }

    /**
     * Returns the error code.
     *
     * @return the error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the context map containing additional debugging information.
     *
     * @return unmodifiable map of context information
     */
    public Map<String, Object> getContext() {
        return Collections.unmodifiableMap(context);
    }

    /**
     * Creates a builder for constructing GatewayException with fluent API.
     *
     * @param errorCode the error code
     * @param message the error message
     * @return a new builder instance
     */
    public static Builder builder(String errorCode, String message) {
        return new Builder(errorCode, message);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GatewayException{");
        sb.append("errorCode='").append(errorCode).append('\'');
        sb.append(", message='").append(getMessage()).append('\'');
        if (!context.isEmpty()) {
            sb.append(", context=").append(context);
        }
        if (getCause() != null) {
            sb.append(", cause=").append(getCause().getClass().getSimpleName());
        }
        sb.append('}');
        return sb.toString();
    }

    /** Builder for creating GatewayException with context information. */
    public static class Builder {
        private final String errorCode;
        private final String message;
        private Throwable cause;
        private final Map<String, Object> context = new HashMap<>();

        private Builder(String errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        /**
         * Sets the underlying cause.
         *
         * @param cause the cause
         * @return this builder
         */
        public Builder cause(Throwable cause) {
            this.cause = cause;
            return this;
        }

        /**
         * Adds a context entry.
         *
         * @param key the context key
         * @param value the context value
         * @return this builder
         */
        public Builder context(String key, Object value) {
            this.context.put(key, value);
            return this;
        }

        /**
         * Adds the endpoint to context.
         *
         * @param endpoint the endpoint URL
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            return context("endpoint", endpoint);
        }

        /**
         * Adds the tool name to context.
         *
         * @param toolName the tool name
         * @return this builder
         */
        public Builder toolName(String toolName) {
            return context("toolName", toolName);
        }

        /**
         * Adds the gateway ID to context.
         *
         * @param gatewayId the gateway ID
         * @return this builder
         */
        public Builder gatewayId(String gatewayId) {
            return context("gatewayId", gatewayId);
        }

        /**
         * Builds the GatewayException.
         *
         * @return the constructed exception
         */
        public GatewayException build() {
            return new GatewayException(errorCode, message, cause, context);
        }
    }
}
