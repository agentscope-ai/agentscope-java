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
package io.agentscope.core.internal.stream;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Internal state tracker shared by stream annotators and middleware that reason about model replies.
 *
 * <p>This type is public only so internal components in different packages can share one set of
 * reply/source correlation rules. It is not a stable public API.
 */
public final class ReplyLifecycleTracker {

    public enum EventKind {
        MODEL_CALL_START,
        MODEL_CALL_END,
        TEXT_BLOCK_START,
        TEXT_BLOCK_DELTA,
        TEXT_BLOCK_END,
        TOOL_CALL_START,
        AGENT_RESULT,
        AGENT_END,
        OTHER
    }

    public record SourceKey(String source, String taskId) {

        public SourceKey {
            source = source == null ? "" : source;
            taskId = taskId == null ? "" : taskId;
        }

        public static SourceKey topLevel() {
            return new SourceKey("", "");
        }

        public boolean isTopLevel() {
            return source.isEmpty();
        }
    }

    public record ReplySnapshot(
            String replyId,
            boolean textSeen,
            boolean toolCallSeen,
            boolean dispositionEmitted,
            AgentResultEvent lastResult) {}

    public record Observation(
            SourceKey sourceKey,
            EventKind kind,
            String eventReplyId,
            boolean currentReplyEvent,
            ReplySnapshot before,
            ReplySnapshot after) {}

    private final Map<SourceKey, ReplyState> states = new LinkedHashMap<>();

    public SourceKey sourceKey(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        Object taskId =
                event.getMetadata() == null
                        ? null
                        : event.getMetadata().get(AgentEvent.METADATA_TASK_ID);
        return new SourceKey(event.getSource(), taskId == null ? null : taskId.toString());
    }

    public Observation observe(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        SourceKey sourceKey = sourceKey(event);
        ReplyState state = states.computeIfAbsent(sourceKey, ignored -> new ReplyState());
        ReplySnapshot before = state.snapshot();
        EventKind kind = eventKind(event);
        String eventReplyId = replyId(event);
        boolean currentReplyEvent =
                eventReplyId != null && Objects.equals(state.replyId, eventReplyId);

        switch (kind) {
            case MODEL_CALL_START -> {
                state.replyId = eventReplyId;
                state.textSeen = false;
                state.toolCallSeen = false;
                state.dispositionEmitted = false;
                currentReplyEvent = true;
            }
            case TEXT_BLOCK_DELTA -> {
                if (currentReplyEvent
                        && event instanceof TextBlockDeltaEvent delta
                        && delta.getDelta() != null
                        && !delta.getDelta().isEmpty()) {
                    state.textSeen = true;
                }
            }
            case TOOL_CALL_START -> {
                if (currentReplyEvent) {
                    state.toolCallSeen = true;
                }
            }
            case AGENT_RESULT -> state.lastResult = (AgentResultEvent) event;
            default -> {
                // The remaining event kinds do not mutate shared reply state.
            }
        }

        return new Observation(
                sourceKey, kind, eventReplyId, currentReplyEvent, before, state.snapshot());
    }

    public ReplySnapshot snapshot(SourceKey sourceKey) {
        ReplyState state = states.get(sourceKey);
        return state == null ? ReplyState.emptySnapshot() : state.snapshot();
    }

    public void markDispositionEmitted(SourceKey sourceKey) {
        states.computeIfAbsent(sourceKey, ignored -> new ReplyState()).dispositionEmitted = true;
    }

    public void clearReply(SourceKey sourceKey) {
        ReplyState state = states.get(sourceKey);
        if (state != null) {
            state.replyId = null;
            state.textSeen = false;
            state.toolCallSeen = false;
            state.dispositionEmitted = false;
        }
    }

    public void clearSource(SourceKey sourceKey) {
        states.remove(sourceKey);
    }

    public void clear() {
        states.clear();
    }

    private static EventKind eventKind(AgentEvent event) {
        if (event instanceof ModelCallStartEvent) {
            return EventKind.MODEL_CALL_START;
        }
        if (event instanceof ModelCallEndEvent) {
            return EventKind.MODEL_CALL_END;
        }
        if (event instanceof TextBlockStartEvent) {
            return EventKind.TEXT_BLOCK_START;
        }
        if (event instanceof TextBlockDeltaEvent) {
            return EventKind.TEXT_BLOCK_DELTA;
        }
        if (event instanceof TextBlockEndEvent) {
            return EventKind.TEXT_BLOCK_END;
        }
        if (event instanceof ToolCallStartEvent) {
            return EventKind.TOOL_CALL_START;
        }
        if (event instanceof AgentResultEvent) {
            return EventKind.AGENT_RESULT;
        }
        if (event instanceof AgentEndEvent) {
            return EventKind.AGENT_END;
        }
        return EventKind.OTHER;
    }

    private static String replyId(AgentEvent event) {
        if (event instanceof ModelCallStartEvent modelStart) {
            return modelStart.getReplyId();
        }
        if (event instanceof ModelCallEndEvent modelEnd) {
            return modelEnd.getReplyId();
        }
        if (event instanceof TextBlockStartEvent textStart) {
            return textStart.getReplyId();
        }
        if (event instanceof TextBlockDeltaEvent textDelta) {
            return textDelta.getReplyId();
        }
        if (event instanceof TextBlockEndEvent textEnd) {
            return textEnd.getReplyId();
        }
        if (event instanceof ToolCallStartEvent toolStart) {
            return toolStart.getReplyId();
        }
        if (event instanceof AgentEndEvent agentEnd) {
            return agentEnd.getReplyId();
        }
        return null;
    }

    private static final class ReplyState {
        private String replyId;
        private boolean textSeen;
        private boolean toolCallSeen;
        private boolean dispositionEmitted;
        private AgentResultEvent lastResult;

        private ReplySnapshot snapshot() {
            return new ReplySnapshot(
                    replyId, textSeen, toolCallSeen, dispositionEmitted, lastResult);
        }

        private static ReplySnapshot emptySnapshot() {
            return new ReplySnapshot(null, false, false, false, null);
        }
    }
}
