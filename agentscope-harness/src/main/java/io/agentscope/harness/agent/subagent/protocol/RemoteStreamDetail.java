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
package io.agentscope.harness.agent.subagent.protocol;

import java.util.Locale;

/**
 * How much of a remote subagent's event stream the caller wants, sent as {@code context.detail}.
 *
 * <p>Each level is a superset of the previous one. The wire value is the lower-case enum name.
 */
public enum RemoteStreamDetail {

    /** Lifecycle, tool call boundaries, tool results and confirmation requests. */
    STATUS,

    /** {@link #STATUS} plus text and thinking deltas. The default for a streaming parent. */
    FULL,

    /**
     * {@link #FULL} plus every remaining {@link io.agentscope.core.event.AgentEvent} — block
     * boundaries, tool argument and tool output deltas, model calls with token usage, hints,
     * custom events — forwarded as {@link RemoteEventType#AGENT_EVENT}.
     *
     * <p>This is the only level that reproduces a local subagent's stream in full, at the cost of
     * noticeably more traffic on high-frequency runs.
     */
    VERBOSE;

    /** Lower-case wire value, e.g. {@code "verbose"}. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Whether this level includes events requiring at least {@code required}. */
    public boolean includes(RemoteStreamDetail required) {
        return required != null && ordinal() >= required.ordinal();
    }

    /** Parses a wire value, falling back to {@link #STATUS} for null or unknown input. */
    public static RemoteStreamDetail parse(String value) {
        if (value == null || value.isBlank()) {
            return STATUS;
        }
        for (RemoteStreamDetail level : values()) {
            if (level.name().equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        return STATUS;
    }
}
