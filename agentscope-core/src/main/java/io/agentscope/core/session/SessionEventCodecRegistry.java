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
package io.agentscope.core.session;

import io.agentscope.core.state.AgentState;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Explicit extension schemas. Required unknown versions/types always stop recovery. */
public final class SessionEventCodecRegistry {
    private static final SessionEventCodecRegistry DEFAULT = new SessionEventCodecRegistry();
    private final Map<String, Codec> extensions = new ConcurrentHashMap<>();

    public record Codec(
            Consumer<Map<String, Object>> validate,
            BiConsumer<AgentState, Map<String, Object>> apply) {
        public Codec {
            Objects.requireNonNull(validate);
            Objects.requireNonNull(apply);
        }
    }

    public static SessionEventCodecRegistry defaultRegistry() {
        return DEFAULT;
    }

    public void register(String type, Codec codec) {
        if (type == null || !type.contains("/") || SessionEventTypes.BUILTIN.contains(type))
            throw new IllegalArgumentException("Use a non-reserved namespaced event type");
        if (extensions.putIfAbsent(type, Objects.requireNonNull(codec)) != null)
            throw new IllegalArgumentException("Event codec already registered: " + type);
    }

    public void validate(SessionEvent event) {
        Codec codec = extensions.get(event.type());
        if (codec != null) codec.validate().accept(event.data());
        else if (event.required() && !SessionEventTypes.BUILTIN.contains(event.type()))
            throw new SessionLogException("Unknown required event: " + event.type());
    }

    public void applyExtension(AgentState state, SessionEvent event) {
        Codec codec = extensions.get(event.type());
        if (codec != null) codec.apply().accept(state, event.data());
    }
}
