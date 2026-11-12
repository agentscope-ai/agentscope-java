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
package io.agentcore.managed.harness.agentscope;

import io.agentscope.core.session.SessionEventCodecRegistry;
import java.time.ZoneId;
import java.util.Map;

/** Required host continuation facts must be understood before their native prefix is restored. */
public final class AgentScopeEventSchemas {
    public static final String START = "managed/finalization_started";
    public static final String END = "managed/finalization_completed";
    public static final String MODEL = "managed/finalization_model";

    public static void register(SessionEventCodecRegistry registry) {
        registry.register(
                START,
                new SessionEventCodecRegistry.Codec(
                        data -> {
                            text(data, "id");
                            if (!(data.get("startedAt") instanceof Number at)
                                    || at.longValue() < 1
                                    || !(data.get("result") instanceof Map))
                                throw new IllegalArgumentException("Invalid finalization snapshot");
                            if (data.containsKey("stateJson")) text(data, "stateJson");
                            else if (!(data.get("checkpointRef") instanceof Map))
                                throw new IllegalArgumentException(
                                        "Missing finalization state reference");
                            text(data, "zone");
                            ZoneId.of((String) data.get("zone"));
                        },
                        (state, data) -> {}));
        registry.register(
                END,
                new SessionEventCodecRegistry.Codec(data -> text(data, "id"), (state, data) -> {}));
        registry.register(
                MODEL,
                new SessionEventCodecRegistry.Codec(
                        data -> {
                            text(data, "frame");
                            text(data, "modelCallId");
                            text(data, "requestHash");
                            if (!(data.get("slot") instanceof Number slot)
                                    || slot.longValue() < 0
                                    || !((String) data.get("requestHash")).matches("[a-f0-9]{64}"))
                                throw new IllegalArgumentException(
                                        "Invalid finalization model identity");
                        },
                        (state, data) -> {}));
    }

    private AgentScopeEventSchemas() {}

    private static class NativeRegistry {
        static final boolean READY = initialize();

        static boolean initialize() {
            register(SessionEventCodecRegistry.defaultRegistry());
            return true;
        }
    }

    public static void ensureRegistered() {
        if (!NativeRegistry.READY) throw new IllegalStateException("Native schemas unavailable");
    }

    private static void text(Map<String, Object> data, String key) {
        if (!(data.get(key) instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Missing continuation " + key);
    }
}
