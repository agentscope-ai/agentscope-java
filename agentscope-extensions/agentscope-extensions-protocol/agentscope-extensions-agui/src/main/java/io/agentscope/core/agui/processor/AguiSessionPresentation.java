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
package io.agentscope.core.agui.processor;

import io.agentscope.core.agui.AguiInterrupts;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiTool;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays committed {@link SessionEvent}s after a recovery cursor as AG-UI deltas.
 *
 * <p>This projector deliberately keeps no snapshot of committed history. The application supplies
 * the conversation history and a cursor that is a presentation boundary: everything before the
 * cursor is already represented by the application's history, and everything after it is replayed
 * here. Tool calls are part of AG-UI messages, so a tool result for a call from history is emitted
 * as {@code TOOL_CALL_RESULT} without a duplicate {@code TOOL_CALL_START}.
 */
final class AguiSessionPresentation {

    private static final String TOOL_RESULT_PREFIX = "agui:tool-result:";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_REASONING = "reasoning";
    private static final String ROLE_TOOL = "tool";
    private static final String UNKNOWN_TOOL = "unknown";

    private final String threadId;
    private final String runId;
    private final RunAgentInput input;
    private final boolean enableReasoning;
    private final boolean emitToolCallArgs;
    private final Set<String> externalToolNames;

    private final Set<String> startedTextMessages = new LinkedHashSet<>();
    private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
    private final Map<String, ToolCallState> toolCalls = new LinkedHashMap<>();
    private final Map<String, String> lastToolCallByModelCall = new LinkedHashMap<>();
    private final Set<String> emittedResults = new LinkedHashSet<>();
    private final Map<String, Interaction> pending = new LinkedHashMap<>();
    private final Set<String> activeRuns = new LinkedHashSet<>();

    AguiSessionPresentation(
            String threadId, String runId, AguiAdapterConfig config, RunAgentInput input) {
        this.threadId = threadId;
        this.runId = runId;
        this.input = input;
        this.enableReasoning = config.isEnableReasoning();
        this.emitToolCallArgs = config.isEmitToolCallArgs();
        Set<String> external = new LinkedHashSet<>();
        if (input != null && input.getTools() != null) {
            for (AguiTool tool : input.getTools()) {
                if (tool != null && tool.getName() != null && !tool.getName().isBlank()) {
                    external.add(tool.getName());
                }
            }
        }
        this.externalToolNames = external;
    }

    Set<String> activeRuns() {
        return Set.copyOf(activeRuns);
    }

    /**
     * Applies one committed event and returns the AG-UI deltas it represents.
     *
     * @param event committed event after the recovery cursor
     * @return AG-UI deltas, possibly empty
     */
    List<AguiEvent> apply(SessionEvent event) {
        List<AguiEvent> out = new ArrayList<>();
        switch (event.type()) {
            case "run/start" -> {
                if (event.executionRunId() != null) {
                    activeRuns.add(event.executionRunId());
                }
                // The run lifecycle frame is produced from the committed event, so a reconnect
                // that already passed it does not emit RUN_STARTED twice.
                out.add(new AguiEvent.RunStarted(threadId, runId, null, input));
            }
            case "run/end" -> {
                if (event.executionRunId() != null) {
                    activeRuns.remove(event.executionRunId());
                }
            }
            case "model/chunk" -> modelChunk(event, out);
            case "model/end" -> modelEnd(event, out);
            case "tool/requested" -> toolRequested(event, out);
            case "action/end" -> actionEnd(event, out);
            case "tool/result" -> toolResult(event, out);
            case "interaction/requested" -> interactionRequested(event);
            case "interaction/resolved" -> interactionResolved(event);
            default -> {
                // Other events do not change the live AG-UI projection.
            }
        }
        return out;
    }

    /**
     * Reconstructs pending interactions observed after the cursor.
     *
     * @return interrupts in request order
     */
    List<AguiEvent.Interrupt> interrupts() {
        List<AguiEvent.Interrupt> out = new ArrayList<>();
        for (Interaction interaction : pending.values()) {
            if (interaction.requestId == null || interaction.requestId.isBlank()) {
                continue;
            }
            ToolUseBlock call = interaction.call;
            if (call == null || call.getId() == null || call.getId().isBlank()) {
                call =
                        ToolUseBlock.builder()
                                .id(interaction.requestId)
                                .name(UNKNOWN_TOOL)
                                .input(Map.of())
                                .build();
            }
            if ("confirmation".equalsIgnoreCase(interaction.kind)) {
                out.add(AguiInterrupts.permissionConfirm(interaction.turnId, null, call));
            } else {
                out.add(AguiInterrupts.externalExecution(interaction.turnId, null, call, null));
            }
        }
        return List.copyOf(out);
    }

