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
package io.agentscope.extensions.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * DingTalk (钉钉) channel adapter.
 *
 * <p>Reception mode is selected by {@link DingTalkChannelProperties#mode()}: in {@code stream}
 * mode (default), {@link DingTalkStreamClient} holds a persistent WebSocket and dispatches each
 * bot message payload here; in {@code http} mode, {@link DingTalkCallbackController} receives
 * signed HTTP callbacks and hands the verified payload to the same intake. Either way the payload
 * is mapped through {@link DingTalkInboundMapper}, deduplicated by {@code msgId}, throttled by the
 * bot-loop guard, then routed via {@link ChannelRouter} and executed through the {@link Gateway}.
 *
 * <p>Outbound: {@link DingTalkOutboundClient} sends replies through the OpenAPI batchSend
 * endpoints.
 */
public final class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "dingtalk";

    private final String channelId;
    private final ChannelConfig config;
    private final DingTalkChannelProperties properties;
    private final DingTalkAccessTokenProvider tokenProvider;
    private final DingTalkOutboundClient outboundClient;
    private final DingTalkInboundMapper mapper;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /** Present only in {@code stream} mode; {@code null} in {@code http} mode. */
    private final DingTalkStreamClient streamClient;

    /** Present only in {@code http} mode; {@code null} in {@code stream} mode. */
    private final DingTalkCallbackCrypto crypto;

    private final DingTalkChannelRegistry registry;

    private volatile Gateway gateway;

    private DingTalkChannel(
            String channelId,
            ChannelConfig config,
            DingTalkChannelProperties properties,
            DingTalkAccessTokenProvider tokenProvider,
            DingTalkOutboundClient outboundClient,
            DingTalkInboundMapper mapper,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            DingTalkChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (DingTalkChannelProperties.MODE_HTTP.equals(properties.mode())) {
            this.streamClient = null;
            this.crypto = new DingTalkCallbackCrypto(properties.appSecret(), properties.aesKey());
        } else {
            this.streamClient = new DingTalkStreamClient(properties, this::onInboundPayload);
            this.crypto = null;
        }
    }

    /**
     * Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. Uses a
     * process-local {@link IdempotencyStore} and {@link InMemoryAccessTokenStore}; use the
     * overloads taking {@link InboundEventDeduplicator} and {@link AccessTokenStore} to supply
     * shared-storage implementations.
     */
    public static DingTalkChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        return fromProperties(
                channelId,
                routing,
                rawProperties,
                new IdempotencyStore(),
                new InMemoryAccessTokenStore());
    }

    /**
     * Factory variant that lets the application supply the {@link InboundEventDeduplicator} used
     * to drop platform redeliveries — for example a shared-storage implementation so duplicates
     * are recognized across instances. The process-local {@link IdempotencyStore} is used
     * otherwise.
     *
     * @param idempotency deduplicator for inbound events; must be thread-safe
     */
    public static DingTalkChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency) {
        return fromProperties(
                channelId, routing, rawProperties, idempotency, new InMemoryAccessTokenStore());
    }

    /**
     * Factory variant that additionally lets the application supply the {@link AccessTokenStore}
     * caching the outbound access token — for example a shared-storage implementation so one
     * instance's refresh or invalidation serves the whole deployment. The process-local {@link
     * InMemoryAccessTokenStore} is used otherwise.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (appKey, appSecret, robotCode, mode,
     *     aesKey, ...)
     * @param idempotency deduplicator for inbound events; must be thread-safe
     * @param tokenStore cache for this channel's access token — one instance per credential, not
     *     to be shared across channels with different credentials; must be thread-safe
     */
    public static DingTalkChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        DingTalkChannelProperties props = DingTalkChannelProperties.from(channelId, rawProperties);
        DingTalkAccessTokenProvider tokenProvider =
                new DingTalkAccessTokenProvider(
                        props.apiBase(), props.appKey(), props.appSecret(), tokenStore);
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
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                DingTalkChannelRegistry.instance());
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
        if (crypto != null) {
            registry.register(this);
        } else {
            streamClient.start();
        }
        log.info(
                "DingTalk channel '{}' started in {} mode: appKey={}, robotCode={}",
                channelId,
                properties.mode(),
                properties.appKey(),
                properties.robotCode());
    }

    @Override
    public void stop() {
        if (crypto != null) {
            registry.unregister(channelId, this);
        } else {
            streamClient.stop();
        }
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
        return g.run(
                        route.context(),
                        message.messages(),
                        route.outboundAddress(),
                        message.runtimeContext(),
                        message)
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
    //  Inbound intake (shared by both reception modes)
    // -----------------------------------------------------------------

    /**
     * Handles a bot-message payload delivered by either reception mode ({@link
     * DingTalkStreamClient} in stream mode, {@link DingTalkCallbackController} in http mode):
     * deduplicates by {@code msgId}, maps, applies the bot-loop guard, then dispatches.
     */
    void onInboundPayload(JsonNode payload) {
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

    /** Callback verification/decryption helper; {@code null} unless running in http mode. */
    DingTalkCallbackCrypto crypto() {
        return crypto;
    }

    InboundEventDeduplicator idempotency() {
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
