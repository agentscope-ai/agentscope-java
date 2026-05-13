/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Sealed interface for all AG-UI protocol events.
 *
 * <p>
 * All events in the AG-UI protocol implement this interface and provide common
 * properties like
 * event type, thread ID, and run ID. Using sealed interface with records
 * provides a cleaner, more
 * concise implementation.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AguiEvent.RunStarted.class, name = "RUN_STARTED"),
    @JsonSubTypes.Type(value = AguiEvent.RunFinished.class, name = "RUN_FINISHED"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageStart.class, name = "TEXT_MESSAGE_START"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageContent.class, name = "TEXT_MESSAGE_CONTENT"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageEnd.class, name = "TEXT_MESSAGE_END"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallStart.class, name = "TOOL_CALL_START"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallArgs.class, name = "TOOL_CALL_ARGS"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallEnd.class, name = "TOOL_CALL_END"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallResult.class, name = "TOOL_CALL_RESULT"),
    @JsonSubTypes.Type(value = AguiEvent.StateSnapshot.class, name = "STATE_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.StateDelta.class, name = "STATE_DELTA"),
    @JsonSubTypes.Type(value = AguiEvent.Raw.class, name = "RAW"),
    @JsonSubTypes.Type(value = AguiEvent.Custom.class, name = "CUSTOM"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningStart.class, name = "REASONING_START"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageStart.class,
            name = "REASONING_MESSAGE_START"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageContent.class,
            name = "REASONING_MESSAGE_CONTENT"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningMessageEnd.class, name = "REASONING_MESSAGE_END"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningMessageChunk.class,
            name = "REASONING_MESSAGE_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.ReasoningEnd.class, name = "REASONING_END")
})
public sealed interface AguiEvent
        permits AguiEvent.RunStarted,
                AguiEvent.RunFinished,
                AguiEvent.TextMessageStart,
                AguiEvent.TextMessageContent,
                AguiEvent.TextMessageEnd,
                AguiEvent.ToolCallStart,
                AguiEvent.ToolCallArgs,
                AguiEvent.ToolCallEnd,
                AguiEvent.ToolCallResult,
                AguiEvent.StateSnapshot,
                AguiEvent.StateDelta,
                AguiEvent.Raw,
                AguiEvent.Custom,
                AguiEvent.ReasoningStart,
                AguiEvent.ReasoningMessageStart,
                AguiEvent.ReasoningMessageContent,
                AguiEvent.ReasoningMessageEnd,
                AguiEvent.ReasoningMessageChunk,
                AguiEvent.ReasoningEnd {

    /**
     * Get the event type.
     *
     * @return The event type
     */
    @JsonIgnore
    AguiEventType getType();

    /**
     * Get the thread ID associated with this event.
     *
     * @return The thread ID
     */
    String getThreadId();

    /**
     * Get the run ID associated with this event.
     *
     * @return The run ID
     */
    String getRunId();

    /**
     * Event indicating that an agent run has started. This is the first event
     * emitted when an agent
     * begins processing a request.
     */
    record RunStarted(String threadId, String runId) implements AguiEvent {

        @JsonCreator
        public RunStarted(
                @JsonProperty("threadId") String threadId, @JsonProperty("runId") String runId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_STARTED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating that an agent run has finished. This is the last event
     * emitted when an agent
     * completes processing a request.
     */
    record RunFinished(String threadId, String runId) implements AguiEvent {

        @JsonCreator
        public RunFinished(
                @JsonProperty("threadId") String threadId, @JsonProperty("runId") String runId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_FINISHED;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the start of a text message. This event is emitted when the
     * agent begins
     * generating a text response.
     */
    record TextMessageStart(String threadId, String runId, String messageId, String role)
            implements AguiEvent {

        @JsonCreator
        public TextMessageStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.role = Objects.requireNonNull(role, "role cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event containing incremental text content for a message. This event is
     * emitted during
     * streaming to deliver text content in chunks.
     */
    record TextMessageContent(String threadId, String runId, String messageId, String delta)
            implements AguiEvent {

        @JsonCreator
        public TextMessageContent(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_CONTENT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the end of a text message. This event is emitted when the
     * agent has finished
     * generating a text message.
     */
    record TextMessageEnd(String threadId, String runId, String messageId) implements AguiEvent {

        @JsonCreator
        public TextMessageEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the start of a tool call. This event is emitted when the
     * agent begins a tool
     * invocation.
     */
    record ToolCallStart(String threadId, String runId, String toolCallId, String toolCallName)
            implements AguiEvent {

        @JsonCreator
        public ToolCallStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("toolCallName") String toolCallName) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.toolCallName = Objects.requireNonNull(toolCallName, "toolCallName cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event containing streaming arguments for a tool call. The delta contains a
     * JSON fragment that
     * forms part of the complete tool arguments.
     */
    record ToolCallArgs(String threadId, String runId, String toolCallId, String delta)
            implements AguiEvent {

        @JsonCreator
        public ToolCallArgs(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("delta") String delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_ARGS;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the end of a tool call. This event is emitted when a tool
     * invocation completes.
     */
    record ToolCallEnd(String threadId, String runId, String toolCallId) implements AguiEvent {

        @JsonCreator
        public ToolCallEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event containing the result of a tool call.
     */
    record ToolCallResult(
            String threadId,
            String runId,
            String toolCallId,
            String content,
            String role,
            String messageId)
            implements AguiEvent {

        @JsonCreator
        public ToolCallResult(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("content") String content,
                @JsonProperty("role") String role,
                @JsonProperty("messageId") String messageId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = Objects.requireNonNull(toolCallId, "toolCallId cannot be null");
            this.content = content;
            this.role = role;
            this.messageId = messageId;
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_RESULT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }

        public String getRole() {
            return role;
        }

        public String getMessageId() {
            return messageId;
        }
    }

    /**
     * Event containing a full state snapshot. This event replaces the entire
     * client-side state with
     * the provided snapshot.
     */
    record StateSnapshot(String threadId, String runId, Map<String, Object> snapshot)
            implements AguiEvent {

        @JsonCreator
        public StateSnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("snapshot") Map<String, Object> snapshot) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.snapshot =
                    snapshot != null
                            ? Collections.unmodifiableMap(new HashMap<>(snapshot))
                            : Collections.emptyMap();
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STATE_SNAPSHOT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event containing an incremental state delta. This event contains a list of
     * JSON Patch
     * operations (RFC 6902) that should be applied to the current client-side
     * state.
     */
    record StateDelta(String threadId, String runId, List<JsonPatchOperation> delta)
            implements AguiEvent {

        @JsonCreator
        public StateDelta(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("delta") List<JsonPatchOperation> delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.delta =
                    delta != null ? Collections.unmodifiableList(delta) : Collections.emptyList();
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
    }

    /**
     * Event containing raw/custom data. This event type allows passing through
     * custom data that
     * doesn't fit into the standard AG-UI event types.
     */
    record Raw(String threadId, String runId, Object rawEvent) implements AguiEvent {

        @JsonCreator
        public Raw(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("rawEvent") Object rawEvent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.rawEvent = rawEvent; // nullable
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RAW;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * The Custom event provides an extension mechanism for implementing
     * features not covered by the standard event types.
     */
    record Custom(String threadId, String runId, String name, Object value) implements AguiEvent {

        @JsonCreator
        public Custom(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("name") String name,
                @JsonProperty("value") Object value) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.name = Objects.requireNonNull(name, "name cannot be null");
            this.value = value; // nullable
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.CUSTOM;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the start of a reasoning/thinking phase. This event is emitted
     * when the agent begins its internal reasoning process.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningStart(String threadId, String runId, String messageId, String encryptedContent)
            implements AguiEvent {

        @JsonCreator
        public ReasoningStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("encryptedContent") String encryptedContent) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.encryptedContent = encryptedContent; // Optional
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event signaling the start of a reasoning message.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningMessageStart(String threadId, String runId, String messageId, String role)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageStart(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.role = Objects.requireNonNull(role, "role cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_START;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event containing a chunk of content in a streaming reasoning message.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningMessageContent(String threadId, String runId, String messageId, String delta)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageContent(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.delta = Objects.requireNonNull(delta, "delta cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_CONTENT;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event signaling the end of a reasoning message.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningMessageEnd(String threadId, String runId, String messageId)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * A convenience event to auto start/close reasoning messages.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningMessageChunk(String threadId, String runId, String messageId, String delta)
            implements AguiEvent {

        @JsonCreator
        public ReasoningMessageChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("delta") String delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = messageId; // Optional
            this.delta = delta; // Optional
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_MESSAGE_CHUNK;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Event indicating the end of a reasoning/thinking phase. This event is emitted
     * when the agent has finished its internal reasoning process.
     *
     * <p>According to AG-UI Reasoning draft specification.
     */
    record ReasoningEnd(String threadId, String runId, String messageId) implements AguiEvent {

        @JsonCreator
        public ReasoningEnd(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_END;
        }

        @Override
        public String getThreadId() {
            return threadId;
        }

        @Override
        public String getRunId() {
            return runId;
        }
    }

    /**
     * Represents a JSON Patch operation (RFC 6902). Used in {@link StateDelta}
     * events for
     * incremental state updates.
     */
    record JsonPatchOperation(String op, String path, Object value, String from) {

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
         * @param path  The path to add at
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
         * @param path  The path to replace
         * @param value The new value
         * @return A new replace operation
         */
        public static JsonPatchOperation replace(String path, Object value) {
            return new JsonPatchOperation("replace", path, value, null);
        }
    }
}
