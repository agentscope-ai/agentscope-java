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
import io.agentscope.core.message.Msg;

/**
 * Emitted when an agent successfully finishes processing an invocation, carrying the final result
 * message.
 *
 * <p>This event is emitted as part of the agent event stream (both {@code call()} and
 * {@code streamEvents()} paths) immediately before {@link AgentEndEvent}. Callers of
 * {@code streamEvents()} can filter for this event to obtain the final {@link Msg} directly
 * from the event stream without subscribing to the {@code Mono<Msg>} return value separately.
 *
 * <p>{@code call()} internally uses this event to extract the result from the shared
 * {@code buildAgentStream()} core, ensuring both paths run through the same {@code onAgent}
 * middleware chain.
 */
public class AgentResultEvent extends AgentEvent {

    private final Msg result;

    public AgentResultEvent(Msg result) {
        this.result = result;
    }

    @JsonCreator
    public AgentResultEvent(
            @JsonProperty("id") String id,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("result") Msg result) {
        super(id, createdAt);
        this.result = result;
    }

    @Override
    public AgentEventType getType() {
        return AgentEventType.AGENT_RESULT;
    }

    public Msg getResult() {
        return result;
    }
}
