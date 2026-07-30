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

import java.util.Objects;

/**
 * Utilities for working with AG-UI events.
 */
public final class AguiEvents {

    private AguiEvents() {}

    /**
     * Copies an event while replacing only the AG-UI base event properties.
     *
     * @param event The event to copy
     * @param timestamp The timestamp for the copied event
     * @param rawEvent The raw source event for the copied event
     * @return A copied event with the supplied base properties
     */
    public static AguiEvent withBaseProperties(AguiEvent event, Long timestamp, Object rawEvent) {
        Objects.requireNonNull(event, "event cannot be null");

        if (event instanceof AguiEvent.RunStarted e) {
            return new AguiEvent.RunStarted(
                    e.threadId(), e.runId(), e.parentRunId(), e.input(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.RunFinished e) {
            return new AguiEvent.RunFinished(
                    e.threadId(), e.runId(), e.result(), e.outcome(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.RunError e) {
            return new AguiEvent.RunError(
                    e.threadId(), e.runId(), e.message(), e.code(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.StepStarted e) {
            return new AguiEvent.StepStarted(
                    e.threadId(), e.runId(), e.stepName(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.StepFinished e) {
            return new AguiEvent.StepFinished(
                    e.threadId(), e.runId(), e.stepName(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.TextMessageStart e) {
            return new AguiEvent.TextMessageStart(
                    e.threadId(), e.runId(), e.messageId(), e.role(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.TextMessageContent e) {
            return new AguiEvent.TextMessageContent(
                    e.threadId(), e.runId(), e.messageId(), e.delta(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.TextMessageEnd e) {
            return new AguiEvent.TextMessageEnd(
                    e.threadId(), e.runId(), e.messageId(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.TextMessageChunk e) {
            return new AguiEvent.TextMessageChunk(
                    e.threadId(),
                    e.runId(),
                    e.messageId(),
                    e.role(),
                    e.delta(),
                    e.name(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.ToolCallStart e) {
            return new AguiEvent.ToolCallStart(
                    e.threadId(), e.runId(), e.toolCallId(), e.toolCallName(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ToolCallArgs e) {
            return new AguiEvent.ToolCallArgs(
                    e.threadId(), e.runId(), e.toolCallId(), e.delta(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ToolCallEnd e) {
            return new AguiEvent.ToolCallEnd(
                    e.threadId(), e.runId(), e.toolCallId(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ToolCallChunk e) {
            return new AguiEvent.ToolCallChunk(
                    e.threadId(),
                    e.runId(),
                    e.toolCallId(),
                    e.toolCallName(),
                    e.parentMessageId(),
                    e.delta(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.ToolCallResult e) {
            return new AguiEvent.ToolCallResult(
                    e.threadId(),
                    e.runId(),
                    e.toolCallId(),
                    e.content(),
                    e.role(),
                    e.messageId(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.StateSnapshot e) {
            return new AguiEvent.StateSnapshot(
                    e.threadId(), e.runId(), e.snapshot(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.StateDelta e) {
            return new AguiEvent.StateDelta(
                    e.threadId(), e.runId(), e.delta(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.MessagesSnapshot e) {
            return new AguiEvent.MessagesSnapshot(
                    e.threadId(), e.runId(), e.messages(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ActivitySnapshot e) {
            return new AguiEvent.ActivitySnapshot(
                    e.threadId(),
                    e.runId(),
                    e.messageId(),
                    e.activityType(),
                    e.content(),
                    e.replace(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.ActivityDelta e) {
            return new AguiEvent.ActivityDelta(
                    e.threadId(),
                    e.runId(),
                    e.messageId(),
                    e.activityType(),
                    e.patch(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.Raw e) {
            return new AguiEvent.Raw(
                    e.threadId(), e.runId(), e.event(), e.source(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.Custom e) {
            return new AguiEvent.Custom(
                    e.threadId(), e.runId(), e.name(), e.value(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningStart e) {
            return new AguiEvent.ReasoningStart(
                    e.threadId(),
                    e.runId(),
                    e.messageId(),
                    e.encryptedContent(),
                    timestamp,
                    rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningMessageStart e) {
            return new AguiEvent.ReasoningMessageStart(
                    e.threadId(), e.runId(), e.messageId(), e.role(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningMessageContent e) {
            return new AguiEvent.ReasoningMessageContent(
                    e.threadId(), e.runId(), e.messageId(), e.delta(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningMessageEnd e) {
            return new AguiEvent.ReasoningMessageEnd(
                    e.threadId(), e.runId(), e.messageId(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningMessageChunk e) {
            return new AguiEvent.ReasoningMessageChunk(
                    e.threadId(), e.runId(), e.messageId(), e.delta(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningEnd e) {
            return new AguiEvent.ReasoningEnd(
                    e.threadId(), e.runId(), e.messageId(), timestamp, rawEvent);
        }
        if (event instanceof AguiEvent.ReasoningEncryptedValue e) {
            return new AguiEvent.ReasoningEncryptedValue(
                    e.threadId(),
                    e.runId(),
                    e.subtype(),
                    e.entityId(),
                    e.encryptedValue(),
                    timestamp,
                    rawEvent);
        }

        throw new IllegalArgumentException("Unsupported AG-UI event type: " + event.getClass());
    }
}