    private void modelChunk(SessionEvent event, List<AguiEvent> out) {
        Map<String, Object> data = event.data();
        Object purpose = data.get("purpose");
        if (purpose != null && "SUMMARY".equalsIgnoreCase(String.valueOf(purpose))) {
            return;
        }
        Object chunkValue = data.get("chunk");
        if (!(chunkValue instanceof Map<?, ?> chunk)) {
            return;
        }
        String chunkId = string(chunk.get("id"));
        String modelCallId = string(data.get("modelCallId"));
        if (modelCallId == null) {
            modelCallId = chunkId;
        }
        if (modelCallId == null) {
            return;
        }
        Object content = chunk.get("content");
        if (!(content instanceof List<?> blocks)) {
            return;
        }
        String turnId = event.turnId();
        for (Object blockValue : blocks) {
            if (!(blockValue instanceof Map<?, ?>)) {
                continue;
            }
            ContentBlock block =
                    JsonUtils.getJsonCodec().convertValue(blockValue, ContentBlock.class);
            if (block instanceof TextBlock textBlock) {
                appendText(turnId, textBlock.getText(), out);
            } else if (block instanceof ThinkingBlock thinkingBlock) {
                appendThinking(turnId, thinkingBlock.getThinking(), out);
            } else if (block instanceof ToolUseBlock toolUse) {
                appendToolUse(modelCallId, toolUse, out);
            }
        }
    }

    private void appendText(String turnId, String delta, List<AguiEvent> out) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        String messageId = textMessageId(turnId);
        if (startedTextMessages.add(messageId)) {
            out.add(new AguiEvent.TextMessageStart(threadId, runId, messageId, ROLE_ASSISTANT));
        }
        out.add(new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
    }

    private void appendThinking(String turnId, String delta, List<AguiEvent> out) {
        if (!enableReasoning || delta == null || delta.isEmpty()) {
            return;
        }
        String messageId = reasoningMessageId(turnId);
        if (startedReasoningMessages.add(messageId)) {
            out.add(
                    new AguiEvent.ReasoningMessageStart(
                            threadId, runId, messageId, ROLE_REASONING));
        }
        out.add(new AguiEvent.ReasoningMessageContent(threadId, runId, messageId, delta));
    }

    private void appendToolUse(String modelCallId, ToolUseBlock toolUse, List<AguiEvent> out) {
        String toolCallId = toolUse.getId();
        if (isBlank(toolCallId)) {
            toolCallId = lastToolCallByModelCall.get(modelCallId);
        }
        if (isBlank(toolCallId)) {
            return;
        }
        lastToolCallByModelCall.put(modelCallId, toolCallId);

        String name = realToolName(toolUse.getName());
        ToolCallState state = toolCalls.computeIfAbsent(toolCallId, ToolCallState::new);
        state.modelCallId = modelCallId;
        if (name != null) {
            state.name = name;
        }
        String content = toolUse.getContent();
        String delta = null;
        if (content != null && !content.isEmpty()) {
            state.arguments.append(content);
            delta = content;
        } else if (toolUse.getInput() != null
                && !toolUse.getInput().isEmpty()
                && state.arguments.length() == 0) {
            state.arguments.append(JsonUtils.getJsonCodec().toJson(toolUse.getInput()));
        }

        if (!state.started && name != null) {
            out.add(new AguiEvent.ToolCallStart(threadId, runId, toolCallId, name));
            state.started = true;
            if (state.arguments.length() > 0 && shouldEmitArguments(state.name)) {
                out.add(
                        new AguiEvent.ToolCallArgs(
                                threadId, runId, toolCallId, state.arguments.toString()));
            }
        } else if (state.started && delta != null && shouldEmitArguments(state.name)) {
            out.add(new AguiEvent.ToolCallArgs(threadId, runId, toolCallId, delta));
        }
    }

