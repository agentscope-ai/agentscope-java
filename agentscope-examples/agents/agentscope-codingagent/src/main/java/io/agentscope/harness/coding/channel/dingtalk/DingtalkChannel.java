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
package io.agentscope.harness.coding.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.coding.channel.Channel;
import io.agentscope.harness.coding.channel.ChannelConfig;
import io.agentscope.harness.coding.channel.ChannelRouter;
import io.agentscope.harness.coding.channel.InboundMessage;
import io.agentscope.harness.coding.channel.OutboundAddress;
import io.agentscope.harness.coding.channel.RouteResult;
import io.agentscope.harness.coding.channel.common.BotLoopGuard;
import io.agentscope.harness.coding.channel.common.IdempotencyStore;
import io.agentscope.harness.coding.gateway.Gateway;
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
 * <p>Inbound: {@link DingtalkStreamClient} dispatches each bot message payload here, where it is
 * mapped through {@link DingtalkInboundMapper}, deduplicated by {@code msgId}, throttled by the
 * bot-loop guard, then routed via {@link ChannelRouter} and executed through the {@link Gateway}.
 *
 * <p>Outbound: {@link DingtalkOutboundClient} sends replies through the OpenAPI batchSend
 * endpoints.
 */
public final class DingtalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingtalkChannel.class);

    /** {@code type} value used in {@code agentscope.json} channel configuration. */
    public static final String TYPE = "dingtalk";

    private final String channelId;
    private final ChannelConfig config;
    private final DingtalkChannelProperties properties;
    private final DingtalkAccessTokenProvider tokenProvider;
    private final DingtalkOutboundClient outboundClient;
    private final DingtalkInboundMapper mapper;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final DingtalkStreamClient streamClient;

    private volatile Gateway gateway;

    private DingtalkChannel(
            String channelId,
            ChannelConfig config,
            DingtalkChannelProperties properties,
            DingtalkAccessTokenProvider tokenProvider,
            DingtalkOutboundClient outboundClient,
            DingtalkInboundMapper mapper,
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
        this.streamClient = new DingtalkStreamClient(properties, this::onInboundPayload);
    }

    /** Factory used by {@code CodingBootstrap.resolveChannels} to build the adapter from JSON. */
    public static DingtalkChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        DingtalkChannelProperties props = DingtalkChannelProperties.from(channelId, rawProperties);
        DingtalkAccessTokenProvider tokenProvider =
                new DingtalkAccessTokenProvider(props.apiBase(), props.appKey(), props.appSecret());
        DingtalkOutboundClient outbound =
                new DingtalkOutboundClient(props.apiBase(), tokenProvider, props.robotCode());
        DingtalkInboundMapper mapper = new DingtalkInboundMapper(channelId, props.appKey());
        return new DingtalkChannel(
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
                            "DingtalkChannel '" + channelId + "' has no gateway"));
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
        Optional<String> msgId = DingtalkInboundMapper.extractMsgId(payload);
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
