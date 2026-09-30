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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal state tracker shared by stream annotators and middleware that reason about model replies.
 *
 * <p>This type tracks only reply lifecycle: which reply is current for a source, whether visible text
 * or a tool call has been seen for it, and whether a disposition has already been emitted. Correlating
 * the authoritative {@link AgentResultEvent} with a source is the caller's responsibility.
 *
 * <p>It is public only so internal components in different packages (for example {@code
 * io.agentscope.core.middleware}) can share one set of reply/source correlation rules. It is not part
 * of the supported API surface and may change without notice; do not use it outside this project.
 * Instances are scoped to one subscription and must be accessed serially by that stream; the
 * tracker is intentionally not synchronized. When the child-source cap is reached, the least
 * recently used child is evicted; subsequent events for that source are treated as unclassified
 * until a new model call starts it again.
 */
public final class ReplyLifecycleTracker {

    private static final Logger log = LoggerFactory.getLogger(ReplyLifecycleTracker.class);

    static final int MAX_TRACKED_CHILD_SOURCES = 4096;

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
            return source.isEmpty() && taskId.isEmpty();
        }
    }

    public record ReplySnapshot(
            String replyId, boolean textSeen, boolean toolCallSeen, boolean dispositionEmitted) {}

    public record Observation(
            SourceKey sourceKey,
            EventKind kind,
            String eventReplyId,
            boolean currentReplyEvent,
            ReplySnapshot before,
            ReplySnapshot after) {}

    // Keep the active top-level reply outside the child cap so fan-out cannot evict it.
    private ReplyState topLevelState;

    private final Map<SourceKey, ReplyState> childStates = new LinkedHashMap<>();

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
        ReplyState state = state(sourceKey);
        ReplySnapshot before = state != null ? state.snapshot() : ReplyState.emptySnapshot();
        EventKind kind = eventKind(event);
        String eventReplyId = replyId(event);
        boolean currentReplyEvent =
                state != null
                        && eventReplyId != null
                        && Objects.equals(state.replyId, eventReplyId);

        switch (kind) {
            case MODEL_CALL_START -> {
                if (state == null) {
                    state = new ReplyState();
                    storeState(sourceKey, state);
                }
                state.replyId = eventReplyId;
                state.textSeen = false;
                state.toolCallSeen = false;
                state.dispositionEmitted = false;
                currentReplyEvent = true;
            }
            case TEXT_BLOCK_DELTA -> {
                if (state != null
                        && currentReplyEvent
                        && event instanceof TextBlockDeltaEvent delta
                        && delta.getDelta() != null
                        && !delta.getDelta().isEmpty()) {
                    state.textSeen = true;
                }
            }
            case TOOL_CALL_START -> {
                if (state != null && currentReplyEvent) {
                    state.toolCallSeen = true;
                }
            }
            default -> {
                // The remaining event kinds do not mutate shared reply state.
            }
        }

        return new Observation(
                sourceKey,
                kind,
                eventReplyId,
                currentReplyEvent,
                before,
                state != null ? state.snapshot() : ReplyState.emptySnapshot());
    }

    public ReplySnapshot snapshot(SourceKey sourceKey) {
        ReplyState state = state(sourceKey);
        return state == null ? ReplyState.emptySnapshot() : state.snapshot();
    }

    public void markDispositionEmitted(SourceKey sourceKey) {
        ReplyState state = state(sourceKey);
        if (state != null) {
            state.dispositionEmitted = true;
        }
    }

    /**
     * Number of sources currently holding reply state.
     *
     * <p>The active top-level reply is tracked outside the bounded child-source map, so this count
     * can exceed {@link #MAX_TRACKED_CHILD_SOURCES} by one.
     *
     * <p>Public only so internal stream annotators can account for all retained bookkeeping. Not part
     * of the supported API surface.
     */
    public int trackedSourceCount() {
        return (topLevelState != null ? 1 : 0) + childStates.size();
    }

    public void clearReply(SourceKey sourceKey) {
        ReplyState state = state(sourceKey);
        if (state != null) {
            state.replyId = null;
            state.textSeen = false;
            state.toolCallSeen = false;
            state.dispositionEmitted = false;
        }
    }

    public void clearSource(SourceKey sourceKey) {
        if (sourceKey != null && sourceKey.isTopLevel()) {
            topLevelState = null;
        } else {
            childStates.remove(sourceKey);
        }
    }

    public void clear() {
        topLevelState = null;
        childStates.clear();
    }

    private ReplyState state(SourceKey sourceKey) {
        if (sourceKey == null) {
            return null;
        }
        return sourceKey.isTopLevel() ? topLevelState : childStates.get(sourceKey);
    }

    private void storeState(SourceKey sourceKey, ReplyState state) {
        Objects.requireNonNull(sourceKey, "sourceKey");
        if (sourceKey.isTopLevel()) {
            topLevelState = state;
        } else {
            childStates.put(sourceKey, state);
            if (childStates.size() > MAX_TRACKED_CHILD_SOURCES) {
                Iterator<Map.Entry<SourceKey, ReplyState>> iterator =
                        childStates.entrySet().iterator();
                Map.Entry<SourceKey, ReplyState> evicted = iterator.next();
                iterator.remove();
                log.debug(
                        "Evicted child reply state for {} after reaching cap {}; later events are"
                                + " unclassified",
                        evicted.getKey(),
                        MAX_TRACKED_CHILD_SOURCES);
            }
        }
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

        private ReplySnapshot snapshot() {
            return new ReplySnapshot(replyId, textSeen, toolCallSeen, dispositionEmitted);
        }

        private static ReplySnapshot emptySnapshot() {
            return new ReplySnapshot(null, false, false, false);
        }
    }
}