    private void modelEnd(SessionEvent event, List<AguiEvent> out) {
        String modelCallId = string(event.data().get("modelCallId"));
        if (modelCallId == null) {
            return;
        }
        String turnId = event.turnId();
        if (startedTextMessages.remove(textMessageId(turnId))) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, textMessageId(turnId)));
        }
        if (startedReasoningMessages.remove(reasoningMessageId(turnId))) {
            out.add(new AguiEvent.ReasoningMessageEnd(threadId, runId, reasoningMessageId(turnId)));
        }
        for (ToolCallState state : toolCalls.values()) {
            if (!modelCallId.equals(state.modelCallId)) {
                continue;
            }
            if (!state.started) {
                out.add(
                        new AguiEvent.ToolCallStart(
                                threadId, runId, state.id, displayName(state.name)));
                state.started = true;
                if (state.arguments.length() > 0 && shouldEmitArguments(state.name)) {
                    out.add(
                            new AguiEvent.ToolCallArgs(
                                    threadId, runId, state.id, state.arguments.toString()));
                }
            }
            if (!state.ended) {
                out.add(new AguiEvent.ToolCallEnd(threadId, runId, state.id));
                state.ended = true;
            }
        }
    }

    private void toolRequested(SessionEvent event, List<AguiEvent> out) {
        Map<String, Object> data = event.data();
        String toolCallId = string(data.get("toolCallId"));
        ToolUseBlock call = toolUse(data.get("call"));
        if (isBlank(toolCallId) && call != null) {
            toolCallId = call.getId();
        }
        if (isBlank(toolCallId)) {
            return;
        }
        ToolCallState state = toolCalls.computeIfAbsent(toolCallId, ToolCallState::new);
        if (call != null) {
            String name = realToolName(call.getName());
            if (name != null) {
                state.name = name;
            }
            String arguments = JsonUtils.resolveToolCallArgsJson(call);
            if (state.arguments.length() == 0 || arguments.length() > state.arguments.length()) {
                state.arguments = new StringBuilder(arguments);
            }
        }
        if (!state.started) {
            out.add(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, toolCallId, displayName(state.name)));
            state.started = true;
            if (state.arguments.length() > 0 && shouldEmitArguments(state.name)) {
                out.add(
                        new AguiEvent.ToolCallArgs(
                                threadId, runId, toolCallId, state.arguments.toString()));
            }
        }
    }

    private void actionEnd(SessionEvent event, List<AguiEvent> out) {
        Map<String, Object> data = event.data();
        ToolResultBlock result = toolResult(data.get("result"));
        if (result == null || result.isSuspended()) {
            return;
        }
        String toolCallId = string(data.get("toolCallId"));
        if (isBlank(toolCallId)) {
            toolCallId = result.getId();
        }
        if (isBlank(toolCallId)) {
            return;
        }
        emitResult(out, toolCallId, result);
    }

    private void toolResult(SessionEvent event, List<AguiEvent> out) {
        Msg message = message(event);
        if (message == null) {
            return;
        }
        for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
            if (result.isSuspended() || isBlank(result.getId())) {
                continue;
            }
            emitResult(out, result.getId(), result);
        }
    }

    private void emitResult(List<AguiEvent> out, String toolCallId, ToolResultBlock result) {
        if (!emittedResults.add(toolCallId)) {
            return;
        }
        ToolCallState state = toolCalls.computeIfAbsent(toolCallId, ToolCallState::new);
        if (isBlank(state.name) && result.getName() != null) {
            state.name = result.getName();
        }
        if (state.started && !state.ended) {
            out.add(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
            state.ended = true;
        }
        out.add(
                new AguiEvent.ToolCallResult(
                        threadId,
                        runId,
                        toolCallId,
                        toolResultText(result),
                        ROLE_TOOL,
                        TOOL_RESULT_PREFIX + toolCallId));
    }

    private void interactionRequested(SessionEvent event) {
        Map<String, Object> data = event.data();
        String requestId = string(data.get("requestId"));
        if (isBlank(requestId)) {
            return;
        }
        String kind = string(data.get("kind"));
        ToolUseBlock call = toolUse(data.get("call"));
        pending.put(requestId, new Interaction(requestId, kind, call, event.turnId()));
    }

    private void interactionResolved(SessionEvent event) {
        String requestId = string(event.data().get("requestId"));
        if (requestId != null) {
            pending.remove(requestId);
        }
    }

    private boolean shouldEmitArguments(String toolName) {
        return emitToolCallArgs || externalToolNames.contains(toolName);
    }

    private static String textMessageId(String turnId) {
        return turnId;
    }

    private static String reasoningMessageId(String turnId) {
        return turnId + ":reasoning";
    }

    private static Msg message(SessionEvent event) {
        Object value = event.data().get("message");
        if (value == null) {
            return null;
        }
        return JsonUtils.getJsonCodec().convertValue(value, Msg.class);
    }

    private static ToolUseBlock toolUse(Object value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.getJsonCodec().convertValue(value, ToolUseBlock.class);
    }

    private static ToolResultBlock toolResult(Object value) {
        if (value == null) {
            return null;
        }
        return JsonUtils.getJsonCodec().convertValue(value, ToolResultBlock.class);
    }

    private static String toolResultText(ToolResultBlock result) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock output : result.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String realToolName(String name) {
        if (isBlank(name) || name.startsWith("__")) {
            return null;
        }
        return name;
    }

    private static String displayName(String name) {
        return isBlank(name) ? UNKNOWN_TOOL : name;
    }

    private record Interaction(String requestId, String kind, ToolUseBlock call, String turnId) {}

    private static final class ToolCallState {
        private final String id;
        private String modelCallId;
        private StringBuilder arguments = new StringBuilder();
        private String name;
        private boolean started;
        private boolean ended;

        private ToolCallState(String id) {
            this.id = id;
        }
    }
}
