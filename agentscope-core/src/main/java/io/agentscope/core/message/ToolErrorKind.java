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
package io.agentscope.core.message;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Machine-readable classification of a tool failure.
 *
 * <p>Kinds are grouped by the caller's next action, not by exception type. They travel in {@link
 * ToolResultBlock} metadata (see {@link ToolErrorMetadata}); the model-visible text remains the
 * actionable explanation.
 *
 * <p>{@link #isRetriable()} is only a default suggestion for the {@code agentscope_retriable}
 * metadata key. It answers whether repeating the identical call may succeed; the framework never
 * retries on its own.
 */
public enum ToolErrorKind {
    /** Missing, blank, out-of-range, malformed, or illegal argument. */
    INVALID_ARGUMENT("invalid_argument", false),

    /** The target object does not exist: file, message, thread, skill, or agent. */
    NOT_FOUND("not_found", false),

    /** The target already exists or its version conflicts with the request. */
    CONFLICT("conflict", false),

    /** Policy, sandbox, path traversal, or user denial. */
    PERMISSION_DENIED("permission_denied", false),

    /** The capability exists but the current environment does not support it. */
    UNSUPPORTED("unsupported", false),

    /** Missing credentials, model, workspace, or other required configuration. */
    CONFIGURATION_ERROR("configuration_error", false),

    /** The operation exceeded its time limit. */
    TIMEOUT("timeout", true),

    /** The upstream service rate-limited the request. */
    RATE_LIMITED("rate_limited", true),

    /** Remote 4xx/5xx, network, protocol, or response parsing failure. */
    UPSTREAM_ERROR("upstream_error", true),

    /** Local file or storage read/write failure other than not-found/permission-denied. */
    IO_ERROR("io_error", false),

    /** A shell command returned a non-zero exit code. */
    COMMAND_FAILED("command_failed", false),

    /** Unexpected exception or framework defect. */
    INTERNAL_ERROR("internal_error", false);

    private final String value;
    private final boolean retriable;

    ToolErrorKind(String value, boolean retriable) {
        this.value = value;
        this.retriable = retriable;
    }

    /** Stable lowercase identifier used in metadata and evaluation aggregation. */
    @JsonValue
    public String getValue() {
        return value;
    }

    /** Default value for {@code agentscope_retriable}; callers may override per occurrence. */
    public boolean isRetriable() {
        return retriable;
    }

    /**
     * Resolves a metadata value back to a kind.
     *
     * @param value stable identifier, case-insensitive
     * @return the matching kind, or {@code null} when the value is unknown
     */
    public static ToolErrorKind fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ToolErrorKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        return null;
    }
}
