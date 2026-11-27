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
package io.agentscope.durable.claude;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.durable.SessionStreamEvent;
import java.util.Objects;
import java.util.function.Consumer;

/** Direct bridge from a Durable stream to Claude session events. */
public final class ClaudeEventStreamSink implements Consumer<SessionStreamEvent> {
    private final ClaudeEventAdapter adapter;
    private final Consumer<JsonNode> events;

    public ClaudeEventStreamSink(Consumer<JsonNode> events) {
        this(new ClaudeEventAdapter(), events);
    }

    ClaudeEventStreamSink(ClaudeEventAdapter adapter, Consumer<JsonNode> events) {
        this.adapter = Objects.requireNonNull(adapter);
        this.events = Objects.requireNonNull(events);
    }

    @Override
    public void accept(SessionStreamEvent event) {
        adapter.map(event).forEach(events);
    }
}
