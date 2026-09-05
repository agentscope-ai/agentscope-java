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
package io.agentscope.core.agui.store;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEvent.JsonPatchOperation;
import io.agentscope.core.agui.model.AguiFunctionCall;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.AguiToolCall;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-{@code threadId:runId} mutable folder that consumes outbound {@link AguiEvent}s and produces
 * a materialized {@link AguiThreadSnapshot}.
 *
 * <p>Seeded from {@link AguiSnapshotStore#find(String)} so messages / state / activities from
 * earlier runs survive. This is where the former CopilotKit replay workarounds
 * (resolved-interrupt suppression and dangling-tool-call synthesis) are absorbed: the snapshot
 * only ever retains the trailing unresolved interrupt, and dangling tool calls are closed with a
 * synthetic empty result so the browser never renders a stuck spinner — except for the tool that
 * belongs to an open interrupt, which is left pending.
 */
final class AguiSnapshotAccumulator {

    private final String threadId;
    private final String runId;

    /** Ordered message transcript, keyed by message id so upserts preserve position. */
    private final LinkedHashMap<String, AguiMessage> messages = new LinkedHashMap<>();

    /** Folded AG-UI state. */
    private Map<String, Object> state = new LinkedHashMap<>();

    /** Activity frames keyed by (messageId \0 activityType), insertion-ordered. */
    private final LinkedHashMap<String, AguiThreadSnapshot.ActivityFrame> activities =
            new LinkedHashMap<>();

    /** Assistant message builders keyed by message id, for text/tool-call folding. */
    private final LinkedHashMap<String, AssistantBuilder> assistantBuilders = new LinkedHashMap<>();

    /** Tool call frames keyed by tool call id. */
    private final LinkedHashMap<String, ToolCallFrame> toolCallFrames = new LinkedHashMap<>();

    /** Message id of the assistant turn currently receiving tool calls. */
    private String currentAssistantMessageId;

    private AguiEvent.RunFinishedOutcome pendingOutcome;

    AguiSnapshotAccumulator(String threadId, String runId, AguiThreadSnapshot seed) {
        this.threadId = threadId;
        this.runId = runId;
        if (seed != null) {
            for (AguiMessage message : seed.messages()) {
                if (message != null && message.getId() != null) {
                    messages.put(message.getId(), message);
                }
            }
            state.putAll(seed.state());
            for (AguiThreadSnapshot.ActivityFrame frame : seed.activities()) {
                activities.put(activityKey(frame.messageId(), frame.activityType()), frame);
            }
        }
    }

    /**
     * Consume one outbound AG-UI event and fold it into the accumulator.
     *
     * @param event the event to consume
     */
    void consume(AguiEvent event) {
        if (event == null) {
            return;
        }
        if (event instanceof AguiEvent.RunStarted runStarted) {
            captureInputMessages(runStarted);
        } else if (event instanceof AguiEvent.TextMessageStart start) {
            startAssistantText(start.messageId(), start.role());
        } else if (event instanceof AguiEvent.TextMessageContent content) {
            appendAssistantText(content.messageId(), content.delta());
        } else if (event instanceof AguiEvent.TextMessageEnd end) {
            endAssistantText(end.messageId());
        } else if (event instanceof AguiEvent.TextMessageChunk chunk) {
            handleTextMessageChunk(chunk);
        } else if (event instanceof AguiEvent.ToolCallStart toolCallStart) {
            startToolCall(toolCallStart.toolCallId(), toolCallStart.toolCallName(), null);
        } else if (event instanceof AguiEvent.ToolCallArgs args) {
            appendToolCallArgs(args.toolCallId(), args.delta());
        } else if (event instanceof AguiEvent.ToolCallChunk toolCallChunk) {
            handleToolCallChunk(toolCallChunk);
        } else if (event instanceof AguiEvent.ToolCallResult result) {
            recordToolCallResult(result);
        } else if (event instanceof AguiEvent.StateSnapshot stateSnapshot) {
            state = new LinkedHashMap<>(stateSnapshot.snapshot());
        } else if (event instanceof AguiEvent.StateDelta stateDelta) {
            state = AguiJsonPatch.apply(state, stateDelta.delta());
        } else if (event instanceof AguiEvent.ActivitySnapshot activitySnapshot) {
            applyActivitySnapshot(activitySnapshot);
        } else if (event instanceof AguiEvent.ActivityDelta activityDelta) {
            applyActivityDelta(activityDelta);
        } else if (event instanceof AguiEvent.RunFinished runFinished) {
            if (runFinished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome interrupt) {
                pendingOutcome = interrupt;
            } else {
                pendingOutcome = null;
            }
        } else if (event instanceof AguiEvent.RunError) {
            pendingOutcome = null;
        }
    }

    /**
     * Materialize the accumulated state into an immutable snapshot.
     *
     * <p>This is a read-only view of the <em>current</em> accumulated state: it builds a fresh
     * transcript without mutating the accumulator, so it is safe to call repeatedly and even after
     * additional events have been consumed.
     *
     * @return the materialized snapshot
     */
    AguiThreadSnapshot materialize() {
        // 1. Assistant messages carry accumulated tool calls. Work on a fresh copy so the live
        //    transcript stays untouched and materialize stays idempotent.
        LinkedHashMap<String, AguiMessage> transcript = new LinkedHashMap<>(messages);
        for (AssistantBuilder builder : assistantBuilders.values()) {
            List<AguiToolCall> calls =
                    builder.toolCallIds.stream()
                            .map(toolCallFrames::get)
                            .filter(java.util.Objects::nonNull)
                            .map(
                                    frame ->
                                            new AguiToolCall(
                                                    frame.toolCallId,
                                                    new AguiFunctionCall(
                                                            frame.name != null
                                                                    ? frame.name
                                                                    : "unknown",
                                                            frame.args.toString())))
                            .toList();
            transcript.put(
                    builder.messageId,
                    AguiMessage.textMessage(
                            builder.messageId, builder.role, builder.text.toString(), calls, null));
        }

        // 2. Resolved tool calls already appended their tool result messages (on result).

        // 3. Dangling tool calls get a synthetic empty result, unless the tool belongs to an open
        //    interrupt (then it genuinely still awaits the user and is left open).
        Set<String> openToolCallIds = openInterruptToolCallIds();
        for (ToolCallFrame frame : toolCallFrames.values()) {
            if (frame.resolved) {
                continue;
            }
            if (openToolCallIds.contains(frame.toolCallId)) {
                continue;
            }
            String resultId = syntheticResultId(frame.toolCallId);
            transcript.putIfAbsent(
                    resultId, AguiMessage.toolMessage(resultId, frame.toolCallId, null));
        }

        return new AguiThreadSnapshot(
                threadId,
                List.copyOf(transcript.values()),
                state,
                List.copyOf(activities.values()),
                pendingOutcome,
                runId,
                System.currentTimeMillis());
    }

    private void captureInputMessages(AguiEvent.RunStarted runStarted) {
        AguiEvent.RunStarted start = runStarted;
        if (start.input() == null || start.input().getMessages() == null) {
            return;
        }
        for (AguiMessage message : start.input().getMessages()) {
            if (message == null || message.getId() == null) {
                continue;
            }
            messages.putIfAbsent(message.getId(), message);
        }
    }

    private void startAssistantText(String messageId, String role) {
        if (messageId == null) {
            return;
        }
        currentAssistantMessageId = messageId;
        AssistantBuilder builder =
                assistantBuilders.computeIfAbsent(
                        messageId, ignored -> new AssistantBuilder(messageId));
        builder.role = role != null ? role : "assistant";
        // Ensure a placeholder exists in the transcript so ordering is preserved.
        messages.putIfAbsent(
                messageId, AguiMessage.textMessage(messageId, builder.role, null, List.of(), null));
    }

    private void appendAssistantText(String messageId, String delta) {
        if (messageId == null || delta == null) {
            return;
        }
        currentAssistantMessageId = messageId;
        AssistantBuilder builder =
                assistantBuilders.computeIfAbsent(
                        messageId, ignored -> new AssistantBuilder(messageId));
        builder.text.append(delta);
        messages.put(
                messageId,
                AguiMessage.textMessage(
                        messageId, builder.role, builder.text.toString(), List.of(), null));
    }

    private void endAssistantText(String messageId) {
        if (messageId == null) {
            return;
        }
        AssistantBuilder builder = assistantBuilders.get(messageId);
        if (builder != null) {
            messages.put(
                    messageId,
                    AguiMessage.textMessage(
                            messageId, builder.role, builder.text.toString(), List.of(), null));
        }
        currentAssistantMessageId = messageId;
    }

    private void handleTextMessageChunk(AguiEvent.TextMessageChunk chunk) {
        if (chunk.messageId() == null) {
            return;
        }
        startAssistantText(chunk.messageId(), chunk.role() != null ? chunk.role() : "assistant");
        if (chunk.delta() != null) {
            appendAssistantText(chunk.messageId(), chunk.delta());
        }
    }

    private void startToolCall(String toolCallId, String toolCallName, String parentMessageId) {
        if (toolCallId == null) {
            return;
        }
        ToolCallFrame frame =
                toolCallFrames.computeIfAbsent(
                        toolCallId, ignored -> new ToolCallFrame(toolCallId));
        if (toolCallName != null && !toolCallName.isBlank()) {
            frame.name = toolCallName;
        }
        linkToolCallToAssistant(frame, parentMessageId);
    }

    private void appendToolCallArgs(String toolCallId, String delta) {
        if (toolCallId == null || delta == null) {
            return;
        }
        ToolCallFrame frame = toolCallFrames.get(toolCallId);
        if (frame != null) {
            frame.args.append(delta);
        }
    }

    private void handleToolCallChunk(AguiEvent.ToolCallChunk chunk) {
        if (chunk.toolCallId() == null) {
            return;
        }
        // Chunk mode carries an explicit parentMessageId, so prefer it over the heuristic.
        startToolCall(chunk.toolCallId(), chunk.toolCallName(), chunk.parentMessageId());
        if (chunk.delta() != null) {
            appendToolCallArgs(chunk.toolCallId(), chunk.delta());
        }
    }

    private void recordToolCallResult(AguiEvent.ToolCallResult result) {
        ToolCallFrame frame = toolCallFrames.get(result.toolCallId());
        if (frame == null) {
            frame = new ToolCallFrame(result.toolCallId());
            toolCallFrames.put(result.toolCallId(), frame);
        }
        frame.resultContent = result.content();
        frame.resultMessageId = result.messageId();
        frame.resolved = true;
        String resultId =
                result.messageId() != null
                        ? result.messageId()
                        : syntheticResultId(result.toolCallId());
        messages.putIfAbsent(
                resultId, AguiMessage.toolMessage(resultId, result.toolCallId(), result.content()));
    }

    private void linkToolCallToAssistant(ToolCallFrame frame, String parentMessageId) {
        // Prefer an explicit parent (chunk mode) over the heuristic last-text-message owner.
        String owner = parentMessageId != null ? parentMessageId : currentAssistantMessageId;
        if (owner == null) {
            owner = "assistant:" + UUID.randomUUID();
            currentAssistantMessageId = owner;
        }
        AssistantBuilder builder = assistantBuilders.computeIfAbsent(owner, AssistantBuilder::new);
        // Ensure a placeholder exists in the transcript so ordering is preserved even when the
        // parent's TextMessageStart has not (or will not) arrive.
        messages.putIfAbsent(
                owner, AguiMessage.textMessage(owner, builder.role, null, List.of(), null));
        builder.addToolCallId(frame.toolCallId);
    }

    private void applyActivitySnapshot(AguiEvent.ActivitySnapshot snapshot) {
        String key = activityKey(snapshot.messageId(), snapshot.activityType());
        boolean replace = snapshot.replace() == null || snapshot.replace();
        if (replace) {
            activities.put(
                    key,
                    new AguiThreadSnapshot.ActivityFrame(
                            snapshot.messageId(), snapshot.activityType(), snapshot.content()));
        } else {
            AguiThreadSnapshot.ActivityFrame existing = activities.get(key);
            Map<String, Object> merged =
                    new LinkedHashMap<>(existing != null ? existing.content() : Map.of());
            merged.putAll(snapshot.content());
            activities.put(
                    key,
                    new AguiThreadSnapshot.ActivityFrame(
                            snapshot.messageId(), snapshot.activityType(), merged));
        }
    }

    private void applyActivityDelta(AguiEvent.ActivityDelta delta) {
        String key = activityKey(delta.messageId(), delta.activityType());
        AguiThreadSnapshot.ActivityFrame existing = activities.get(key);
        Map<String, Object> content =
                new LinkedHashMap<>(existing != null ? existing.content() : Map.of());
        List<JsonPatchOperation> patch = delta.patch();
        if (patch != null && !patch.isEmpty()) {
            content = AguiJsonPatch.apply(content, patch);
        }
        activities.put(
                key,
                new AguiThreadSnapshot.ActivityFrame(
                        delta.messageId(), delta.activityType(), content));
    }

    private Set<String> openInterruptToolCallIds() {
        if (!(pendingOutcome instanceof AguiEvent.RunFinishedInterruptOutcome interrupt)) {
            return Set.of();
        }
        List<AguiEvent.Interrupt> interrupts = interrupt.interrupts();
        if (interrupts == null || interrupts.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (AguiEvent.Interrupt i : interrupts) {
            if (i.toolCallId() != null) {
                ids.add(i.toolCallId());
            }
        }
        return ids;
    }

    private static String activityKey(String messageId, String activityType) {
        return messageId + "\u0000" + activityType;
    }

    private static String syntheticResultId(String toolCallId) {
        return toolCallId + ":result";
    }

    /** Mutable builder for an assistant message's text and linked tool calls. */
    private static final class AssistantBuilder {
        final String messageId;
        String role = "assistant";
        final StringBuilder text = new StringBuilder();
        final List<String> toolCallIds = new ArrayList<>();

        AssistantBuilder(String messageId) {
            this.messageId = messageId;
        }

        void addToolCallId(String toolCallId) {
            if (!toolCallIds.contains(toolCallId)) {
                toolCallIds.add(toolCallId);
            }
        }
    }

    /** Mutable holder for a tool call's streamed args and result. */
    private static final class ToolCallFrame {
        final String toolCallId;
        String name;
        final StringBuilder args = new StringBuilder();
        String resultContent;
        String resultMessageId;
        boolean resolved;

        ToolCallFrame(String toolCallId) {
            this.toolCallId = toolCallId;
        }
    }
}
