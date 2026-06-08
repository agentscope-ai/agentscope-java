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
package io.agentscope.harness.agent.gateway.channel.chatui;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default {@link Channel} implementation for direct Chat UI interactions: no external transport,
 * no webhook, no websocket — the caller submits a {@link ChatUiRequest} programmatically and
 * receives the agent reply reactively.
 *
 * <p>Suitable for:
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
 * io.agentscope.harness.agent.gateway.GatewayBootstrap#chatUiChannel()}:
 *
 * <pre>{@code
 * GatewayBootstrap gw = GatewayBootstrap.builder().model(model)
 *     .agent("main", b -> b.name("assistant").sysPrompt("..."))
 *     .build();
 * ChatUiChannel chat = gw.chatUiChannel();
 * chat.send("Hello!").block();
 * }</pre>
 */
public final class ChatUiChannel implements Channel {

    public static final String CHANNEL_ID = "chatui";

    private volatile Gateway gateway;
    private volatile ChannelConfig config;
    private final ChannelRouter router;
    private final ConcurrentLinkedQueue<OutboundEnvelope> outboundQueue =
            new ConcurrentLinkedQueue<>();

    private ChatUiChannel(Gateway gateway, ChannelConfig config) {
        this.gateway = gateway;
        this.config = Objects.requireNonNull(config, "config");
        this.router = new ChannelRouter(null);
    }

    // -----------------------------------------------------------------
    //  Factories
    // -----------------------------------------------------------------

    /** Creates a Chat UI channel with the default {@link DmScope#MAIN} config. */
    public static ChatUiChannel create() {
        return new ChatUiChannel(null, ChannelConfig.of(CHANNEL_ID));
    }

    /** Creates a Chat UI channel with an explicit config. */
    public static ChatUiChannel create(ChannelConfig config) {
        return new ChatUiChannel(null, config);
    }

    /** Creates a per-peer Chat UI channel. Each distinct peerId gets its own session. */
    public static ChatUiChannel perPeer() {
        ChannelConfig cfg = ChannelConfig.builder(CHANNEL_ID).dmScope(DmScope.PER_PEER).build();
        return new ChatUiChannel(null, cfg);
    }

    /** Creates a Chat UI channel with a pre-wired gateway. */
    public static ChatUiChannel create(Gateway gateway) {
        return new ChatUiChannel(
                Objects.requireNonNull(gateway, "gateway"), ChannelConfig.of(CHANNEL_ID));
    }

    /** Creates a Chat UI channel with a pre-wired gateway and explicit config. */
    public static ChatUiChannel create(Gateway gateway, ChannelConfig config) {
        return new ChatUiChannel(Objects.requireNonNull(gateway, "gateway"), config);
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {}

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
     * dispatching it. Useful for pre-computing the session key before sending.
     */
    public RouteResult previewRoute(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        return router.resolveRoute(config, message);
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages != null && !messages.isEmpty()) {
            outboundQueue.add(new OutboundEnvelope(address, messages));
        }
    }

