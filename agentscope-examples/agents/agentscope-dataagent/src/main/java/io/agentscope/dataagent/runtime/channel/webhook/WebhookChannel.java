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
package io.agentscope.dataagent.runtime.channel.webhook;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Generic webhook channel — the "side tool" form: an HTTP-in / HTTP-out adapter that lets external
 * systems (IM bots, ticketing, CI) invoke a DataAgent without opening the primary Chat UI.
 *
 * <p>Inbound: {@link WebhookCallbackController} handles {@code POST /api/webhook/{channelId}
 * /inbound}, verifies the HMAC signature, then calls {@link #ingest(WebhookInboundRequest)} which
 * deduplicates, applies the bot-loop guard, routes through {@link ChannelRouter}, and dispatches
 * to the {@link Gateway}.
 *
 * <p>Outbound: two modes per request — {@code callback} posts the reply to the caller's
 * {@code callbackUrl} via {@link WebhookOutboundClient}, while {@code poll} parks the reply in
 * {@link #parkedReplies} for the long-poll endpoint.
 */
public final class WebhookChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link
     * io.agentscope.dataagent.runtime.config.ChannelTypeRegistry}.
     */
    public static final String TYPE = "webhook";

    private final String channelId;
    private final ChannelConfig config;
    private final WebhookChannelProperties properties;
    private final WebhookInboundMapper mapper;
    private final WebhookOutboundClient outboundClient;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;

    /** Per-inboundId park slot for {@code replyMode=poll}. Bounded by FIFO eviction. */
    private final ConcurrentHashMap<String, Sinks.One<String>> parkedReplies =
            new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> parkOrder = new ConcurrentLinkedDeque<>();

    private volatile Gateway gateway;

    private WebhookChannel(
            String channelId,
            ChannelConfig config,
            WebhookChannelProperties properties,
            WebhookInboundMapper mapper,
            WebhookOutboundClient outboundClient,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
    }

    /** Factory used by {@link io.agentscope.dataagent.runtime.config.ChannelTypeRegistry}. */
    public static WebhookChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        WebhookChannelProperties props = WebhookChannelProperties.from(channelId, rawProperties);
        return new WebhookChannel(
                channelId,
                routing,
                props,
                new WebhookInboundMapper(channelId),
                new WebhookOutboundClient(channelId, props.sharedSecret()),
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
        log.info(
                "Webhook channel '{}' started: requiresSignature={}, allowedIps={}",
                channelId,
                properties.requiresSignature(),
                properties.allowedIps());
    }

    @Override
    public void stop() {
        parkedReplies.forEach((id, sink) -> sink.tryEmitEmpty());
        parkedReplies.clear();
        parkOrder.clear();
        log.info("Webhook channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WebhookChannel '" + channelId + "' has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, message);
        return g.run(route.context(), message.messages(), route.outboundAddress());
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        // Proactive subagent announces — there is no persistent peer connection for a webhook,
        // so we drop these on the floor. Callers that need announce delivery should use the
        // chatui channel or a real IM channel.
        if (messages == null || messages.isEmpty()) return;
        log.debug(
                "Webhook channel '{}' dropping {} proactive message(s) (no persistent peer)",
                channelId,
                messages.size());
    }

    // -----------------------------------------------------------------
    //  Webhook entry points (called by WebhookCallbackController)
    // -----------------------------------------------------------------

    WebhookChannelProperties properties() {
        return properties;
    }

    /**
     * Ingest a freshly-authenticated inbound request. Returns the response payload to send back
     * to the caller (containing the assigned {@code inboundId} and — when in poll mode — the
     * actual reply once available).
     */
    public Mono<WebhookDispatchResult> ingest(WebhookInboundRequest req) {
        Objects.requireNonNull(req, "req");
        String inboundId =
                (req.inboundId() != null && !req.inboundId().isBlank())
                        ? req.inboundId().strip()
                        : UUID.randomUUID().toString();
        if (!idempotency.firstSeen(channelId + "|" + inboundId)) {
            log.debug(
                    "Webhook channel '{}' dispatch: duplicate inboundId={}", channelId, inboundId);
            return Mono.just(
                    new WebhookDispatchResult(
                            inboundId, "duplicate", null, req.effectiveReplyMode()));
        }
        return mapper.map(req)
                .map(
                        inbound -> {
                            if (!botLoopGuard.allow(inbound.peer().key())) {
                                log.warn(
                                        "Webhook channel '{}' bot-loop guard tripped for peer='{}'",
                                        channelId,
                                        inbound.peer().key());
                                return Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "bot-loop-blocked",
                                                null,
                                                req.effectiveReplyMode()));
                            }
                            return dispatchAndDeliver(req, inbound, inboundId);
                        })
                .orElseGet(
                        () ->
                                Mono.just(
                                        new WebhookDispatchResult(
                                                inboundId,
                                                "invalid-payload",
                                                null,
                                                req.effectiveReplyMode())));
    }

    private Mono<WebhookDispatchResult> dispatchAndDeliver(
            WebhookInboundRequest req, InboundMessage inbound, String inboundId) {
        String mode = req.effectiveReplyMode();
        Mono<Msg> dispatch = dispatch(inbound);

        if (WebhookInboundRequest.REPLY_MODE_CALLBACK.equals(mode)) {
            String cbUrl = req.callbackUrl();
            if (cbUrl == null || cbUrl.isBlank()) {
                return Mono.just(
                        new WebhookDispatchResult(inboundId, "callback-url-required", null, mode));
            }
            dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                    .flatMap(text -> outboundClient.deliver(cbUrl, inboundId, text))
                    .doOnError(
                            err ->
                                    log.warn(
                                            "Webhook channel '{}' dispatch failed (callback): {}",
                                            channelId,
                                            err.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
            return Mono.just(new WebhookDispatchResult(inboundId, "accepted", null, mode));
        }

        // Poll mode: park the reply sink, await dispatch in the background.
        Sinks.One<String> sink = Sinks.one();
        parkReply(inboundId, sink);
        dispatch.map(reply -> WebhookOutboundClient.renderReplyText(List.of(reply)))
                .doOnSuccess(text -> sink.tryEmitValue(text == null ? "" : text))
                .doOnError(
                        err -> {
                            log.warn(
                                    "Webhook channel '{}' dispatch failed (poll): {}",
                                    channelId,
                                    err.getMessage());
                            sink.tryEmitValue("");
                        })
                .onErrorResume(e -> Mono.empty())
                .subscribe();
        return Mono.just(new WebhookDispatchResult(inboundId, "parked", null, mode));
    }

    /**
     * Long-poll fetch of a previously parked reply. Returns empty when the poll timeout elapses
     * without a reply landing; callers should re-poll.
     */
    public Mono<String> awaitReply(String inboundId) {
        if (inboundId == null) return Mono.empty();
        Sinks.One<String> sink = parkedReplies.get(inboundId);
        if (sink == null) return Mono.empty();
        return sink.asMono()
                .timeout(
                        java.time.Duration.ofMillis(properties.longPollTimeoutMillis()),
                        Mono.empty())
                .doFinally(sig -> parkedReplies.remove(inboundId));
    }

    private void parkReply(String inboundId, Sinks.One<String> sink) {
        parkedReplies.put(inboundId, sink);
        parkOrder.addLast(inboundId);
        while (parkedReplies.size() > properties.outboundParkCapacity()) {
            String evict = parkOrder.pollFirst();
            if (evict == null) break;
            Sinks.One<String> dropped = parkedReplies.remove(evict);
            if (dropped != null) dropped.tryEmitEmpty();
        }
    }

    /** Response envelope returned from {@link #ingest}. */
    public record WebhookDispatchResult(
            String inboundId, String status, String reply, String replyMode) {}
}
