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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
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
    @JsonSubTypes.Type(value = AguiEvent.ReasoningEnd.class, name = "REASONING_END"),
    @JsonSubTypes.Type(value = AguiEvent.RunError.class, name = "RUN_ERROR"),
    @JsonSubTypes.Type(value = AguiEvent.StepStarted.class, name = "STEP_STARTED"),
    @JsonSubTypes.Type(value = AguiEvent.StepFinished.class, name = "STEP_FINISHED"),
    @JsonSubTypes.Type(value = AguiEvent.TextMessageChunk.class, name = "TEXT_MESSAGE_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.ToolCallChunk.class, name = "TOOL_CALL_CHUNK"),
    @JsonSubTypes.Type(value = AguiEvent.MessagesSnapshot.class, name = "MESSAGES_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.ActivitySnapshot.class, name = "ACTIVITY_SNAPSHOT"),
    @JsonSubTypes.Type(value = AguiEvent.ActivityDelta.class, name = "ACTIVITY_DELTA"),
    @JsonSubTypes.Type(
            value = AguiEvent.ReasoningEncryptedValue.class,
            name = "REASONING_ENCRYPTED_VALUE")
})
public sealed interface AguiEvent
        permits AguiEvent.RunStarted,
                AguiEvent.RunFinished,
                AguiEvent.RunError,
                AguiEvent.StepStarted,
                AguiEvent.StepFinished,
                AguiEvent.TextMessageStart,
                AguiEvent.TextMessageContent,
                AguiEvent.TextMessageEnd,
                AguiEvent.TextMessageChunk,
                AguiEvent.ToolCallStart,
                AguiEvent.ToolCallArgs,
                AguiEvent.ToolCallEnd,
                AguiEvent.ToolCallChunk,
                AguiEvent.ToolCallResult,
                AguiEvent.StateSnapshot,
                AguiEvent.StateDelta,
                AguiEvent.MessagesSnapshot,
                AguiEvent.ActivitySnapshot,
                AguiEvent.ActivityDelta,
                AguiEvent.Raw,
                AguiEvent.Custom,
                AguiEvent.ReasoningStart,
                AguiEvent.ReasoningMessageStart,
                AguiEvent.ReasoningMessageContent,
                AguiEvent.ReasoningMessageEnd,
                AguiEvent.ReasoningMessageChunk,
                AguiEvent.ReasoningEnd,
                AguiEvent.ReasoningEncryptedValue {

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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunStarted(String threadId, String runId, String parentRunId, RunAgentInput input)
            implements AguiEvent {

        @JsonCreator
        public RunStarted(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("parentRunId") String parentRunId,
                @JsonProperty("input") RunAgentInput input) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.parentRunId = parentRunId;
            this.input = input;
        }

        public RunStarted(String threadId, String runId) {
            this(threadId, runId, null, null);
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunFinished(String threadId, String runId, Object result, RunFinishedOutcome outcome)
            implements AguiEvent {

        @JsonCreator
        public RunFinished(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("result") Object result,
                @JsonProperty("outcome") RunFinishedOutcome outcome) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.result = result;
            this.outcome = outcome;
        }

        public RunFinished(String threadId, String runId) {
            this(threadId, runId, null, null);
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
     * Event indicating that an agent run has errored. This event is emitted when
     * the agent encounters
     * an error during execution, replacing the legacy Raw+RunFinished pattern.
     */
    record RunError(String threadId, String runId, String message, String code)
            implements AguiEvent {

        @JsonCreator
        public RunError(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("message") String message,
                @JsonProperty("code") String code) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.message = Objects.requireNonNull(message, "message cannot be null");
            this.code = code;
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.RUN_ERROR;
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
     * Event emitted when an agent step begins execution.
     */
    record StepStarted(String threadId, String runId, String stepName) implements AguiEvent {

        @JsonCreator
        public StepStarted(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("stepName") String stepName) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STEP_STARTED;
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
     * Event emitted when an agent step completes execution.
     */
    record StepFinished(String threadId, String runId, String stepName) implements AguiEvent {

        @JsonCreator
        public StepFinished(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("stepName") String stepName) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.STEP_FINISHED;
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
     * A convenience event to auto start/close text messages (compatibility chunk mode).
     */
    record TextMessageChunk(
            String threadId, String runId, String messageId, String role, String delta, String name)
            implements AguiEvent {

        @JsonCreator
        public TextMessageChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("role") String role,
                @JsonProperty("delta") String delta,
                @JsonProperty("name") String name) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = messageId;
            this.role = role;
            this.delta = delta;
            this.name = name;
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TEXT_MESSAGE_CHUNK;
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
     * A convenience event to auto start/close tool calls (compatibility chunk mode).
     */
    record ToolCallChunk(
            String threadId,
            String runId,
            String toolCallId,
            String toolCallName,
            String parentMessageId,
            String delta)
            implements AguiEvent {

        @JsonCreator
        public ToolCallChunk(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("toolCallName") String toolCallName,
                @JsonProperty("parentMessageId") String parentMessageId,
                @JsonProperty("delta") String delta) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.toolCallId = toolCallId;
            this.toolCallName = toolCallName;
            this.parentMessageId = parentMessageId;
            this.delta = delta;
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.TOOL_CALL_CHUNK;
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
     * Event containing a full snapshot of the conversation messages. This event
     * is typically emitted after RUN_FINISHED to synchronize the complete
     * message state.
     */
    record MessagesSnapshot(String threadId, String runId, List<AguiMessage> messages)
            implements AguiEvent {

        @JsonCreator
        public MessagesSnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messages") List<AguiMessage> messages) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messages =
                    messages != null
                            ? Collections.unmodifiableList(new ArrayList<>(messages))
                            : Collections.emptyList();
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.MESSAGES_SNAPSHOT;
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
     * Event containing a snapshot of activity state. This event provides
     * structured activity data that replaces the entire client-side activity
     * state for the given message.
     */
    record ActivitySnapshot(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            Map<String, Object> content,
            Boolean replace)
            implements AguiEvent {

        @JsonCreator
        public ActivitySnapshot(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("activityType") String activityType,
                @JsonProperty("content") Map<String, Object> content,
                @JsonProperty("replace") Boolean replace) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.activityType = Objects.requireNonNull(activityType, "activityType cannot be null");
            this.content =
                    content != null
                            ? Collections.unmodifiableMap(new HashMap<>(content))
                            : Collections.emptyMap();
            this.replace = replace != null ? replace : true;
        }

        public ActivitySnapshot(
                String threadId,
                String runId,
                String messageId,
                String activityType,
                Map<String, Object> content) {
            this(threadId, runId, messageId, activityType, content, true);
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.ACTIVITY_SNAPSHOT;
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
     * Event containing an incremental delta update to activity state. The patch
     * contains a list of JSON Patch operations (RFC 6902) that should be applied
     * to the client-side activity state for the given message.
     */
    record ActivityDelta(
            String threadId,
            String runId,
            String messageId,
            String activityType,
            List<JsonPatchOperation> patch)
            implements AguiEvent {

        @JsonCreator
        public ActivityDelta(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("activityType") String activityType,
                @JsonProperty("patch") List<JsonPatchOperation> patch) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.messageId = Objects.requireNonNull(messageId, "messageId cannot be null");
            this.activityType = Objects.requireNonNull(activityType, "activityType cannot be null");
            this.patch =
                    patch != null ? Collections.unmodifiableList(patch) : Collections.emptyList();
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.ACTIVITY_DELTA;
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
     * Event containing an encrypted reasoning value. Used for encrypted
     * reasoning content (ZDR mode), where sensitive reasoning tokens are
     * encrypted before transmission.
     */
    record ReasoningEncryptedValue(
            String threadId, String runId, String subtype, String entityId, String encryptedValue)
            implements AguiEvent {

        @JsonCreator
        public ReasoningEncryptedValue(
                @JsonProperty("threadId") String threadId,
                @JsonProperty("runId") String runId,
                @JsonProperty("subtype") String subtype,
                @JsonProperty("entityId") String entityId,
                @JsonProperty("encryptedValue") String encryptedValue) {
            this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
            this.runId = Objects.requireNonNull(runId, "runId cannot be null");
            this.subtype = Objects.requireNonNull(subtype, "subtype cannot be null");
            this.entityId = Objects.requireNonNull(entityId, "entityId cannot be null");
            this.encryptedValue =
                    Objects.requireNonNull(encryptedValue, "encryptedValue cannot be null");
        }

        @Override
        public AguiEventType getType() {
            return AguiEventType.REASONING_ENCRYPTED_VALUE;
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

    // ---- value types ----

    /**
     * Outcome of a finished run. Can be a success or an interrupt.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = RunFinishedSuccessOutcome.class, name = "success"),
        @JsonSubTypes.Type(value = RunFinishedInterruptOutcome.class, name = "interrupt")
    })
    sealed interface RunFinishedOutcome
            permits RunFinishedSuccessOutcome, RunFinishedInterruptOutcome {}

    /**
     * Successful run outcome.
     */
    record RunFinishedSuccessOutcome() implements RunFinishedOutcome {

        @JsonCreator
        public RunFinishedSuccessOutcome {}
    }

    /**
     * Run outcome indicating the run was interrupted, with one or more
     * interrupts awaiting user resolution.
     */
    record RunFinishedInterruptOutcome(List<Interrupt> interrupts) implements RunFinishedOutcome {

        @JsonCreator
        public RunFinishedInterruptOutcome(@JsonProperty("interrupts") List<Interrupt> interrupts) {
            this.interrupts =
                    interrupts != null
                            ? Collections.unmodifiableList(interrupts)
                            : Collections.emptyList();
        }
    }

    /**
     * Represents an interrupt that occurred during agent execution, requiring
     * user input to resolve before the agent can continue.
     */
    record Interrupt(
            String id,
            String reason,
            String message,
            String toolCallId,
            Map<String, Object> responseSchema,
            String expiresAt,
            Map<String, Object> metadata) {

        @JsonCreator
        public Interrupt(
                @JsonProperty("id") String id,
                @JsonProperty("reason") String reason,
                @JsonProperty("message") String message,
                @JsonProperty("toolCallId") String toolCallId,
                @JsonProperty("responseSchema") Map<String, Object> responseSchema,
                @JsonProperty("expiresAt") String expiresAt,
                @JsonProperty("metadata") Map<String, Object> metadata) {
            this.id = Objects.requireNonNull(id, "id cannot be null");
            this.reason = Objects.requireNonNull(reason, "reason cannot be null");
            this.message = message;
            this.toolCallId = toolCallId;
            this.responseSchema = responseSchema;
            this.expiresAt = expiresAt;
            this.metadata = metadata;
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
