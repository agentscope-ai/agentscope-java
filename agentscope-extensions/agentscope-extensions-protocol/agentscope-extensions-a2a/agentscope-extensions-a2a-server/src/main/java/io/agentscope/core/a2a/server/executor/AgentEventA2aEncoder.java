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
package io.agentscope.core.a2a.server.executor;

import static io.agentscope.core.a2a.agent.utils.MessageConvertUtil.contentBlockToProtobufSafeMap;
import static io.agentscope.core.a2a.agent.utils.MessageConvertUtil.protobufSafeMap;
import static io.agentscope.core.a2a.agent.utils.MessageConvertUtil.protobufSafeValue;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.agent.hitl.A2aPendingTool;
import io.agentscope.core.a2a.agent.message.MessageConstants;
import io.agentscope.core.a2a.server.hitl.HitlEncodingContext;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.utils.MessageConvertUtil;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.DataBlockDeltaEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ExternalExecutionResultEvent;
import io.agentscope.core.event.HintBlockEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireExternalExecutionEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stateful one-way encoder from AgentScope GA events to standard A2A 1.0 emissions. */
final class AgentEventA2aEncoder {

    private static final Logger log = LoggerFactory.getLogger(AgentEventA2aEncoder.class);

    private static final Set<String> INPUT_REQUIRED_VALUES =
            Set.of("inputrequired", "requiresinput");
    private static final Set<AgentEventType> WIRE_EVENT_TYPES =
            Set.of(
                    AgentEventType.AGENT_END,
                    AgentEventType.AGENT_RESULT,
                    AgentEventType.TEXT_BLOCK_DELTA,
                    AgentEventType.THINKING_BLOCK_DELTA,
                    AgentEventType.DATA_BLOCK_DELTA,
                    AgentEventType.TOOL_CALL_START,
                    AgentEventType.TOOL_CALL_DELTA,
                    AgentEventType.TOOL_CALL_END,
                    AgentEventType.TOOL_RESULT_START,
                    AgentEventType.TOOL_RESULT_TEXT_DELTA,
                    AgentEventType.TOOL_RESULT_DATA_DELTA,
                    AgentEventType.TOOL_RESULT_END,
                    AgentEventType.EXCEED_MAX_ITERS,
                    AgentEventType.REQUIRE_USER_CONFIRM,
                    AgentEventType.REQUIRE_EXTERNAL_EXECUTION,
                    AgentEventType.USER_CONFIRM_RESULT,
                    AgentEventType.EXTERNAL_EXECUTION_RESULT,
                    AgentEventType.REQUEST_STOP,
                    AgentEventType.HINT_BLOCK,
                    AgentEventType.ALL_TOOLS_DENIED);
    private static final Set<AgentEventType> INTENTIONALLY_IGNORED_EVENT_TYPES =
            Set.of(
                    AgentEventType.AGENT_START,
                    AgentEventType.MODEL_CALL_START,
                    AgentEventType.MODEL_CALL_END,
                    AgentEventType.TEXT_BLOCK_START,
                    AgentEventType.TEXT_BLOCK_END,
                    AgentEventType.THINKING_BLOCK_START,
                    AgentEventType.THINKING_BLOCK_END,
                    AgentEventType.DATA_BLOCK_START,
                    AgentEventType.DATA_BLOCK_END,
                    AgentEventType.SUBAGENT_EXPOSED,
                    AgentEventType.CUSTOM);

    private final RequestContext context;
    private final AgentExecuteProperties properties;
    private final AgentEmitter emitter;
    private final boolean streaming;
    private final HitlEncodingContext hitlContext;
    private final String artifactId = UUID.randomUUID().toString();
    private final AtomicBoolean firstArtifact = new AtomicBoolean(true);
    private final AtomicReference<TerminalState> terminal =
            new AtomicReference<>(TerminalState.OPEN);
    private final Map<String, ToolCallAccumulator> toolCalls = new ConcurrentHashMap<>();
    private final Map<String, ToolResultAccumulator> toolResults = new ConcurrentHashMap<>();
    private final List<Part<?>> blockingParts = new ArrayList<>();

