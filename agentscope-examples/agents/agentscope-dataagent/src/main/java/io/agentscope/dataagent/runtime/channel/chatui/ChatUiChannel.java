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
package io.agentscope.dataagent.runtime.channel.chatui;

import io.agentscope.core.message.Msg;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.channel.Channel;
import io.agentscope.dataagent.runtime.channel.ChannelConfig;
import io.agentscope.dataagent.runtime.channel.ChannelRouter;
import io.agentscope.dataagent.runtime.channel.DmScope;
import io.agentscope.dataagent.runtime.channel.InboundMessage;
import io.agentscope.dataagent.runtime.channel.OutboundAddress;
import io.agentscope.dataagent.runtime.channel.RouteResult;
import io.agentscope.dataagent.runtime.gateway.Gateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Mono;

/**
 * Default {@link Channel} implementation for direct Chat UI interactions: no external transport,
 * no webhook, no websocket — the caller submits a {@link ChatUiRequest} programmatically and
 * receives the agent reply reactively.
 *
 * <p>Suitable for:
 *
 * <ul>
 *   <li>Embedded web chat UIs that send HTTP requests to the harness
 *   <li>CLI tools and integration tests that need a typed API to the agent
 *   <li>Single-agent single-session scenarios where the default {@link DmScope#MAIN} collapses all
 *       conversations into one session
 * </ul>
 *
 * <h2>Obtaining an instance</h2>
 *
 * The recommended way is through {@link
 * DataAgentBootstrap#chatUiChannel()}, which wires the gateway
 * automatically:
 *
 * <pre>{@code
 * AgentBootstrap result = AgentBootstrap.builder().model(model).build();
 * ChatUiChannel chat = result.chatUiChannel();
 * chat.send("Hello!").block();
 * }</pre>
 *
 * Alternatively, a bare instance can be created via {@link #create()} (or {@link
 * #create(ChannelConfig)}) and passed to {@link
 * DataAgentBootstrap#start}, which calls {@link
 * #init(Gateway)} and {@link #start()} in order:
 *
 * <pre>{@code
 * ChatUiChannel chat = ChatUiChannel.create();       // no gateway yet
 * result.start(chat);                                // init() + start() called here
 * chat.send("Hello!").block();                       // gateway is now set
 * }</pre>
 *
 * <h2>Session routing</h2>
 *
 * Requests <em>without</em> a {@link ChatUiRequest#peerId()} are treated as a single shared DM
 * conversation when the channel config uses {@link DmScope#MAIN} (the default). Requests
 * <em>with</em> a {@code peerId} receive a per-peer session when the config uses {@link
 * DmScope#PER_PEER} or a finer scope — each distinct peer id yields its own memory-isolated
 * session.
 *
 * <h2>Agent routing</h2>
 *
 * The resolved {@code agentId} is placed in {@link
 * io.agentscope.dataagent.runtime.gateway.MsgContext#extra()} under key {@code "agentId"} so that {@link
 * io.agentscope.dataagent.runtime.gateway.HarnessGateway} can route to the correct registered agent.
 */
public final class ChatUiChannel implements Channel {

    /** Channel identifier for Chat UI. */
    public static final String CHANNEL_ID = "chatui";

    /** Null until {@link #init(Gateway)} is called (when constructed without a gateway). */
    private volatile Gateway gateway;

    /** Volatile so {@link #applyRoutingConfig} can hot-swap it from the admin UI. */
    private volatile ChannelConfig config;

    private final ChannelRouter router;

    /** Buffer for proactive outbound messages delivered by the gateway. */
    private final ConcurrentLinkedQueue<OutboundEnvelope> outboundQueue =
            new ConcurrentLinkedQueue<>();

    private ChatUiChannel(Gateway gateway, ChannelConfig config) {
        this.gateway = gateway; // may be null; set later via init()
        this.config = Objects.requireNonNull(config, "config");
        this.router = new ChannelRouter(null);
    }

    // -----------------------------------------------------------------
    //  Factories — no-gateway (for use with AgentBootstrap.start)
    // -----------------------------------------------------------------

    /**
     * Creates a Chat UI channel with the default {@link DmScope#MAIN} config. The gateway is not
     * required at construction time; pass the instance to {@link
     * DataAgentBootstrap#start} which will call {@link
     * #init(Gateway)} before {@link #start()}.
     */
    public static ChatUiChannel create() {
        return new ChatUiChannel(null, ChannelConfig.of(CHANNEL_ID));
    }

    /**
     * Creates a Chat UI channel with an explicit {@link ChannelConfig} and no pre-wired gateway.
     * Pass to {@link DataAgentBootstrap#start} for automatic
     * gateway injection.
     */
    public static ChatUiChannel create(ChannelConfig config) {
        return new ChatUiChannel(null, config);
    }

