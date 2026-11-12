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

package io.agentscope.extensions.durable.http;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.SessionEventStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * SSE framing for any host OutputStream (JDK HTTP, Servlet, etc.). Pair with SessionEventStream for
 * replay and acknowledgements. The host owns headers, authorization and the stream lifetime.
 * A write/flush failure propagates to the client so its cursor does not acknowledge that event.
 */
public final class SseEventWriter implements SessionEventStream.Sink {
    private final OutputStream output;
    private String acknowledged;

    public SseEventWriter(OutputStream output) {
        this.output = Objects.requireNonNull(output);
    }

    @Override
    public void accept(PublicEvent event) {
        String cursor = Objects.requireNonNull(event.cursor());
        if (cursor.indexOf('\r') >= 0 || cursor.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid SSE cursor");
        write("id: " + cursor + "\nevent: durable\ndata: " + DurableJson.value(event) + "\n\n");
        acknowledged = cursor;
    }

    @Override
    public void live(LiveEvent event) {
        write("event: live\ndata: " + DurableJson.value(event) + "\n\n");
    }

    /** Advance past filtered-out events without inventing a committed public event. */
    public void cursor(String cursor) {
        Objects.requireNonNull(cursor);
        if (cursor.equals(acknowledged)) return;
        if (cursor.indexOf('\r') >= 0 || cursor.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid SSE cursor");
        write("id: " + cursor + "\nevent: cursor\ndata: {}\n\n");
        acknowledged = cursor;
    }

    @Override
    public void idle() {
        heartbeat();
    }

    public void heartbeat() {
        write(": heartbeat\n\n");
    }

    @Override
    public void reset(Snapshot snapshot) {
        resync();
    }

    /** The observer must fetch a fresh snapshot before reconnecting; no cursor is acknowledged. */
    public void resync() {
        write("event: resync\ndata: {\"code\":\"CURSOR_EXPIRED\"}\n\n");
    }

    private void write(String frame) {
        try {
            output.write(frame.getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
