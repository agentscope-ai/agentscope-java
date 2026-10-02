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
package io.agentscope.extensions.channel.wecom;

import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * WeCom (企业微信) channel adapter.
 *
 * <p>Inbound: relies on Spring-managed {@link WeComCallbackController} that receives the URL
 * verification handshake and encrypted message callbacks, decrypts via {@link WeComCrypto},
 * deduplicates by {@code MsgId}, runs the bot-loop guard, and dispatches into this channel.
 *
 * <p>Outbound: uses {@link WeComOutboundClient} to call {@code /cgi-bin/message/send} for DMs and
 * {@code /cgi-bin/appchat/send} for groups, authenticating with an {@code access_token} from
 * {@link WeComAccessTokenProvider}.
 */
public final class WeComChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeComChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "wecom";

    private final String channelId;
    private final ChannelConfig config;
    private final WeComChannelProperties properties;
    private final WeComCrypto crypto;
    private final WeComAccessTokenProvider tokenProvider;
    private final WeComOutboundClient outboundClient;
    private final WeComInboundMapper mapper;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final WeComChannelRegistry registry;

    private volatile Gateway gateway;

    private WeComChannel(
            String channelId,
            ChannelConfig config,
            WeComChannelProperties properties,
            WeComCrypto crypto,
            WeComAccessTokenProvider tokenProvider,
            WeComOutboundClient outboundClient,
            WeComInboundMapper mapper,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            WeComChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (corpId, agentId, secret, token,
     *     encodingAesKey, ...)
     */
    public static WeComChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WeComChannelProperties props = WeComChannelProperties.from(channelId, rawProperties);
        WeComCrypto crypto = new WeComCrypto(props.token(), props.encodingAesKey(), props.corpId());
        WeComAccessTokenProvider tokenProvider =
                new WeComAccessTokenProvider(props.apiBase(), props.corpId(), props.secret());
        WeComOutboundClient outbound =
                new WeComOutboundClient(props.apiBase(), tokenProvider, props.agentId());
        WeComInboundMapper mapper = new WeComInboundMapper(channelId, props.corpId());
        return new WeComChannel(
                channelId,
                routing,
                props,
                crypto,
                tokenProvider,
                outbound,
                mapper,
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                WeComChannelRegistry.instance());
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
        registry.register(this);
        log.info(
                "WeCom channel '{}' started: corpId={}, agentId={}, callbackPath={}",
                channelId,
                properties.corpId(),
                properties.agentId(),
                properties.callbackPath());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("WeCom channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WeComChannel '" + channelId + "' has no gateway"));
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
                                        "WeCom channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Internal accessors for WeComCallbackController
    // -----------------------------------------------------------------

    WeComCrypto crypto() {
        return crypto;
    }

    WeComInboundMapper mapper() {
        return mapper;
    }

    IdempotencyStore idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    WeComChannelProperties properties() {
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
                                        "WeCom channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