    /**
     * Creates a per-peer Chat UI channel with no pre-wired gateway. Each distinct {@link
     * ChatUiRequest#peerId()} gets its own session.
     */
    public static ChatUiChannel perPeer() {
        ChannelConfig cfg = ChannelConfig.builder(CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        return new ChatUiChannel(null, cfg);
    }

    // -----------------------------------------------------------------
    //  Factories — with gateway (advanced / direct wiring)
    // -----------------------------------------------------------------

    /**
     * Creates a Chat UI channel with a pre-wired gateway and the default {@link DmScope#MAIN}
     * config. Use when the gateway is available at construction time and {@link
     * DataAgentBootstrap#start} is not involved.
     */
    public static ChatUiChannel create(Gateway gateway) {
        return new ChatUiChannel(
                Objects.requireNonNull(gateway, "gateway"), ChannelConfig.of(CHANNEL_ID));
    }

    /**
     * Creates a Chat UI channel with a pre-wired gateway and an explicit {@link ChannelConfig}.
     * Useful for configuring {@link DmScope#PER_PEER} (multi-user) or adding binding rules.
     */
    public static ChatUiChannel create(Gateway gateway, ChannelConfig config) {
        return new ChatUiChannel(Objects.requireNonNull(gateway, "gateway"), config);
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

    /**
     * Injects the gateway when this channel was created without one (e.g. via {@link #create()}).
     * Has no effect if a gateway was already supplied at construction time. Called automatically by
     * {@link DataAgentBootstrap#start}.
     */
    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    /** No-op: {@code ChatUiChannel} is pull-based and does not need an external connection. */
    @Override
    public void start() {}

    /** No-op. */
    @Override
    public void stop() {}

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    /**
     * Hot-swaps the routing config without tearing down the channel. {@code newConfig.channelId()}
     * must equal {@link #CHANNEL_ID}; otherwise the swap is rejected and this returns {@code
     * false}.
     */
    @Override
    public boolean applyRoutingConfig(ChannelConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig");
        if (!CHANNEL_ID.equals(newConfig.channelId())) {
            return false;
        }
        this.config = newConfig;
        return true;
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway().run(route.context(), message.messages(), route.outboundAddress());
    }

    /**
     * Returns the {@link RouteResult} this channel would produce for {@code message} without
     * dispatching it. Lets callers compute the eventual session key (via
     * {@code result.context().canonicalKey()}) before sending — useful for SSE subscribers that
     * need to attach to the tool-event bus before the first turn registers a session.
     */
    public RouteResult previewRoute(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        return router.resolveRoute(config, message);
    }

    /**
     * Buffers proactive outbound messages delivered by the gateway (e.g. subagent completion
     * announces). Since {@code ChatUiChannel} is pull-based, callers retrieve these via {@link
     * #pollOutbound()}.
     */
    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages != null && !messages.isEmpty()) {
            outboundQueue.add(new OutboundEnvelope(address, messages));
        }
    }

    /**
     * Drains and returns all buffered proactive outbound messages. Returns an empty list if no
     * messages are pending. This is the pull-based complement to {@link #deliver}: the gateway
     * pushes announce replies here, and the caller (e.g. a chat UI polling loop) retrieves them.
     */
    public List<OutboundEnvelope> pollOutbound() {
        List<OutboundEnvelope> result = new ArrayList<>();
        OutboundEnvelope e;
        while ((e = outboundQueue.poll()) != null) {
            result.add(e);
        }
        return result;
    }

    /** Returns the number of buffered proactive outbound messages. */
    public int outboundQueueSize() {
        return outboundQueue.size();
    }

    // -----------------------------------------------------------------
    //  Convenience send APIs
    // -----------------------------------------------------------------

    /**
     * Sends a plain-text message in single-session mode (no peer id). Equivalent to {@link
     * #send(ChatUiRequest)} with {@link ChatUiRequest#of(String)}.
     */
    public Mono<Msg> send(String text) {
        return send(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /**
     * Sends a plain-text message from a specific peer. Equivalent to {@link
     * #send(ChatUiRequest)} with {@link ChatUiRequest#withPeer(String, String)}.
     */
    public Mono<Msg> send(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return send(ChatUiRequest.withPeer(peerId, text));
    }

    /** Sends a structured {@link ChatUiRequest} and returns the agent reply reactively. */
    public Mono<Msg> send(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        return dispatch(buildInbound(request));
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    private Gateway resolveGateway() {
        Gateway g = gateway;
        if (g == null) {
            throw new IllegalStateException(
                    "ChatUiChannel has no gateway. Either use AgentBootstrap.chatUiChannel(),"
                            + " pass this instance to AgentBootstrap.start(), or construct"
                            + " with ChatUiChannel.create(gateway).");
        }
        return g;
    }

    private InboundMessage buildInbound(ChatUiRequest request) {
        String peerId = request.peerId();
        if (peerId != null && !peerId.isBlank()) {
            return InboundMessage.dm(CHANNEL_ID, peerId.trim(), request.messages());
        }
        return InboundMessage.dm(CHANNEL_ID, "__anonymous__", request.messages());
    }
}
