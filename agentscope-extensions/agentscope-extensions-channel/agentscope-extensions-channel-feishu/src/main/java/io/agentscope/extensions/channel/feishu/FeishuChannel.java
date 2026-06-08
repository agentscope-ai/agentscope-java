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
package io.agentscope.extensions.channel.feishu;

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
 * Feishu (飞书 / Lark) channel adapter.
 *
 * <p>Inbound: relies on Spring-managed {@link FeishuCallbackController} which receives the URL
 * verification handshake and event-subscription callbacks, optionally decrypts via
 * {@link FeishuCrypto}, deduplicates by {@code header.event_id}, runs the bot-loop guard, and
 * dispatches into this channel.
 *
 * <p>Outbound: uses {@link FeishuOutboundClient} to call {@code /open-apis/im/v1/messages},
 * authenticating with a {@code tenant_access_token} from {@link FeishuAccessTokenProvider}.
 */
public final class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "feishu";

    private final String channelId;
    private final ChannelConfig config;
    private final FeishuChannelProperties properties;
    private final FeishuCrypto crypto;
    private final FeishuAccessTokenProvider tokenProvider;
    private final FeishuOutboundClient outboundClient;
    private final FeishuInboundMapper mapper;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final FeishuChannelRegistry registry;

    private volatile Gateway gateway;

    private FeishuChannel(
            String channelId,
            ChannelConfig config,
            FeishuChannelProperties properties,
            FeishuCrypto crypto,
            FeishuAccessTokenProvider tokenProvider,
            FeishuOutboundClient outboundClient,
            FeishuInboundMapper mapper,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            FeishuChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.crypto = crypto;
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static FeishuChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        FeishuChannelProperties props = FeishuChannelProperties.from(channelId, rawProperties);
        FeishuCrypto crypto = props.isEncrypted() ? new FeishuCrypto(props.encryptKey()) : null;
        FeishuAccessTokenProvider tokenProvider =
                new FeishuAccessTokenProvider(props.apiBase(), props.appId(), props.appSecret());
        FeishuOutboundClient outbound = new FeishuOutboundClient(props.apiBase(), tokenProvider);
        FeishuInboundMapper mapper = new FeishuInboundMapper(channelId);
        return new FeishuChannel(
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
                FeishuChannelRegistry.instance());
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
                "Feishu channel '{}' started: appId={}, callbackPath={}, encrypted={}",
                channelId,
                properties.appId(),
                properties.callbackPath(),
                properties.isEncrypted());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("Feishu channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("FeishuChannel '" + channelId + "' has no gateway"));
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
                                        "Feishu channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Internal accessors for FeishuCallbackController
    // -----------------------------------------------------------------

    FeishuCrypto crypto() {
        return crypto;
    }

    FeishuInboundMapper mapper() {
        return mapper;
    }

    IdempotencyStore idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    FeishuChannelProperties properties() {
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
                                        "Feishu channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
