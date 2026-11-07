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
 * One AG-UI event plus its optional AgentScope replay cursor.
 *
 * <p>The cursor is the native {@code SessionEvent.seq()} of the committed event that produced this
 * frame. It is exposed as the SSE {@code id} so clients can reconnect with {@code Last-Event-ID}.
 * A negative {@code seq} means the frame has no cursor (ordinary live runs, or frames preceding the
 * last frame of a multi-frame committed event).
 *
 * <p>{@code sourceSeq} is the sequence of the event that produced the frame, whether or not this
 * particular frame advances the cursor. It lets a consumer filter whole events, which the cursor
 * alone cannot do because only the last frame of an event is cursored.
 *
 * <p>The cursor is an AgentScope extension to AG-UI transport, not an AG-UI protocol field.
 *
 * @param seq cursor, or a negative value when this frame advances no cursor
 * @param sourceSeq sequence of the event that produced this frame, or negative when unknown
 * @param event the AG-UI event
 */
public record AguiFramedEvent(long seq, long sourceSeq, AguiEvent event) {

    public AguiFramedEvent {
        Objects.requireNonNull(event, "event cannot be null");
    }

    /**
     * Creates a frame whose event sequence equals its cursor.
     *
     * @param seq cursor and source sequence
     * @param event the AG-UI event
     */
    public AguiFramedEvent(long seq, AguiEvent event) {
        this(seq, seq, event);
    }

    /**
     * Wraps an event without a replay cursor.
     *
     * @param event the AG-UI event
     * @return a cursor-less frame
     */
    public static AguiFramedEvent uncursored(AguiEvent event) {
        return new AguiFramedEvent(-1, -1, event);
    }

    /**
     * Whether this frame advances the client's replay cursor.
     *
     * @return {@code true} when {@code seq >= 0}
     */
    public boolean hasCursor() {
        return seq >= 0;
    }

    /**
     * Whether this frame belongs to an event after the given sequence.
     *
     * <p>Unlike {@link #hasCursor()}, this also covers the leading frames of a multi-frame event,
     * so a consumer can drop an entire event it has already delivered.
     *
     * @param cursor the last delivered event sequence
     * @return {@code true} when the frame's source event is newer than {@code cursor}
     */
    public boolean isAfter(long cursor) {
        return sourceSeq > cursor;
    }
}