    /**
     * Drains and returns all buffered proactive outbound messages. Returns an empty list if no
     * messages are pending.
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

    /** Sends a plain-text message in single-session mode (no peer id). */
    public Mono<Msg> send(String text) {
        return send(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /** Sends a plain-text message from a specific peer. */
    public Mono<Msg> send(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return send(ChatUiRequest.withPeer(peerId, text));
    }

    /**
     * Sends a plain-text message with explicit routing identity. The {@link SendOptions} determines
     * the user identity and session key directly — no {@link DmScope} configuration required.
     *
     * @see SendOptions#userId(String)
     * @see SendOptions#of(String, String)
     */
    public Mono<Msg> send(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchWithOptions(options, List.of(msg));
    }

    /** Sends a message directly to an exposed subagent, bypassing normal routing. */
    public Mono<Msg> sendToSubagent(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagent(subagentId, List.of(msg));
    }

    /** Sends a structured {@link ChatUiRequest} and returns the agent reply reactively. */
    public Mono<Msg> send(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagent(request.subagentId(), request.messages());
        }
        return dispatch(buildInbound(request));
    }

    // -----------------------------------------------------------------
    //  Streaming send APIs
    // -----------------------------------------------------------------

    @Override
    public Flux<AgentEvent> dispatchStream(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        RouteResult route = router.resolveRoute(config, message);
        return resolveGateway()
                .runStream(route.context(), message.messages(), route.outboundAddress());
    }

    /** Streaming variant of {@link #send(String)}. Returns fine-grained {@link AgentEvent}s. */
    public Flux<AgentEvent> sendStream(String text) {
        return sendStream(ChatUiRequest.of(Objects.requireNonNull(text, "text")));
    }

    /** Streaming variant of {@link #send(String, String)}. */
    public Flux<AgentEvent> sendStream(String peerId, String text) {
        Objects.requireNonNull(peerId, "peerId");
        Objects.requireNonNull(text, "text");
        return sendStream(ChatUiRequest.withPeer(peerId, text));
    }

    /** Streaming variant of {@link #send(SendOptions, String)}. */
    public Flux<AgentEvent> sendStream(SendOptions options, String text) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).name(options.userId()).textContent(text).build();
        return dispatchStreamWithOptions(options, List.of(msg));
    }

    /** Streaming variant of {@link #sendToSubagent(String, String)}. */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, String text) {
        Objects.requireNonNull(subagentId, "subagentId");
        Objects.requireNonNull(text, "text");
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(text).build();
        return resolveGateway().runSubagentStream(subagentId, List.of(msg));
    }

    /** Streaming variant of {@link #send(ChatUiRequest)}. */
    public Flux<AgentEvent> sendStream(ChatUiRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.subagentId() != null) {
            return resolveGateway().runSubagentStream(request.subagentId(), request.messages());
        }
        return dispatchStream(buildInbound(request));
    }

    // -----------------------------------------------------------------
    //  Internal
    // -----------------------------------------------------------------

    private Mono<Msg> dispatchWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().run(ctx, messages, outbound);
    }

    private Flux<AgentEvent> dispatchStreamWithOptions(SendOptions options, List<Msg> messages) {
        MsgContext ctx = buildContextFromOptions(options);
        OutboundAddress outbound = buildOutboundFromOptions(options);
        return resolveGateway().runStream(ctx, messages, outbound);
    }

    private MsgContext buildContextFromOptions(SendOptions options) {
        String room = options.effectiveSessionKey();
        String agentId = options.agentId();
        if (agentId == null) {
            agentId = config.defaultAgentId();
        }
        Map<String, String> extra = agentId != null ? Map.of("agentId", agentId) : Map.of();
        return new MsgContext(CHANNEL_ID, null, room, null, null, extra)
                .withUserId(options.userId());
    }

    private OutboundAddress buildOutboundFromOptions(SendOptions options) {
        return OutboundAddress.direct(CHANNEL_ID, CHANNEL_ID + ":" + options.effectiveSessionKey());
    }

    private Gateway resolveGateway() {
        Gateway g = gateway;
        if (g == null) {
            throw new IllegalStateException(
                    "ChatUiChannel has no gateway. Use GatewayBootstrap.chatUiChannel(),"
                            + " or construct with ChatUiChannel.create(gateway).");
        }
        return g;
    }

    private InboundMessage buildInbound(ChatUiRequest request) {
        String peerId = request.peerId();
        String agentId = request.agentId();
        if (peerId != null && !peerId.isBlank()) {
            if (agentId != null) {
                return InboundMessage.dmFor(CHANNEL_ID, peerId.trim(), agentId, request.messages());
            }
            return InboundMessage.dm(CHANNEL_ID, peerId.trim(), request.messages());
        }
        if (agentId != null) {
            return InboundMessage.dmFor(CHANNEL_ID, "__anonymous__", agentId, request.messages());
        }
        return InboundMessage.dm(CHANNEL_ID, "__anonymous__", request.messages());
    }
}
