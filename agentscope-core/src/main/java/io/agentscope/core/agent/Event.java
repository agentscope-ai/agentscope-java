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
package io.agentscope.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.Msg;

/**
 * An event emitted during streaming agent execution.
 *
 * <p>Events provide a structured way to observe agent execution, with clear
 * type identification and completion status for each message.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * agent.stream(userMsg, options)
 *     .subscribe(event -> {
 *         switch (event.getType()) {
 *             case REASONING -> {
 *                 if (event.isLast()) {
 *                     System.out.println("✓ Reasoning complete");
 *                 } else {
 *                     System.out.print("...");  // Progress indicator
 *                 }
 *             }
 *             case TOOL_RESULT -> {
 *                 System.out.println("Tool: " + event.getMessage().getTextContent());
 *             }
 *         }
 *     });
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {

    private final EventType type;
    private final Msg message;
    private final boolean isLast;

    /**
     * Identifies the originating (sub)agent when this event was emitted by a nested subagent
     * during a parent {@code stream()} call. {@code null} for events emitted by the top-level
     * agent itself.
     */
    private final EventSource source;

    /**
     * Creates a new event (top-level agent — no source).
     *
     * @param type The event type (REASONING, TOOL_RESULT, etc.)
     * @param message The message content
     * @param isLast Whether this is the last/complete message for this event
     */
    public Event(EventType type, Msg message, boolean isLast) {
        this(type, message, isLast, null);
    }

    /**
     * Creates a new event with optional source.
     *
     * @param type The event type
     * @param message The message content
     * @param isLast Whether this is the last/complete message
     * @param source The originating subagent, or {@code null} for the top-level agent
     */
    @JsonCreator
    public Event(
            @JsonProperty("type") EventType type,
            @JsonProperty("message") Msg message,
            @JsonProperty("isLast") boolean isLast,
            @JsonProperty("source") EventSource source) {
        this.type = type;
        this.message = message;
        this.isLast = isLast;
        this.source = source;
    }

    /**
     * Returns a copy of this event with the given source attached. The original event is not
     * modified (immutable copy).
     *
     * @param source the originating subagent descriptor
     * @return new Event with {@code source} set
     */
    public Event withSource(EventSource source) {
        return new Event(this.type, this.message, this.isLast, source);
    }

    /**
     * Get the event type.
     *
     * <p>Use this to determine what kind of message this is and how to process it.
     *
     * @return The event type
     */
    public EventType getType() {
        return type;
    }

    /**
     * Get the message content.
     *
     * <p>The message contains the actual data - inspect {@link Msg#getRole()},
     * {@link Msg#getContent()}, and other fields for details.
     *
     * @return The message
     */
    public Msg getMessage() {
        return message;
    }

    /**
     * Check if this is the last/complete message for this event.
     *
     * <p><b>Return values:</b>
     * <ul>
     *   <li>{@code true}: Complete message or final chunk. Safe to persist,
     *       display as final, or trigger downstream processing.</li>
     *   <li>{@code false}: Intermediate chunk. More events with the same
     *       message ID will follow. Useful for real-time UI updates.</li>
     * </ul>
     *
     * <p><b>Streaming behavior:</b>
     * For streaming events (e.g., LLM streaming output), multiple events will
     * be emitted with the same {@link Msg#getId()}:
     * <pre>
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=false)
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=false)
     * Event(type=REASONING, msg(id="abc", content=[...]), isLast=true)  ← Final
     * </pre>
     *
     * <p><b>Non-streaming behavior:</b>
     * For non-streaming events, there will be only one event and {@code isLast}
     * is always {@code true}.
     *
     * <p><b>Example usage:</b>
     * <pre>{@code
     * agent.stream(userMsg, options)
     *     .subscribe(event -> {
     *         if (event.isLast()) {
     *             // Complete message - safe to persist or process
     *             database.save(event.getMessage());
     *         } else {
     *             // Intermediate chunk - update UI
     *             ui.append(event.getMessage().getTextContent());
     *         }
     *     });
     * }</pre>
     *
     * @return true if this is the last chunk, false if more chunks will follow
     */
    @JsonProperty("isLast")
    public boolean isLast() {
        return isLast;
    }

    /**
     * Returns the originating subagent descriptor, or {@code null} if this event was emitted by
     * the top-level agent.
     *
     * <p>Consumers can use this to route subagent events to the correct UI card or log channel
     * without needing out-of-band metadata.
     *
     * @return the event source, or {@code null} for the top-level agent
     */
    public EventSource getSource() {
        return source;
    }

    /**
     * Get the message ID (delegates to {@link Msg#getId()}).
     *
     * <p>Events with the same message ID are parts of the same logical message.
     * Use this to group streaming chunks together.
     *
     * @return The message ID
     */
    public String getMessageId() {
        return message.getId();
    }

    @Override
    public String toString() {
        if (source != null) {
            return String.format(
                    "Event{type=%s, isLast=%s, msgId=%s, contentBlocks=%d, source=%s}",
                    type, isLast, message.getId(), message.getContent().size(), source);
        }
        return String.format(
                "Event{type=%s, isLast=%s, msgId=%s, contentBlocks=%d}",
                type, isLast, message.getId(), message.getContent().size());
    }
}
