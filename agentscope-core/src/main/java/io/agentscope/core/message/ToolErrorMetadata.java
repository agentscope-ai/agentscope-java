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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Metadata keys and helpers for machine-readable tool failure classification.
 *
 * <p>Attach the result to {@link ToolResultBlock#withMetadata(Map)}:
 *
 * <pre>{@code
 * return ToolResultBlock.error("startLine must be >= 1 (1-based); got 0.")
 *         .withMetadata(ToolErrorMetadata.of(ToolErrorKind.INVALID_ARGUMENT, path));
 * }</pre>
 *
 * <p>The keys are control-plane facts: they are not rendered into the model-visible output. A
 * successful result should not carry them. When a failure cannot be classified, write nothing;
 * consumers treat the absence as {@code unclassified} instead of a placeholder value.
 */
public final class ToolErrorMetadata {

    /** {@link ToolErrorKind#getValue()} of the failure. */
    public static final String METADATA_ERROR_KIND = ToolResultBlock.METADATA_ERROR_KIND;

    /** Short, stable identifier of the object that failed (path, host, thread, skill, ...). */
    public static final String METADATA_ERROR_SCOPE = ToolResultBlock.METADATA_ERROR_SCOPE;

    /** Whether repeating the identical call may succeed; never an instruction to retry. */
    public static final String METADATA_RETRIABLE = ToolResultBlock.METADATA_RETRIABLE;

    private ToolErrorMetadata() {}

    /**
     * Builds metadata with the kind's default retriable suggestion and no scope.
     *
     * @param kind failure kind
     * @return immutable metadata map
     */
    public static Map<String, Object> of(ToolErrorKind kind) {
        return of(kind, null, null);
    }

    /**
     * Builds metadata with a scope and the kind's default retriable suggestion.
     *
     * @param kind failure kind
     * @param scope short identifier of the failed object; {@code null} or blank omits the key
     * @return immutable metadata map
     */
    public static Map<String, Object> of(ToolErrorKind kind, String scope) {
        return of(kind, scope, null);
    }

    /**
     * Builds metadata, explicitly controlling the retriable value.
     *
     * @param kind failure kind
     * @param scope short identifier of the failed object; {@code null} or blank omits the key
     * @param retriable whether the identical call may succeed; {@code null} uses the kind default
     * @return immutable metadata map
     */
    public static Map<String, Object> of(ToolErrorKind kind, String scope, Boolean retriable) {
        Objects.requireNonNull(kind, "kind is required");
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(METADATA_ERROR_KIND, kind.getValue());
        if (scope != null && !scope.isBlank()) {
            metadata.put(METADATA_ERROR_SCOPE, scope.trim());
        }
        metadata.put(METADATA_RETRIABLE, retriable != null ? retriable : kind.isRetriable());
        return Map.copyOf(metadata);
    }
}
