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
package io.agentscope.claw2.runtime.channel;

import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.runtime.gateway.Gateway;
import io.agentscope.core.message.Msg;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Channel adapter — ingests inbound messages from one messaging platform (or a programmatic
 * caller), routes them through {@link ChannelRouter} to resolve the target agent and session, and
 * delegates execution to {@link io.agentscope.claw2.runtime.gateway.Gateway}.
 *
 * <p>Each implementation handles one channel protocol:
 *
 * <ul>
 *   <li>{@link io.agentscope.claw2.runtime.channel.chatui.ChatUiChannel} — direct programmatic / Chat UI
 *       calls (default implementation, no external transport)
 *   <li>Future: Slack, Discord, Telegram, WhatsApp adapters, etc.
 * </ul>
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>{@link #init(Gateway)} — called by {@link
 *       ClawBootstrap#start} to inject the auto-wired
 *       {@link Gateway} before start-up. Implementations should store the gateway and perform any
 *       pre-connection setup. The default is a no-op for channels that receive the gateway at
 *       construction time (e.g. {@code ChatUiChannel}).
 *   <li>{@link #start()} — connects to the external event source (webhook endpoint, long-poll,
 *       websocket, etc.) and begins dispatching inbound messages. Programmatic channels (e.g.
 *       {@code ChatUiChannel}) may implement this as a no-op.
 *   <li>{@link #stop()} — disconnects and releases resources.
 * </ol>
 *
 * <h2>Message dispatch</h2>
 *
 * Implementations call {@link io.agentscope.claw2.runtime.gateway.Gateway#run} with the {@link
 * io.agentscope.claw2.runtime.gateway.MsgContext} produced by {@link ChannelRouter#resolveRoute}. The
 * returned {@link Msg} reply is delivered back to the originating platform by the channel adapter.
 *
 * @see ChannelConfig
 * @see ChannelRouter
 * @see io.agentscope.claw2.runtime.gateway.Gateway
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
     * Called by {@link ClawBootstrap#start} before {@link
     * #start()} to supply the auto-wired {@link Gateway}. Implementations that need a gateway but do
     * not receive it at construction time should override this method. The default is a no-op.
     */
    default void init(Gateway gateway) {}

    /**
     * Connects to the external event source and starts dispatching inbound messages. Programmatic
     * channels (e.g. {@code ChatUiChannel}) may make this a no-op.
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
     *
     * <p>Useful when the caller has already built the {@link InboundMessage} (e.g. from a webhook
     * payload) and wants a reactive handle to the agent reply.
     */
    Mono<Msg> dispatch(InboundMessage message);

    /**
     * Delivers proactive outbound messages (e.g. subagent completion announces) to this channel's
     * transport. Called by the gateway when an agent produces a reply that needs to be pushed to
     * the originating channel/peer rather than returned synchronously.
     *
     * <p>The default implementation is a no-op, suitable for pull-based channels that do not
     * support proactive push. Channel adapters with an active transport (webhooks, websockets,
     * bot APIs) should override this to deliver the messages through their platform.
     *
     * @param address the delivery target (peer, thread, account context)
     * @param messages the messages to deliver
     */
    default void deliver(OutboundAddress address, List<Msg> messages) {}
}