    private AgentEventA2aEncoder(
            RequestContext context,
            AgentExecuteProperties properties,
            AgentEmitter emitter,
            boolean streaming,
            HitlEncodingContext hitlContext) {
        this.context = context;
        this.properties = properties;
        this.emitter = emitter;
        this.streaming = streaming;
        this.hitlContext = hitlContext;
    }

    static AgentEventA2aEncoder streaming(
            RequestContext context, AgentExecuteProperties properties, AgentEmitter emitter) {
        return new AgentEventA2aEncoder(context, properties, emitter, true, null);
    }

    static AgentEventA2aEncoder streaming(
            RequestContext context,
            AgentExecuteProperties properties,
            AgentEmitter emitter,
            HitlEncodingContext hitlContext) {
        return new AgentEventA2aEncoder(context, properties, emitter, true, hitlContext);
    }

    static AgentEventA2aEncoder blocking(
            RequestContext context, AgentExecuteProperties properties, AgentEmitter emitter) {
        return new AgentEventA2aEncoder(context, properties, emitter, false, null);
    }

    static AgentEventA2aEncoder blocking(
            RequestContext context,
            AgentExecuteProperties properties,
            AgentEmitter emitter,
            HitlEncodingContext hitlContext) {
        return new AgentEventA2aEncoder(context, properties, emitter, false, hitlContext);
    }

