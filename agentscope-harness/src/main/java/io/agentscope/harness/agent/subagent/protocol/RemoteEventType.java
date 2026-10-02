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

/**
 * Stable wire event types for remote subagent streaming over Agent Protocol.
 *
 * <p>These values must remain backward-compatible across versions; unknown types should be ignored
 * by older clients.
 */
public enum RemoteEventType {
    RUN_STARTED,
    RUN_FINISHED,
    RUN_ERROR,
    TEXT_DELTA,
    THINKING_DELTA,
    TOOL_CALL_START,
    TOOL_CALL_END,
    TOOL_RESULT,
    REQUIRE_CONFIRM,
    STATUS,

    /**
     * Carries an {@link io.agentscope.core.event.AgentEvent} that has no dedicated wire type, fully
     * serialized in {@link RemoteAgentEvent#getPayload()}. Emitted only at {@code detail=verbose};
     * clients that predate this type skip it.
     */
    AGENT_EVENT
}
