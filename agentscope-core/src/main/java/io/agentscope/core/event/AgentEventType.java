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

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Fine-grained event types emitted during agent execution.
 *
 * <p>Aligned with AgentScope Python 2.0 EventType. Each type corresponds to
 * a specific phase or delta in the agent's reasoning/acting lifecycle.
 */
public enum AgentEventType {
    REPLY_START("REPLY_START"),
    REPLY_END("REPLY_END"),

    MODEL_CALL_START("MODEL_CALL_START"),
    MODEL_CALL_END("MODEL_CALL_END"),

    TEXT_BLOCK_START("TEXT_BLOCK_START"),
    TEXT_BLOCK_DELTA("TEXT_BLOCK_DELTA"),
    TEXT_BLOCK_END("TEXT_BLOCK_END"),

    THINKING_BLOCK_START("THINKING_BLOCK_START"),
    THINKING_BLOCK_DELTA("THINKING_BLOCK_DELTA"),
    THINKING_BLOCK_END("THINKING_BLOCK_END"),

    DATA_BLOCK_START("DATA_BLOCK_START"),
    DATA_BLOCK_DELTA("DATA_BLOCK_DELTA"),
    DATA_BLOCK_END("DATA_BLOCK_END"),

    TOOL_CALL_START("TOOL_CALL_START"),
    TOOL_CALL_DELTA("TOOL_CALL_DELTA"),
    TOOL_CALL_END("TOOL_CALL_END"),

    TOOL_RESULT_START("TOOL_RESULT_START"),
    TOOL_RESULT_TEXT_DELTA("TOOL_RESULT_TEXT_DELTA"),
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
}