    void onNext(AgentEvent event) {
        if (event == null || terminal.get() != TerminalState.OPEN) {
            return;
        }
        if (emitMetadataHandoff(event)) {
            return;
        }
        if (event instanceof TextBlockDeltaEvent text) {
            emitTextDelta(text, MessageConstants.BlockContent.TYPE_TEXT, text.getDelta());
        } else if (event instanceof ThinkingBlockDeltaEvent thinking) {
            emitTextDelta(
                    thinking, MessageConstants.BlockContent.TYPE_THINKING, thinking.getDelta());
        } else if (event instanceof DataBlockDeltaEvent data) {
            emitDataDelta(data);
        } else if (event instanceof ToolCallStartEvent start) {
            toolCalls.put(
                    start.getToolCallId(),
                    new ToolCallAccumulator(
                            start.getReplyId(),
                            start.getToolCallId(),
                            start.getToolCallName(),
                            start.getSource()));
        } else if (event instanceof ToolCallDeltaEvent delta) {
            toolCalls
                    .computeIfAbsent(
                            delta.getToolCallId(),
                            ignored ->
                                    new ToolCallAccumulator(
                                            delta.getReplyId(),
                                            delta.getToolCallId(),
                                            delta.getToolCallName(),
                                            delta.getSource()))
                    .arguments()
                    .append(nullToEmpty(delta.getDelta()));
        } else if (event instanceof ToolCallEndEvent end) {
            emitToolCall(end);
        } else if (event instanceof ToolResultStartEvent start) {
            toolResults.put(
                    start.getToolCallId(),
                    new ToolResultAccumulator(
                            start.getReplyId(),
                            start.getToolCallId(),
                            start.getToolCallName(),
                            start.getSource()));
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            toolResults
                    .computeIfAbsent(
                            delta.getToolCallId(),
                            ignored ->
                                    new ToolResultAccumulator(
                                            delta.getReplyId(),
                                            delta.getToolCallId(),
                                            delta.getToolCallName(),
                                            delta.getSource()))
                    .text()
                    .append(nullToEmpty(delta.getDelta()));
        } else if (event instanceof ToolResultDataDeltaEvent delta) {
            toolResults
                    .computeIfAbsent(
                            delta.getToolCallId(),
                            ignored ->
                                    new ToolResultAccumulator(
                                            delta.getReplyId(),
                                            delta.getToolCallId(),
                                            delta.getToolCallName(),
                                            delta.getSource()))
                    .data()
                    .add(delta.getData());
        } else if (event instanceof ToolResultEndEvent end) {
            emitToolResult(end);
        } else if (event instanceof HintBlockEvent hint) {
            emitHint(hint);
        } else if (event instanceof RequireUserConfirmEvent confirm) {
            emitInputRequired(confirm.getReplyId(), confirm.getToolCalls(), "user-confirm");
        } else if (event instanceof RequireExternalExecutionEvent external) {
            emitInputRequired(external.getReplyId(), external.getToolCalls(), "external-execution");
        } else if (event instanceof UserConfirmResultEvent confirmResult) {
            emitControlData(
                    confirmResult.getReplyId(),
                    "user-confirm-result",
                    protobufSafeValue(confirmResult.getConfirmResults()),
                    confirmResult.getSource());
        } else if (event instanceof ExternalExecutionResultEvent executionResult) {
            emitExternalResults(executionResult);
        } else if (event instanceof AgentResultEvent result) {
            if (result.getResult() != null
                    && result.getResult().getGenerateReason() == GenerateReason.TOOL_SUSPENDED) {
                emitInputRequired(
                        result.getResult().getId(),
                        result.getResult().getContentBlocks(ToolUseBlock.class),
                        "external-execution");
            } else {
                emitResult(result.getResult());
            }
        } else if (event instanceof RequestStopEvent stop) {
            emitCanceled(stop.getReason());
        } else if (event instanceof ExceedMaxItersEvent exceeded) {
            emitFailure(
                    "Agent exceeded max iterations: "
                            + exceeded.getCurrentIter()
                            + "/"
                            + exceeded.getMaxIters());
        } else if (event instanceof AllToolsDeniedEvent) {
            emitFailure("All requested tools were denied");
        } else if (event instanceof AgentEndEvent end) {
            if (end.getSource() != null) {
                log.trace(
                        "[{}] Sourced child AgentEndEvent is non-terminal: source={} replyId={}",
                        context.getTaskId(),
                        end.getSource(),
                        end.getReplyId());
            } else {
                emitFailure(
                        "Agent stream ended without AgentResultEvent for reply "
                                + end.getReplyId());
            }
        } else if (event.getType() != null
                && INTENTIONALLY_IGNORED_EVENT_TYPES.contains(event.getType())) {
            log.trace(
                    "[{}] Intentionally skip AgentEvent type={} class={}",
                    context.getTaskId(),
                    event.getType(),
                    event.getClass().getName());
        } else {
            log.warn(
                    "[{}] Unhandled AgentEvent type={} class={}; no A2A event emitted",
                    context.getTaskId(),
                    event.getType(),
                    event.getClass().getName());
        }
    }

    static boolean isClassifiedEventType(AgentEventType type) {
        return type != null
                && (WIRE_EVENT_TYPES.contains(type)
                        ^ INTENTIONALLY_IGNORED_EVENT_TYPES.contains(type));
    }

    void onComplete() {
        if (terminal.get() == TerminalState.OPEN) {
            emitFailure("Agent stream completed without AgentResultEvent or handoff");
        }
    }

    void onError(Throwable error) {
        String message = error == null ? "unknown error" : nullToEmpty(error.getMessage());
        emitFailure("Agent stream failed: " + message, HitlHandoffStatus.RECOVERY_REQUIRED);
    }

    private void emitTextDelta(AgentEvent event, String blockType, String delta) {
        if (!streaming || delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = eventMetadata(event, replyId(event), blockId(event));
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, blockType);
        metadata.put(MessageConstants.STREAM_CHUNK_METADATA_KEY, true);
        emitArtifact(List.of(new TextPart(delta, protobufSafeMap(metadata))), false);
    }

