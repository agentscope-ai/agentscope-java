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
package io.agentscope.core.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Fine-grained event types emitted during agent execution.
 *
 * <p>Aligned with AgentScope Python 2.0 EventType. Each value carries a Java-native
 * name plus a {@link JsonAlias} for the Python equivalent so JSON payloads can
 * be round-tripped between the two SDKs without translation. See
 * {@code docs/v2-design/RFC-000-event-naming.md} for the naming-divergence
 * rationale.
 *
 * <p>Python aliases recognised on deserialization:
 * <ul>
 *   <li>{@code RUN_STARTED} → {@link #REPLY_START}</li>
 *   <li>{@code RUN_FINISHED} → {@link #REPLY_END}</li>
 *   <li>{@code MODEL_CALL_STARTED} → {@link #MODEL_CALL_START}</li>
 *   <li>{@code MODEL_CALL_ENDED} → {@link #MODEL_CALL_END}</li>
 *   <li>{@code BINARY_BLOCK_*} → {@code DATA_BLOCK_*}</li>
 *   <li>{@code TOOL_RESULT_BINARY_DELTA} → {@link #TOOL_RESULT_DATA_DELTA}</li>
 * </ul>
 *
 * <p>Serialization always emits the Java-native form.
 */
public enum AgentEventType {
    @JsonAlias({"RUN_STARTED"})
    REPLY_START("REPLY_START"),
    @JsonAlias({"RUN_FINISHED"})
    REPLY_END("REPLY_END"),

    @JsonAlias({"MODEL_CALL_STARTED"})
    MODEL_CALL_START("MODEL_CALL_START"),
    @JsonAlias({"MODEL_CALL_ENDED"})
    MODEL_CALL_END("MODEL_CALL_END"),

    TEXT_BLOCK_START("TEXT_BLOCK_START"),
    TEXT_BLOCK_DELTA("TEXT_BLOCK_DELTA"),
    TEXT_BLOCK_END("TEXT_BLOCK_END"),

    THINKING_BLOCK_START("THINKING_BLOCK_START"),
    THINKING_BLOCK_DELTA("THINKING_BLOCK_DELTA"),
    THINKING_BLOCK_END("THINKING_BLOCK_END"),

    @JsonAlias({"BINARY_BLOCK_START"})
    DATA_BLOCK_START("DATA_BLOCK_START"),
    @JsonAlias({"BINARY_BLOCK_DELTA"})
    DATA_BLOCK_DELTA("DATA_BLOCK_DELTA"),
    @JsonAlias({"BINARY_BLOCK_END"})
    DATA_BLOCK_END("DATA_BLOCK_END"),

    TOOL_CALL_START("TOOL_CALL_START"),
    TOOL_CALL_DELTA("TOOL_CALL_DELTA"),
    TOOL_CALL_END("TOOL_CALL_END"),

    TOOL_RESULT_START("TOOL_RESULT_START"),
    TOOL_RESULT_TEXT_DELTA("TOOL_RESULT_TEXT_DELTA"),
    @JsonAlias({"TOOL_RESULT_BINARY_DELTA"})
    TOOL_RESULT_DATA_DELTA("TOOL_RESULT_DATA_DELTA"),
    TOOL_RESULT_END("TOOL_RESULT_END"),

    EXCEED_MAX_ITERS("EXCEED_MAX_ITERS"),

    REQUIRE_USER_CONFIRM("REQUIRE_USER_CONFIRM"),
    REQUIRE_EXTERNAL_EXECUTION("REQUIRE_EXTERNAL_EXECUTION"),
    USER_CONFIRM_RESULT("USER_CONFIRM_RESULT"),
    EXTERNAL_EXECUTION_RESULT("EXTERNAL_EXECUTION_RESULT");

    private final String value;

    AgentEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /**
     * Resolve an enum value from its Java-native string or any Python alias.
     *
     * <p>Falls back to a case-sensitive match against {@link #getValue()} and the
     * declared aliases when Jackson's default enum lookup misses (e.g. when an
     * agent reads a payload produced by the Python SDK).
     *
     * @param raw the incoming string value
     * @return the corresponding enum constant
     * @throws IllegalArgumentException when {@code raw} matches no value or alias
     */
    @JsonCreator
    public static AgentEventType fromValue(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("AgentEventType value must not be null");
        }
        for (AgentEventType type : values()) {
            if (type.value.equals(raw)) {
                return type;
            }
        }
        // Python aliases — keep the mapping co-located with the enum for grep-ability.
        return switch (raw) {
            case "RUN_STARTED" -> REPLY_START;
            case "RUN_FINISHED" -> REPLY_END;
            case "MODEL_CALL_STARTED" -> MODEL_CALL_START;
            case "MODEL_CALL_ENDED" -> MODEL_CALL_END;
            case "BINARY_BLOCK_START" -> DATA_BLOCK_START;
            case "BINARY_BLOCK_DELTA" -> DATA_BLOCK_DELTA;
            case "BINARY_BLOCK_END" -> DATA_BLOCK_END;
            case "TOOL_RESULT_BINARY_DELTA" -> TOOL_RESULT_DATA_DELTA;
            default -> throw new IllegalArgumentException("Unknown AgentEventType value: " + raw);
        };
    }
}
