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
package io.agentscope.harness.coding.gateway;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.coding.channel.OutboundAddress;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Gateway-style entrypoint: route inbound turns by {@link MsgContext}, and bind the primary {@link
 * HarnessAgent} for announce delivery.
 *
 * <p>Implementations should serialize turns per logical session (fair wait) so injected announce
 * runs and user/channel {@link #run} calls do not race the same {@code HarnessAgent} turn (see
 * {@link HarnessGateway}).
 *
 * <p>Default implementation: {@link HarnessGateway}.
 */
public interface Gateway {

    /**
     * Binds the primary harness agent after {@link HarnessAgent} is constructed. The agent is
     * registered under its {@link HarnessAgent#getAgentId()} for routing by {@link #run}.
     */
    void bindMainAgent(HarnessAgent agent);

    /**
     * Registers a named harness agent for routing. {@link #run} will resolve the target agent by
     * the {@code agentId} supplied in {@link MsgContext#extra()} (key {@code "agentId"}), falling
     * back to the agent registered via {@link #bindMainAgent}.
     */
    default void registerAgent(String agentId, HarnessAgent agent) {}

    /** Inbound turn (direct API or channel adapter). */
    Mono<Msg> run(MsgContext context, List<Msg> messages);

    /**
     * Inbound turn with outbound address. The {@code outboundAddress} is recorded as the session's
     * "last route" so that proactive outbound messages (e.g. subagent announces) can be delivered
     * back to the originating channel/peer.
     *
     * @param context routing context for session key resolution
     * @param messages the inbound messages
     * @param outboundAddress the delivery target for proactive replies; may be null
     */
    default Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        return run(context, messages);
    }

    /** Single-message convenience. */
    default Mono<Msg> run(MsgContext context, Msg message) {
        return run(context, List.of(message));
    }
}