    private void emitDataDelta(DataBlockDeltaEvent event) {
        if (!streaming) {
            return;
        }
        Map<String, Object> metadata = eventMetadata(event, event.getReplyId(), event.getBlockId());
        metadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY, MessageConstants.BlockContent.TYPE_DATA);
        emitArtifact(
                List.of(
                        new DataPart(
                                protobufSafeValue(event.getDelta()), protobufSafeMap(metadata))),
                false);
    }

    private void emitToolCall(ToolCallEndEvent end) {
        ToolCallAccumulator accumulator = toolCalls.remove(end.getToolCallId());
        if (!properties.isRequireInnerMessage()) {
            return;
        }
        if (accumulator == null) {
            accumulator =
                    new ToolCallAccumulator(
                            end.getReplyId(),
                            end.getToolCallId(),
                            end.getToolCallName(),
                            end.getSource());
        }
        Map<String, Object> metadata =
                toolMetadata(
                        accumulator.replyId(),
                        accumulator.toolCallId(),
                        accumulator.toolCallName(),
                        accumulator.source(),
                        MessageConstants.BlockContent.TYPE_TOOL_USE);
        String arguments = accumulator.arguments().toString();
        DataPart part;
        if (arguments.isEmpty()) {
            part = new DataPart(Map.of(), protobufSafeMap(metadata));
        } else {
            try {
                Object parsedArguments = DataPart.fromJson(arguments).data();
                Object safeArguments = protobufSafeValue(parsedArguments);
                part =
                        new DataPart(
                                safeArguments == null ? Map.of() : safeArguments,
                                protobufSafeMap(metadata));
            } catch (IllegalArgumentException invalidJson) {
                metadata.put(MessageConstants.TOOL_ARGUMENTS_RAW_METADATA_KEY, true);
                part = new DataPart(arguments, protobufSafeMap(metadata));
            }
        }
        emitArtifact(List.of(part), false);
    }

    private void emitToolResult(ToolResultEndEvent end) {
        ToolResultAccumulator accumulator = toolResults.remove(end.getToolCallId());
        if (!properties.isRequireInnerMessage()) {
            return;
        }
        if (accumulator == null) {
            accumulator =
                    new ToolResultAccumulator(
                            end.getReplyId(),
                            end.getToolCallId(),
                            end.getToolCallName(),
                            end.getSource());
        }
        Map<String, Object> metadata =
                toolMetadata(
                        accumulator.replyId(),
                        accumulator.toolCallId(),
                        accumulator.toolCallName(),
                        accumulator.source(),
                        MessageConstants.BlockContent.TYPE_TOOL_RESULT);
        if (end.getState() != null) {
            metadata.put(
                    MessageConstants.TOOL_RESULT_STATE_METADATA_KEY, end.getState().getValue());
        }
        List<ContentBlock> output = new ArrayList<>();
        if (!accumulator.text().isEmpty()) {
            output.add(TextBlock.builder().text(accumulator.text().toString()).build());
        }
        output.addAll(accumulator.data());
        List<Map<String, Object>> wireOutput =
                output.stream()
                        .map(block -> contentBlockToProtobufSafeMap(block))
                        .filter(value -> !value.isEmpty())
                        .toList();
        Map<String, Object> data =
                Map.of(MessageConstants.TOOL_RESULT_OUTPUT_METADATA_KEY, wireOutput);
        emitArtifact(
                List.of(new DataPart(protobufSafeValue(data), protobufSafeMap(metadata))), false);
    }

    private void emitHint(HintBlockEvent hint) {
        if (!properties.isRequireInnerMessage()) {
            return;
        }
        Map<String, Object> metadata = eventMetadata(hint, hint.getReplyId(), hint.getBlockId());
        metadata.put(
                MessageConstants.BLOCK_TYPE_METADATA_KEY, MessageConstants.BlockContent.TYPE_HINT);
        if (hint.getHintSource() != null) {
            metadata.put(MessageConstants.SOURCE_NAME_METADATA_KEY, hint.getHintSource());
        }
        emitArtifact(
                List.of(new TextPart(nullToEmpty(hint.getHint()), protobufSafeMap(metadata))),
                false);
    }

    private void emitInputRequired(
            String replyId, List<ToolUseBlock> toolCalls, String handoffType) {
        if (hitlContext != null
                && hitlContext.claimedHandoffId() != null
                && !hitlContext.canOpenDurableHandoff()) {
            throw new IllegalStateException(
                    "A resumed HITL turn paused again without a next resume token");
        }
        Map<String, Object> handoffMetadata = new LinkedHashMap<>();
        handoffMetadata.put(MessageConstants.A2A_TASK_STATE_METADATA_KEY, "input-required");
        handoffMetadata.put(MessageConstants.HANDOFF_TYPE_METADATA_KEY, handoffType);
        if (hitlContext != null && hitlContext.canOpenDurableHandoff()) {
            A2aHandoffType type =
                    "user-confirm".equals(handoffType)
                            ? A2aHandoffType.USER_CONFIRM
                            : A2aHandoffType.EXTERNAL_EXECUTION;
            HitlHandoffRecord record =
                    hitlContext
                            .coordinator()
                            .open(
                                    new HitlOpenRequest(
                                            context.getTaskId(),
                                            context.getContextId(),
                                            hitlContext.executionKey(),
                                            type,
                                            toolCalls,
                                            hitlContext.nextResumeToken(),
                                            hitlContext.handoffTtl(),
                                            hitlContext.claimedHandoffId()));
            handoffMetadata.put(MessageConstants.HANDOFF_ID_METADATA_KEY, record.handoffId());
            handoffMetadata.put(
                    MessageConstants.HANDOFF_EXPIRES_AT_METADATA_KEY,
                    record.expiresAt().toString());
            handoffMetadata.put(
                    MessageConstants.PENDING_TOOLS_FINGERPRINT_METADATA_KEY,
                    record.pendingFingerprint());
            handoffMetadata.put(
                    MessageConstants.PENDING_TOOLS_METADATA_KEY,
                    record.pendingTools().stream()
                            .map(AgentEventA2aEncoder::pendingToolMetadata)
                            .toList());
        }
        if (!tryTerminal(TerminalState.HANDOFF)) {
            return;
        }
        Msg message =
                Msg.builder()
                        .id(replyId)
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(toolCalls == null ? List.of() : new ArrayList<>(toolCalls))
                        .metadata(handoffMetadata)
                        .build();
        Message wire =
                Message.builder(toMessage(message))
                        .metadata(protobufSafeMap(handoffMetadata))
                        .build();
        emitter.requiresInput(wire, true);
    }

    private static Map<String, Object> pendingToolMetadata(ToolUseBlock tool) {
        A2aPendingTool pending =
                new A2aPendingTool(
                        tool.getId(),
                        tool.getName(),
                        tool.getInput(),
                        tool.getContent() == null || tool.getContent().isBlank()
                                ? "Tool " + tool.getName() + " requires input"
                                : tool.getContent());
        @SuppressWarnings("unchecked")
        Map<String, Object> value =
                io.agentscope.core.util.JsonUtils.getJsonCodec().convertValue(pending, Map.class);
        return protobufSafeMap(value);
    }

    private void emitControlData(String replyId, String kind, Object data, String source) {
        if (!properties.isRequireInnerMessage()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MessageConstants.MSG_ID_METADATA_KEY, replyId);
        metadata.put(MessageConstants.HANDOFF_TYPE_METADATA_KEY, kind);
        if (source != null) {
            metadata.put(MessageConstants.EVENT_SOURCE_METADATA_KEY, source);
        }
        emitArtifact(List.of(new DataPart(data, protobufSafeMap(metadata))), false);
    }

    private void emitExternalResults(ExternalExecutionResultEvent event) {
        if (!properties.isRequireInnerMessage()) {
            return;
        }
        Msg message =
                Msg.builder()
                        .id(event.getReplyId())
                        .name("agent")
                        .role(MsgRole.ASSISTANT)
                        .content(new ArrayList<>(event.getToolResults()))
                        .build();
        emitArtifact(MessageConvertUtil.convertFromContentBlocks(message), false);
    }

    private void emitResult(Msg result) {
        if (result == null) {
            emitFailure("AgentResultEvent did not contain a result message");
            return;
        }
        if (!tryTerminal(TerminalState.COMPLETED)) {
            return;
        }
        Message message = toMessage(result);
        if (!streaming) {
            Message finalMessage = message;
            if (blockingParts.isEmpty()) {
                finalMessage = message;
            } else {
                List<Part<?>> combined = new ArrayList<>(blockingParts);
                combined.addAll(message.parts());
                finalMessage = Message.builder(message).parts(combined).build();
            }
            if (hasClaimedHandoff()) {
                emitter.complete(finalMessage);
                transitionClaimed(HitlHandoffStatus.COMPLETED);
            } else {
                emitter.sendMessage(finalMessage);
            }
            return;
        }
        List<Part<?>> parts = MessageConvertUtil.convertFromContentBlocks(result);
        if (!parts.isEmpty()) {
            Map<String, Object> metadata = protobufSafeMap(result.getMetadata());
            emitter.addArtifact(
                    parts,
                    artifactId,
                    "agent-response",
                    metadata.isEmpty() ? Map.of() : metadata,
                    false,
                    true);
            firstArtifact.set(false);
        }
        if (properties.isCompleteWithMessage()) {
            emitter.complete(message);
        } else {
            emitter.complete();
        }
        transitionClaimed(HitlHandoffStatus.COMPLETED);
    }

    private boolean emitMetadataHandoff(AgentEvent event) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (event.getMetadata() != null) {
            metadata.putAll(event.getMetadata());
        }
        Msg result = event instanceof AgentResultEvent resultEvent ? resultEvent.getResult() : null;
        if (result != null && result.getMetadata() != null) {
            metadata.putAll(result.getMetadata());
        }
        if (isInputRequired(metadata)) {
            if (tryTerminal(TerminalState.HANDOFF)) {
                emitter.requiresInput(messageFor(result, "Agent requires input"), true);
            }
            return true;
        }
        return false;
    }

    private void emitCanceled(String reason) {
        if (!tryTerminal(TerminalState.CANCELED)) {
            return;
        }
        emitter.cancel(textMessage("Agent execution canceled: " + nullToEmpty(reason)));
        transitionClaimed(HitlHandoffStatus.CANCELED);
    }

    private void emitFailure(String reason) {
        emitFailure(reason, HitlHandoffStatus.FAILED);
    }

    private void emitFailure(String reason, HitlHandoffStatus claimedStatus) {
        if (!tryTerminal(TerminalState.FAILED)) {
            return;
        }
        Message message = textMessage(reason);
        if (streaming || hasClaimedHandoff()) {
            emitter.fail(message);
        } else {
            emitter.sendMessage(message);
        }
        transitionClaimed(claimedStatus);
    }

    private boolean hasClaimedHandoff() {
        return hitlContext != null && hitlContext.claimedHandoffId() != null;
    }

    private void transitionClaimed(HitlHandoffStatus target) {
        if (hitlContext == null || hitlContext.claimedHandoffId() == null) {
            return;
        }
        hitlContext
                .coordinator()
                .transition(hitlContext.claimedHandoffId(), HitlHandoffStatus.CLAIMED, target);
    }

    private void emitArtifact(List<Part<?>> parts, boolean lastChunk) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        if (!streaming) {
            blockingParts.addAll(parts);
            return;
        }
        boolean append = !firstArtifact.getAndSet(false);
        emitter.addArtifact(parts, artifactId, "agent-response", Map.of(), append, lastChunk);
    }

    private Message toMessage(Msg msg) {
        return MessageConvertUtil.convertFromMsgToMessage(
                msg, context.getTaskId(), context.getContextId());
    }

    private Message messageFor(Msg msg, String fallback) {
        return msg == null ? textMessage(fallback) : toMessage(msg);
    }

    private Message textMessage(String text) {
        return A2A.createAgentTextMessage(text, context.getContextId(), context.getTaskId());
    }

    private Map<String, Object> eventMetadata(AgentEvent event, String replyId, String blockId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (event.getMetadata() != null) {
            metadata.putAll(event.getMetadata());
        }
        if (replyId != null) {
            metadata.put(MessageConstants.MSG_ID_METADATA_KEY, replyId);
        }
        if (blockId != null) {
            metadata.put(MessageConstants.BLOCK_ID_METADATA_KEY, blockId);
        }
        if (event.getSource() != null) {
            metadata.put(MessageConstants.EVENT_SOURCE_METADATA_KEY, event.getSource());
        }
        return metadata;
    }

    private Map<String, Object> toolMetadata(
            String replyId, String toolCallId, String toolName, String source, String blockType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(MessageConstants.BLOCK_TYPE_METADATA_KEY, blockType);
        if (replyId != null) {
            metadata.put(MessageConstants.MSG_ID_METADATA_KEY, replyId);
        }
        if (toolCallId != null) {
            metadata.put(MessageConstants.TOOL_CALL_ID_METADATA_KEY, toolCallId);
        }
        if (toolName != null) {
            metadata.put(MessageConstants.TOOL_NAME_METADATA_KEY, toolName);
        }
        if (source != null) {
            metadata.put(MessageConstants.EVENT_SOURCE_METADATA_KEY, source);
        }
        return metadata;
    }

    private boolean isInputRequired(Map<String, Object> metadata) {
        return containsStateValue(metadata, INPUT_REQUIRED_VALUES)
                || isTrue(metadata.get("requiresInput"))
                || isTrue(metadata.get("requires_input"));
    }

    private boolean containsStateValue(Map<String, Object> metadata, Set<String> values) {
        String canonicalState =
                normalized(metadata.get(MessageConstants.A2A_TASK_STATE_METADATA_KEY));
        return values.contains(canonicalState)
                || values.contains(normalized(metadata.get("a2a_task_state")))
                || values.contains(normalized(metadata.get("taskState")))
                || values.contains(normalized(metadata.get("task_state")));
    }

    private String normalized(Object value) {
        return value == null
                ? ""
                : String.valueOf(value).trim().toLowerCase().replace("-", "").replace("_", "");
    }

    private boolean isTrue(Object value) {
        return value instanceof Boolean bool
                ? bool
                : "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private boolean tryTerminal(TerminalState state) {
        boolean updated = terminal.compareAndSet(TerminalState.OPEN, state);
        if (!updated) {
            log.debug(
                    "[{}] Ignore {} because terminal state is already {}",
                    context.getTaskId(),
                    state,
                    terminal.get());
        }
        return updated;
    }

    private static String replyId(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getReplyId();
        }
        return ((ThinkingBlockDeltaEvent) event).getReplyId();
    }

    private static String blockId(AgentEvent event) {
        if (event instanceof TextBlockDeltaEvent text) {
            return text.getBlockId();
        }
        return ((ThinkingBlockDeltaEvent) event).getBlockId();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum TerminalState {
        OPEN,
        COMPLETED,
        HANDOFF,
        FAILED,
        CANCELED
    }

    private record ToolCallAccumulator(
            String replyId,
            String toolCallId,
            String toolCallName,
            String source,
            StringBuilder arguments) {

        private ToolCallAccumulator(
                String replyId, String toolCallId, String toolCallName, String source) {
            this(replyId, toolCallId, toolCallName, source, new StringBuilder());
        }
    }

    private record ToolResultAccumulator(
            String replyId,
            String toolCallId,
            String toolCallName,
            String source,
            StringBuilder text,
            List<ContentBlock> data) {

        private ToolResultAccumulator(
                String replyId, String toolCallId, String toolCallName, String source) {
            this(replyId, toolCallId, toolCallName, source, new StringBuilder(), new ArrayList<>());
        }
    }
}
