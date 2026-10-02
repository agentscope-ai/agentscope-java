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
package io.agentscope.harness.agent.gateway.channel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.Gateway;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Channel adapter — ingests inbound messages from one messaging platform (or a programmatic
 * caller), routes them through {@link ChannelRouter} to resolve the target agent and session, and
 * delegates execution to {@link Gateway}.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #init(Gateway)} — called by the gateway bootstrap to inject the auto-wired
 *       {@link Gateway} before start-up. Implementations should store the gateway and perform any
 *       pre-connection setup. The default is a no-op for channels that receive the gateway at
 *       construction time.
 *   <li>{@link #start()} — connects to the external event source (webhook endpoint, long-poll,
 *       websocket, etc.) and begins dispatching inbound messages. Programmatic channels may
 *       implement this as a no-op.
 *   <li>{@link #stop()} — disconnects and releases resources.
 * </ol>
 *
 * <h2>Message dispatch</h2>
 *
 * Implementations call {@link Gateway#run} with the {@link
 * io.agentscope.harness.agent.gateway.MsgContext} produced by {@link ChannelRouter#resolveRoute}.
 * The returned {@link Msg} reply is delivered back to the originating platform by the channel
 * adapter.
 *
 * @see ChannelConfig
 * @see ChannelRouter
 * @see Gateway
 */
public interface Channel {

    /**
     * Logical identifier for this channel (e.g. {@code "chatui"}, {@code "slack"},
     * {@code "discord"}). Must match {@link ChannelConfig#channelId()}.
     */
    String channelId();

    /** Returns the routing configuration for this channel. */
    ChannelConfig config();

    /**
     * Called by the gateway bootstrap before {@link #start()} to supply the auto-wired
     * {@link Gateway}. Implementations that need a gateway but do not receive it at construction
     * time should override this method. The default is a no-op.
     */
    default void init(Gateway gateway) {}

    /**
     * Connects to the external event source and starts dispatching inbound messages. Programmatic
     * channels may make this a no-op.
     */
    default void start() {}

    /**
     * Disconnects from the external event source and releases resources. Programmatic channels may
     * make this a no-op.
     */
    default void stop() {}

    /**
     * Dispatches a fully constructed {@link InboundMessage} through routing and returns the agent
     * reply. The channel implementation is responsible for:
     *
     * <ol>
     *   <li>Calling {@link ChannelRouter#resolveRoute} to obtain a {@link RouteResult} (which
     *       includes the {@link OutboundAddress} for reply routing)
     *   <li>Passing the {@link RouteResult#context()}, messages, and {@link
     *       RouteResult#outboundAddress()} to {@link Gateway#run}
     * </ol>
     */
    Mono<Msg> dispatch(InboundMessage message);

    /**
     * Streaming variant of {@link #dispatch(InboundMessage)}. Returns fine-grained
     * {@link AgentEvent}s instead of a single reply.
     *
     * <p>The default delegates to {@link Gateway#runStream} with the resolved route.
     */
    default Flux<AgentEvent> dispatchStream(InboundMessage message) {
        return Flux.error(new UnsupportedOperationException("Streaming dispatch not supported"));
    }

    /**
     * Delivers proactive outbound messages (e.g. subagent completion announces) to this channel's
     * transport. Called by the gateway when an agent produces a reply that needs to be pushed to
     * the originating channel/peer rather than returned synchronously.
     *
     * <p>The default implementation is a no-op, suitable for pull-based channels that do not
     * support proactive push.
     *
     * @param address the delivery target (peer, thread, account context)
     * @param messages the messages to deliver
     */
    default void deliver(OutboundAddress address, List<Msg> messages) {}

    /**
     * Applies a new routing {@link ChannelConfig} (bindings, dmScope, defaultAgentId) without
     * tearing down the channel's transport. Implementations that hold their {@link #config()} in a
     * mutable / volatile reference can override this to support hot reload of bindings.
     *
     * <p>The default implementation returns {@code false} to signal that this channel does not
     * support live config swap. Returning {@code true} means the swap was applied and subsequent
     * inbound messages will route under the new config.
     *
     * @param newConfig the new routing configuration to install (channelId must match
     *     {@link #channelId()})
     * @return {@code true} if the config was applied live; {@code false} if the channel requires a
     *     restart to pick up the new config
     */
    default boolean applyRoutingConfig(ChannelConfig newConfig) {
        return false;
    }
}
