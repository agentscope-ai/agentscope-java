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
package io.agentscope.durable.examples.httpserver;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.DurableTypes.LiveEvent;
import io.agentscope.durable.DurableTypes.PublicEvent;
import io.agentscope.durable.DurableTypes.Snapshot;
import io.agentscope.durable.SessionEventStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Spring MVC SSE framing that acknowledges an event only after a successful send. */
final class SpringSseWriter implements SessionEventStream.Sink {
    private final SseEmitter emitter;
    private String acknowledged;

    SpringSseWriter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void accept(PublicEvent event) {
        String cursor = valid(event.cursor());
        send(
                SseEmitter.event()
                        .id(cursor)
                        .name("durable")
                        .data(DurableJson.value(event).toString()));
        acknowledged = cursor;
    }

    @Override
    public void live(LiveEvent event) {
        send(SseEmitter.event().name("live").data(DurableJson.value(event).toString()));
    }

    @Override
    public void cursor(String cursor) {
        String value = valid(cursor);
        if (value.equals(acknowledged)) return;
        send(SseEmitter.event().id(value).name("cursor").data("{}"));
        acknowledged = value;
    }

    @Override
    public void idle() {
        send(SseEmitter.event().comment("heartbeat"));
    }

    @Override
    public void reset(Snapshot snapshot) {
        send(SseEmitter.event().name("resync").data("{\"code\":\"CURSOR_EXPIRED\"}"));
    }

    void complete() {
        emitter.complete();
    }

    private void send(SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String valid(String cursor) {
        Objects.requireNonNull(cursor);
        if (cursor.indexOf('\r') >= 0 || cursor.indexOf('\n') >= 0)
            throw new IllegalArgumentException("Invalid SSE cursor");
        return cursor;
    }
}
