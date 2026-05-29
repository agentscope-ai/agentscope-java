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
package io.agentscope.claw2.runtime.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.claw2.runtime.channel.Channel;
import io.agentscope.claw2.runtime.channel.ChannelConfig;
import io.agentscope.claw2.runtime.channel.ChannelRouter;
import io.agentscope.claw2.runtime.channel.InboundMessage;
import io.agentscope.claw2.runtime.channel.OutboundAddress;
import io.agentscope.claw2.runtime.channel.RouteResult;
import io.agentscope.claw2.runtime.channel.common.BotLoopGuard;
import io.agentscope.claw2.runtime.channel.common.IdempotencyStore;
import io.agentscope.claw2.runtime.gateway.Gateway;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DingTalk (钉钉) channel adapter using the Stream protocol (persistent WebSocket).
 *
 * <p>Inbound: {@link DingTalkStreamClient} dispatches each bot message payload here, where it is
 * mapped through {@link DingTalkInboundMapper}, deduplicated by {@code msgId}, throttled by the
 * bot-loop guard, then routed via {@link ChannelRouter} and executed through the {@link Gateway}.
 *
 * <p>Outbound: {@link DingTalkOutboundClient} sends replies through the OpenAPI batchSend
 * endpoints.
 */
public final class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.claw2.runtime.config.ChannelTypeRegistry}. */
    public static final String TYPE = "dingtalk";

    private final String channelId;
    private final ChannelConfig config;
    private final DingTalkChannelProperties properties;
    private final DingTalkAccessTokenProvider tokenProvider;
    private final DingTalkOutboundClient outboundClient;
    private final DingTalkInboundMapper mapper;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final DingTalkStreamClient streamClient;

    private volatile Gateway gateway;

    private DingTalkChannel(
            String channelId,
            ChannelConfig config,
            DingTalkChannelProperties properties,
            DingTalkAccessTokenProvider tokenProvider,
            DingTalkOutboundClient outboundClient,
            DingTalkInboundMapper mapper,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.streamClient = new DingTalkStreamClient(properties, this::onInboundPayload);
    }

    /** Factory used by {@link io.agentscope.claw2.runtime.config.ChannelTypeRegistry}. */
    public static DingTalkChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        DingTalkChannelProperties props = DingTalkChannelProperties.from(channelId, rawProperties);
        DingTalkAccessTokenProvider tokenProvider =
                new DingTalkAccessTokenProvider(props.apiBase(), props.appKey(), props.appSecret());
        DingTalkOutboundClient outbound =
                new DingTalkOutboundClient(props.apiBase(), tokenProvider, props.robotCode());
        DingTalkInboundMapper mapper = new DingTalkInboundMapper(channelId, props.appKey());
        return new DingTalkChannel(
                channelId,
                routing,
                props,
                tokenProvider,
                outbound,
                mapper,
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()));
    }

    // -----------------------------------------------------------------
    //  Channel lifecycle
    // -----------------------------------------------------------------

    @Override
    public String channelId() {
        return channelId;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        if (this.gateway == null) {
            this.gateway = Objects.requireNonNull(gateway, "gateway");
        }
    }

    @Override
    public void start() {
        streamClient.start();
        log.info(
                "DingTalk channel '{}' started: appKey={}, robotCode={}",
                channelId,
                properties.appKey(),
                properties.robotCode());
    }

    @Override
    public void stop() {
        streamClient.stop();
        log.info("DingTalk channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException(
                            "DingTalkChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(route.context(), message.messages(), route.outboundAddress())
                .flatMap(reply -> sendReply(route.outboundAddress(), reply).thenReturn(reply));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        outboundClient
                .send(address, messages)
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Stream callback
    // -----------------------------------------------------------------

    private void onInboundPayload(JsonNode payload) {
        Optional<String> msgId = DingTalkInboundMapper.extractMsgId(payload);
        if (msgId.isPresent() && !idempotency.firstSeen(channelId + "|" + msgId.get())) {
            log.debug(
                    "DingTalk dispatch: duplicate msgId={} (channelId='{}')",
                    msgId.get(),
                    channelId);
            return;
        }
        Optional<InboundMessage> inbound = mapper.map(payload);
        if (inbound.isEmpty()) {
            return;
        }
        InboundMessage in = inbound.get();
        if (!botLoopGuard.allow(in.peer().key())) {
            log.warn(
                    "DingTalk dispatch: bot-loop guard tripped for peer='{}' (channelId='{}')",
                    in.peer().key(),
                    channelId);
            return;
        }
        dispatch(in)
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' dispatch failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Internal accessors / helpers
    // -----------------------------------------------------------------

    DingTalkInboundMapper mapper() {
        return mapper;
    }

    IdempotencyStore idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    DingTalkAccessTokenProvider tokenProvider() {
        return tokenProvider;
    }

    DingTalkChannelProperties properties() {
        return properties;
    }

    private Mono<Void> sendReply(OutboundAddress address, Msg reply) {
        if (reply == null) {
            return Mono.empty();
        }
        return outboundClient
                .send(address, List.of(reply))
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
