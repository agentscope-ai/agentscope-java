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
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AguiStreamContext {

    // CopilotKit will merge reasoning and text with the same messageId, adding suffixes to the
    // reasoning to distinguish them
    public static final String REASONING_MESSAGE_ID_SUFFIX = "-reasoning";

    private static final Logger logger = LoggerFactory.getLogger(AguiStreamContext.class);

    private final String threadId;
    private final String runId;
    private final AguiAdapterConfig config;
    private final RunAgentInput runInput;

    private final List<AguiEvent> pendingEvents = new ArrayList<>();

    private final Set<String> startedTextMessages = new LinkedHashSet<>();
    private final Set<String> endedTextMessages = new LinkedHashSet<>();
    private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
    private final Set<String> startedToolCalls = new LinkedHashSet<>();
    private final Set<String> endedToolCalls = new LinkedHashSet<>();
    private String currentTextMessageId;
    private String currentReasoningMessageId;
    private final Map<String, StringBuilder> toolResultContent = new LinkedHashMap<>();
    private final Map<String, AguiEvent.Interrupt> pendingInterrupts = new LinkedHashMap<>();
    private final Set<String> warnedMissingToolCallIdOperations = new LinkedHashSet<>();
    private final TokenUsageAccumulator tokenUsageAccumulator = new TokenUsageAccumulator();

    public AguiStreamContext(String threadId, String runId, AguiAdapterConfig config) {
        this(threadId, runId, config, null);
    }

    public AguiStreamContext(
            String threadId, String runId, AguiAdapterConfig config, RunAgentInput runInput) {
        this.threadId = Objects.requireNonNull(threadId, "threadId cannot be null");
        this.runId = Objects.requireNonNull(runId, "runId cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.runInput = runInput;
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

    public RunAgentInput getRunInput() {
        return runInput;
    }

    public void beginEvent() {
        pendingEvents.clear();
    }

    public List<AguiEvent> drainEvents() {
        List<AguiEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public void emit(AguiEvent event) {
        pendingEvents.add(event);
    }

    TokenUsageAccumulator getTokenUsageAccumulator() {
        return tokenUsageAccumulator;
    }

    public void startTextMessage(String messageId) {
        if (startedTextMessages.add(messageId)) {
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
        String reasoningMessageId = reasoningMessageId(messageId);
        if (startedReasoningMessages.add(reasoningMessageId)) {
            emit(
                    new AguiEvent.ReasoningMessageStart(
                            threadId, runId, reasoningMessageId, "reasoning"));
        }
        currentReasoningMessageId = reasoningMessageId;
    }

    public void appendReasoningDelta(String messageId, String delta) {
        if (delta != null && !delta.isEmpty()) {
            startReasoningMessage(messageId);
            emit(
                    new AguiEvent.ReasoningMessageContent(
                            threadId, runId, reasoningMessageId(messageId), delta));
        }
    }

    public void closeActiveReasoningMessage() {
        if (currentReasoningMessageId == null) {
            return;
        }
        closeReasoningMessage(currentReasoningMessageId);
    }

    public void closeReasoningMessage(String messageId) {
        String reasoningMessageId = reasoningMessageId(messageId);
        if (reasoningMessageId == null
                || !startedReasoningMessages.contains(reasoningMessageId)
                || endedReasoningMessages.contains(reasoningMessageId)) {
            return;
        }
        endedReasoningMessages.add(reasoningMessageId);
        if (Objects.equals(reasoningMessageId, currentReasoningMessageId)) {
            currentReasoningMessageId = null;
        }
        emit(new AguiEvent.ReasoningMessageEnd(threadId, runId, reasoningMessageId));
    }

    public void startToolCall(String toolCallId, String toolCallName) {
        if (isBlank(toolCallId)) {
            warnMissingToolCallId("ToolCallStartEvent");
            return;
        }
        if (startedToolCalls.add(toolCallId)) {
            emit(
                    new AguiEvent.ToolCallStart(
                            threadId, runId, toolCallId, normalizeToolCallName(toolCallName)));
        }
    }

    public void appendToolCallArgs(String toolCallId, String delta) {
        if (!hasStartedToolCall(toolCallId, "ToolCallDeltaEvent")) {
            return;
        }
        if (delta != null && !delta.isEmpty()) {
            emit(new AguiEvent.ToolCallArgs(threadId, runId, toolCallId, delta));
        }
    }

    public void endToolCall(String toolCallId) {
        if (!hasStartedToolCall(toolCallId, "ToolCallEndEvent")) {
            return;
        }
        if (endedToolCalls.add(toolCallId)) {
            emit(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
        }
    }

    public void beginToolResult(String toolCallId) {
        if (!hasStartedToolCall(toolCallId, "ToolResultStartEvent")) {
            return;
        }
        toolResultContent.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
    }

    public void appendToolResultText(String toolCallId, String delta) {
        if (!hasStartedToolCall(toolCallId, "ToolResultTextDeltaEvent")) {
            return;
        }
        if (delta != null && !delta.isEmpty()) {
            toolResultBuffer(toolCallId).append(delta);
        }
    }

    public void appendToolResultData(String toolCallId, ContentBlock data) {
        if (!hasStartedToolCall(toolCallId, "ToolResultDataDeltaEvent")) {
            return;
        }
        if (data == null) {
            return;
        }
        StringBuilder buffer = toolResultBuffer(toolCallId);
        if (!buffer.isEmpty()) {
            buffer.append("\n");
        }
        buffer.append(serialize(data));
    }

    public void endToolResult(String replyId, String toolCallId) {
        if (!hasStartedToolCall(toolCallId, "ToolResultEndEvent")) {
            return;
        }
        if (endedToolCalls.add(toolCallId)) {
            emit(new AguiEvent.ToolCallEnd(threadId, runId, toolCallId));
        }

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

    public void markToolCallSuspended(String toolCallId) {
        if (!hasStartedToolCall(toolCallId, "ToolResultEndEvent")) {
            return;
        }
        toolResultContent.remove(toolCallId);
    }

    public void addInterrupt(AguiEvent.Interrupt interrupt) {
        Objects.requireNonNull(interrupt, "interrupt cannot be null");
        pendingInterrupts.put(interrupt.id(), interrupt);
    }

    public List<AguiEvent.Interrupt> getPendingInterrupts() {
        return List.copyOf(pendingInterrupts.values());
    }

    public List<AguiEvent> finishPendingEvents() {
        pendingEvents.clear();
        closeActiveTextMessage();
        closeActiveReasoningMessage();
        for (String toolCallId : startedToolCalls) {
            if (!endedToolCalls.contains(toolCallId)) {
                endToolCall(toolCallId);
            }
        }
        return drainEvents();
    }

    private boolean hasStartedToolCall(String toolCallId, String eventName) {
        if (isBlank(toolCallId)) {
            warnMissingToolCallId(eventName);
            return false;
        }
        return startedToolCalls.contains(toolCallId);
    }

    private StringBuilder toolResultBuffer(String toolCallId) {
        return toolResultContent.computeIfAbsent(toolCallId, ignored -> new StringBuilder());
    }

    private static String normalizeToolCallName(String toolCallName) {
        return toolCallName != null && !toolCallName.isBlank() ? toolCallName : "unknown";
    }

    private static String reasoningMessageId(String messageId) {
        if (messageId.endsWith(REASONING_MESSAGE_ID_SUFFIX)) {
            return messageId;
        }
        return messageId + REASONING_MESSAGE_ID_SUFFIX;
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void warnMissingToolCallId(String eventName) {
        if (!warnedMissingToolCallIdOperations.add(eventName)) {
            return;
        }
        logger.warn(
                "Ignoring {}: null/blank toolCallId. Upstream must supply a stable toolCallId per"
                        + " AG-UI protocol.",
                eventName);
    }

    static final class TokenUsageAccumulator {

        private long cumulativeInputTokens;
        private long cumulativeOutputTokens;
        private long cumulativeCachedTokens;
        private double cumulativeTime;

        TokenUsageSnapshot add(ChatUsage usage) {
            cumulativeInputTokens += usage.getInputTokens();
            cumulativeOutputTokens += usage.getOutputTokens();
            cumulativeCachedTokens += usage.getCachedTokens();
            cumulativeTime += usage.getTime();
            return new TokenUsageSnapshot(
                    new TokenUsage(
                            usage.getInputTokens(),
                            usage.getOutputTokens(),
                            usage.getCachedTokens(),
                            usage.getTime()),
                    new TokenUsage(
                            cumulativeInputTokens,
                            cumulativeOutputTokens,
                            cumulativeCachedTokens,
                            cumulativeTime));
        }
    }

    record TokenUsageSnapshot(TokenUsage delta, TokenUsage cumulative) {}

    record TokenUsage(long inputTokens, long outputTokens, long cachedTokens, double time) {

        long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
