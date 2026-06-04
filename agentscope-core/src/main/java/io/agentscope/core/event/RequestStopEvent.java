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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.message.GenerateReason;

/**
 * Requests the agent to pause execution after the current phase completes.
 *
 * <p>Emit this event from a {@link io.agentscope.core.middleware.MiddlewareBase} (e.g. inside
 * {@code onReasoning} or {@code onActing}) to stop the agent loop. The agent finishes the
 * in-flight step, then returns a {@code Msg} whose {@link GenerateReason} is set to
 * {@code generateReason} (defaults to {@link GenerateReason#MIDDLEWARE_STOP_REQUESTED}).
 *
 * <p>The pending state is persisted via {@link io.agentscope.core.message.ToolCallState} in
 * {@code AgentState.context}, so callers can resume by issuing a second {@code agent.call(...)}
 * — pending tool calls will be picked up automatically and execution continues.
 *
 * <p>Typical use cases:
 * <ul>
 *   <li>Cost / budget guards (stop when token usage exceeds a threshold)
 *   <li>Audit / compliance checkpoints (manual review before destructive actions)
 *   <li>Debug stepping (pause every iteration)
 *   <li>Content moderation gates
 * </ul>
 */
public class RequestStopEvent extends AgentEvent {

    private final String reason;
    private final GenerateReason generateReason;

    @JsonCreator
    public RequestStopEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("reason") String reason,
            @JsonProperty("generateReason") GenerateReason generateReason) {
        super(id, createdAt);
        this.reason = reason;
        this.generateReason =
                generateReason != null ? generateReason : GenerateReason.MIDDLEWARE_STOP_REQUESTED;
    }

    /**
     * Create a stop request with the default {@link GenerateReason#MIDDLEWARE_STOP_REQUESTED}.
     *
     * @param reason human-readable description of why the stop was requested
     */
    public RequestStopEvent(String reason) {
        this(reason, GenerateReason.MIDDLEWARE_STOP_REQUESTED);
    }

    /**
     * Create a stop request with an explicit generate reason. Used internally by Permission HITL
     * to surface {@link GenerateReason#PERMISSION_ASKING}.
     *
     * @param reason human-readable description
     * @param generateReason the reason to set on the returned Msg
     */
    public RequestStopEvent(String reason, GenerateReason generateReason) {
        this.reason = reason;
        this.generateReason =
                generateReason != null ? generateReason : GenerateReason.MIDDLEWARE_STOP_REQUESTED;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.REQUEST_STOP;
    }

    /** Human-readable description of why the stop was requested. */
    public String getReason() {
        return reason;
    }

    /** The {@link GenerateReason} to assign to the returned Msg. */
    public GenerateReason getGenerateReason() {
        return generateReason;
    }
}
