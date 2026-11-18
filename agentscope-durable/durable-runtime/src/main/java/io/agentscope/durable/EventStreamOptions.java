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
package io.agentscope.durable;

import io.agentscope.durable.DurableTypes.LiveEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Options for one event stream or subscription. Delta previews are opt-in per connection. */
public final class EventStreamOptions {
    public enum EventDeltaType {
        MESSAGE,
        TOOL_ARGUMENTS
    }

    private final String cursor;
    private final Set<EventDeltaType> deltas;

    private EventStreamOptions(Builder builder) {
        cursor = builder.cursor;
        deltas = Collections.unmodifiableSet(EnumSet.copyOf(builder.deltas));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static EventStreamOptions defaults() {
        return builder().build();
    }

    public String cursor() {
        return cursor;
    }

    public Set<EventDeltaType> deltas() {
        return deltas;
    }

    boolean accepts(LiveEvent event) {
        return switch (event.type()) {
            case "message.started", "message.delta" -> deltas.contains(EventDeltaType.MESSAGE);
            case "tool.arguments.delta" -> deltas.contains(EventDeltaType.TOOL_ARGUMENTS);
            default -> false;
        };
    }

    static EventStreamOptions legacy(String cursor) {
        return builder().after(cursor).deltas(EventDeltaType.values()).build();
    }

    public static final class Builder {
        private String cursor;
        private final EnumSet<EventDeltaType> deltas = EnumSet.noneOf(EventDeltaType.class);

        private Builder() {}

        /** Resume committed event delivery after this Durable cursor. Null starts at history. */
        public Builder after(String value) {
            cursor = value;
            return this;
        }

        /** Enable the selected best-effort previews for this connection. */
        public Builder deltas(EventDeltaType... values) {
            Objects.requireNonNull(values);
            Arrays.stream(values).forEach(value -> deltas.add(Objects.requireNonNull(value)));
            return this;
        }

        public EventStreamOptions build() {
            return new EventStreamOptions(this);
        }
    }
}
