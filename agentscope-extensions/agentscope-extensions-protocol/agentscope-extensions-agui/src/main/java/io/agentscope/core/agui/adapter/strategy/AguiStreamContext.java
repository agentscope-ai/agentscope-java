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
package io.agentscope.core.agui.adapter.strategy;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AguiStreamContext {

    private final String threadId;
    private final String runId;
    private final AguiAdapterConfig config;

    private final List<AguiEvent> pendingEvents = new ArrayList<>();

    private final Set<String> startedTextMessages = new LinkedHashSet<>();
    private final Set<String> endedTextMessages = new LinkedHashSet<>();
    private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> startedToolCalls = new LinkedHashSet<>();
    private final Set<String> endedToolCalls = new LinkedHashSet<>();
    private String currentTextMessageId;
    private String currentReasoningMessageId;
    private final Map<String, String> toolCallNames = new LinkedHashMap<>();
    private final Map<String, StringBuilder> toolResultContent = new LinkedHashMap<>();

    public AguiStreamContext(String threadId, String runId, AguiAdapterConfig config) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public String getThreadId() {
        return threadId;
    }

    public String getRunId() {
        return runId;
    }

    public AguiAdapterConfig getConfig() {
        return config;
    }

    public void beginEvent() {
        pendingEvents.clear();
    }

    public List<AguiEvent> drainEvents() {
        List<AguiEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    void emit(AguiEvent event) {
        pendingEvents.add(event);
    }

    public void startTextMessage(String messageId) {
        if (!startedTextMessages.contains(messageId)) {
            startedTextMessages.add(messageId);
            emit(new AguiEvent.TextMessageStart(threadId, runId, messageId, "assistant"));
        }
        currentTextMessageId = messageId;
    }

    public void appendTextDelta(String messageId, String delta) {
        if (delta != null && !delta.isEmpty()) {
            startTextMessage(messageId);
            emit(new AguiEvent.TextMessageContent(threadId, runId, messageId, delta));
        }
    }

    public void closeActiveTextMessage() {
        if (currentTextMessageId == null) {
            return;
        }
        closeTextMessage(currentTextMessageId);
    }

    public void closeTextMessage(String messageId) {
        if (messageId == null
                || !startedTextMessages.contains(messageId)
                || endedTextMessages.contains(messageId)) {
            return;
        }
        endedTextMessages.add(messageId);
        if (Objects.equals(messageId, currentTextMessageId)) {
            currentTextMessageId = null;
        }
        emit(new AguiEvent.TextMessageEnd(threadId, runId, messageId));
    }

    public void startReasoningMessage(String messageId) {
        if (!config.isEnableReasoning()) {
            return;
        }
        if (!startedReasoningMessages.contains(messageId)) {
            startedReasoningMessages.add(messageId);
            emit(new AguiEvent.ReasoningMessageStart(threadId, runId, messageId, "reasoning"));
        }
        currentReasoningMessageId = messageId;
    }

    public void appendReasoningDelta(String messageId, String delta) {
        if (!config.isEnableReasoning()) {
            return;
        }
        if (delta != null && !delta.isEmpty()) {
            startReasoningMessage(messageId);
            emit(new AguiEvent.ReasoningMessageContent(threadId, runId, messageId, delta));
        }
    }

    public void closeActiveReasoningMessage() {
        if (!config.isEnableReasoning() || currentReasoningMessageId == null) {
            return;
        }
        closeReasoningMessage(currentReasoningMessageId);
    }

    public void closeReasoningMessage(String messageId) {
        if (messageId == null
                || !startedReasoningMessages.contains(messageId)
                || endedReasoningMessages.contains(messageId)) {
            return;
        }
        endedReasoningMessages.add(messageId);
        if (Objects.equals(messageId, currentReasoningMessageId)) {
            currentReasoningMessageId = null;
        }
        emit(new AguiEvent.ReasoningMessageEnd(threadId, runId, messageId));
    }

    public void recordToolCall(String toolCallId, String toolCallName) {
        rememberToolCallName(toolCallId, toolCallName);
    }

    public void startToolCall(String toolCallId, String toolCallName) {
        if (!startedToolCalls.contains(toolCallId)) {
            startedToolCalls.add(toolCallId);
            rememberToolCallName(toolCallId, toolCallName);
            emit(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, toolCallId, toolCallNames.get(toolCallId)));
        }
    }

    public void appendToolCallArgs(String toolCallId, String toolCallName, String delta) {
        recordToolCall(toolCallId, toolCallName);
        if (config.isEmitToolCallArgs() && delta != null && !delta.isEmpty()) {
            startToolCall(toolCallId, toolCallName);
            emit(new AguiEvent.ToolCallArgs(threadId, runId, toolCallId, delta));
        }
    }

    public void endToolCall(String toolCallId, String toolCallName) {
        if (!startedToolCalls.contains(toolCallId)) {
            startToolCall(toolCallId, toolCallName);
        }
        if (!endedToolCalls.contains(toolCallId)) {
            endedToolCalls.add(toolCallId);
            emit(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
        }
    }

    public void beginToolResult(String toolCallId) {
        toolResultContent.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
    }

    public void appendToolResultText(String toolCallId, String delta) {
        beginToolResult(toolCallId);
        if (delta != null && !delta.isEmpty()) {
            toolResultContent.get(toolCallId).append(delta);
        }
    }

    public void appendToolResultData(String toolCallId, ContentBlock data) {
        beginToolResult(toolCallId);
        if (data == null) {
            return;
        }
        StringBuilder buffer = toolResultContent.get(toolCallId);
        if (!buffer.isEmpty()) {
            buffer.append("\n");
        }
        buffer.append(serialize(data));
    }

    public void endToolResult(String replyId, String toolCallId, String toolCallName) {
        endToolCall(toolCallId, toolCallName);
        StringBuilder content = toolResultContent.remove(toolCallId);
        emit(
                new AguiEvent.ToolCallResult(
                        threadId,
                        runId,
                        toolCallId,
                        content != null && !content.isEmpty() ? content.toString() : null,
                        "tool",
                        replyId));
    }

    public List<AguiEvent> finishPendingEvents() {
        pendingEvents.clear();
        closeActiveTextMessage();
        closeActiveReasoningMessage();
        // Enforce full lifecycle (START -> END) for recorded but not-yet-started tool calls,
        // preventing orphaned states in lazy-start / abort scenarios.
        Set<String> toolCallIds = new LinkedHashSet<>(toolCallNames.keySet());
        toolCallIds.addAll(startedToolCalls);
        for (String toolCallId : toolCallIds) {
            if (!endedToolCalls.contains(toolCallId)) {
                endToolCall(toolCallId, toolCallNames.get(toolCallId));
            }
        }
        return drainEvents();
    }

    public String idOrGenerated(String id) {
        return id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private void rememberToolCallName(String toolCallId, String toolCallName) {
        String normalizedName =
                toolCallName != null && !toolCallName.isBlank() ? toolCallName : "unknown";
        String existingName = toolCallNames.get(toolCallId);
        if (existingName == null
                || ("unknown".equals(existingName) && !"unknown".equals(normalizedName))) {
            toolCallNames.put(toolCallId, normalizedName);
        }
    }

    private static String serialize(ContentBlock data) {
        if (data instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        try {
            return JsonUtils.getJsonCodec().toJson(data);
        } catch (JsonException e) {
            return data.toString();
        }
    }
}
