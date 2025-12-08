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
package io.agentscope.core.agui.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Event containing an incremental state delta.
 *
 * <p>This event contains a list of JSON Patch operations (RFC 6902) that should be
 * applied to the current client-side state. This allows efficient partial updates
 * without transmitting the entire state.
 */
public final class StateDeltaEvent implements AguiEvent {

    private final String threadId;
    private final String runId;
    private final List<JsonPatchOperation> delta;

    /**
     * Creates a new StateDeltaEvent.
     *
     * @param threadId The thread ID
     * @param runId The run ID
     * @param delta The list of JSON Patch operations
     */
    @JsonCreator
    public StateDeltaEvent(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("delta") List<JsonPatchOperation> delta) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.delta = delta != null ? Collections.unmodifiableList(delta) : Collections.emptyList();
    }

    @Override
    public AguiEventType getType() {
        return AguiEventType.STATE_DELTA;
    }

    @Override
    public String getThreadId() {
        return threadId;
    }

    @Override
    public String getRunId() {
        return runId;
    }

    /**
     * Get the list of JSON Patch operations.
     *
     * @return The delta operations as an immutable list
     */
    public List<JsonPatchOperation> getDelta() {
        return delta;
    }

    @Override
    public String toString() {
        return "StateDeltaEvent{threadId='"
                + threadId
                + "', runId='"
                + runId
                + "', delta="
                + delta
                + "}";
    }

    /**
     * Represents a JSON Patch operation (RFC 6902).
     */
    public static class JsonPatchOperation {

        private final String op;
        private final String path;
        private final Object value;
        private final String from;

        /**
         * Creates a new JSON Patch operation.
         *
         * @param op The operation type (add, remove, replace, move, copy, test)
         * @param path The JSON Pointer path
         * @param value The value (for add, replace, test operations)
         * @param from The source path (for move, copy operations)
         */
        @JsonCreator
        public JsonPatchOperation(
                @JsonProperty("op") String op,
                @JsonProperty("path") String path,
                @JsonProperty("value") Object value,
                @JsonProperty("from") String from) {
            this.op = Objects.requireNonNull(op, "op cannot be null");
            this.path = Objects.requireNonNull(path, "path cannot be null");
            this.value = value;
            this.from = from;
        }

        /**
         * Creates an "add" operation.
         *
         * @param path The path to add at
         * @param value The value to add
         * @return A new add operation
         */
        public static JsonPatchOperation add(String path, Object value) {
            return new JsonPatchOperation("add", path, value, null);
        }

        /**
         * Creates a "remove" operation.
         *
         * @param path The path to remove
         * @return A new remove operation
         */
        public static JsonPatchOperation remove(String path) {
            return new JsonPatchOperation("remove", path, null, null);
        }

        /**
         * Creates a "replace" operation.
         *
         * @param path The path to replace
         * @param value The new value
         * @return A new replace operation
         */
        public static JsonPatchOperation replace(String path, Object value) {
            return new JsonPatchOperation("replace", path, value, null);
        }

        public String getOp() {
            return op;
        }

        public String getPath() {
            return path;
        }

        public Object getValue() {
            return value;
        }

        public String getFrom() {
            return from;
        }

        @Override
        public String toString() {
            return "JsonPatchOperation{op='"
                    + op
                    + "', path='"
                    + path
                    + "', value="
                    + value
                    + ", from='"
                    + from
                    + "'}";
        }
    }
}
