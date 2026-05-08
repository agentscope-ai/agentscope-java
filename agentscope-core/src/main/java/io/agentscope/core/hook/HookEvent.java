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
package io.agentscope.core.hook;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for all hook events.
 *
 * <p>This is a sealed class - only the predefined event types are permitted.
 * This enables exhaustive pattern matching in switch expressions.
 *
 * <p>All events provide access to common context:
 * <ul>
 *   <li>{@link #getAgent()} - The agent instance</li>
 *   <li>{@link #getMemory()} - Convenient access to agent's memory (may be null)</li>
 *   <li>{@link #getType()} - The event type</li>
 *   <li>{@link #getTimestamp()} - When the event occurred</li>
 * </ul>
 *
 * <p><b>System message lifecycle:</b> Every event carries a unified {@code systemMsg} field
 * that holds the single {@link MsgRole#SYSTEM} message visible to the LLM. {@code ReActAgent}
 * manages this field across the event lifecycle as follows:
 * <ol>
 *   <li>Seeded from {@code sysPrompt} at the start of each {@code call()} before
 *       {@link PreCallEvent} hooks run.</li>
 *   <li>After {@link PreCallEvent} hooks complete, the resulting system message is
 *       <em>frozen</em> as the base for the entire call.</li>
 *   <li>Before each {@link PreReasoningEvent} (and {@link PreSummaryEvent}), the frozen base
 *       is injected fresh into the event — hooks that run on these events always start from
 *       the same clean baseline and may append per-iteration content.</li>
 *   <li>Before {@code model.stream(...)} is called: the event's final system message is
 *       prepended to {@link PreReasoningEvent#getInputMessages()} as the first element.</li>
 * </ol>
 *
 * <p>Because each {@link PreReasoningEvent} starts from a fresh copy of the frozen base,
 * hooks that fire per-iteration (e.g. subagent guidance) can safely use
 * {@link #appendSystemContent(String)} — content is added to that iteration's copy and never
 * accumulates across iterations.
 *
 * <p>Hooks should modify the system message exclusively via {@link #setSystemMessage(Msg)},
 * {@link #appendSystemContent(String)}, or {@link #appendSystemContent(ContentBlock)}.
 * Injecting {@link MsgRole#SYSTEM} messages into {@code inputMessages} directly is forbidden
 * and results in an {@link IllegalStateException} at runtime.
 *
 * <p><b>Modifiability:</b> Whether an event allows modification is determined by
 * the presence of setter methods in the concrete event class.
 *
 * @see Hook
 * @see HookEventType
 */
public abstract sealed class HookEvent
        permits PreCallEvent, PostCallEvent, ReasoningEvent, ActingEvent, SummaryEvent, ErrorEvent {

    private final HookEventType type;
    private final Agent agent;
    private final long timestamp;

    /**
     * The unified system message for this event. Hooks read and write this field via the
     * helper methods below; {@code ReActAgent} propagates it between events and prepends it
     * to the LLM input before every reasoning call.
     */
    private Msg systemMsg;

    /**
     * Constructor for HookEvent.
     *
     * @param type The event type (must not be null)
     * @param agent The agent instance (must not be null)
     * @throws NullPointerException if type or agent is null
     */
    protected HookEvent(HookEventType type, Agent agent) {
        this.type = Objects.requireNonNull(type, "type cannot be null");
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Get the event type.
     *
     * @return The event type
     */
    public final HookEventType getType() {
        return type;
    }

    /**
     * Get the agent instance.
     *
     * @return The agent instance (never null)
     */
    public final Agent getAgent() {
        return agent;
    }

    /**
     * Get the timestamp when event was created.
     *
     * @return The timestamp (milliseconds since epoch)
     */
    public final long getTimestamp() {
        return timestamp;
    }

    /**
     * Convenient access to agent's memory.
     *
     * @return The memory, or null if agent doesn't have memory
     */
    public final Memory getMemory() {
        if (agent instanceof ReActAgent reactAgent) {
            return reactAgent.getMemory();
        }
        return null;
    }

    // ==================== System message API ====================

    /**
     * Returns the current unified system message, or {@code null} if none has been set.
     *
     * <p>On {@link PreCallEvent} and {@link PreReasoningEvent}, modifications made by earlier
     * hooks in the chain are already reflected here.
     *
     * @return the system message, may be null
     */
    public final Msg getSystemMessage() {
        return systemMsg;
    }

    /**
     * Replaces the entire system message with the given one.
     *
     * <p>Prefer {@link #appendSystemContent} when you only need to add a portion of the system
     * message; use this method only when you need to set a completely custom system message.
     *
     * @param systemMsg the new system message (may be null to clear)
     */
    public final void setSystemMessage(Msg systemMsg) {
        this.systemMsg = systemMsg;
    }

    /**
     * Appends the given text as a new {@link TextBlock} at the end of the system message.
     *
     * <p>If no system message exists yet, one is created automatically with
     * {@link MsgRole#SYSTEM} and name {@code "system"}.
     *
     * @param text the text to append (must not be null)
     */
    public final void appendSystemContent(String text) {
        Objects.requireNonNull(text, "text cannot be null");
        appendSystemContent(TextBlock.builder().text(text).build());
    }

    /**
     * Appends a {@link ContentBlock} at the end of the system message.
     *
     * <p>If no system message exists yet, one is created automatically with
     * {@link MsgRole#SYSTEM} and name {@code "system"}.
     *
     * @param block the content block to append (must not be null)
     */
    public final void appendSystemContent(ContentBlock block) {
        Objects.requireNonNull(block, "block cannot be null");
        if (systemMsg == null) {
            systemMsg = Msg.builder().name("system").role(MsgRole.SYSTEM).content(block).build();
        } else {
            List<ContentBlock> merged = new ArrayList<>(systemMsg.getContent());
            merged.add(block);
            systemMsg =
                    Msg.builder()
                            .id(systemMsg.getId())
                            .name(systemMsg.getName())
                            .role(MsgRole.SYSTEM)
                            .content(merged)
                            .metadata(systemMsg.getMetadata())
                            .timestamp(systemMsg.getTimestamp())
                            .build();
        }
    }
}
