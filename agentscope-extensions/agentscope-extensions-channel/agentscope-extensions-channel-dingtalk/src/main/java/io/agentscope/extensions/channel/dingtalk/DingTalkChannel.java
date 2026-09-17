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
import java.util.function.Supplier;
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
 *
 * <p>In a multi-tenant http-mode deployment one instance serves one tenant, and its credentials
 * are not fixed at construction: {@link #refreshCredentials(DingTalkChannelProperties)} swaps the
 * credential-derived collaborators in place, so rotating a tenant's app secret or AES key never
 * replaces the channel object and never disturbs its sessions. Stream-mode channels bind their
 * credentials at WebSocket connect time and are not refreshable — see
 * {@link DingTalkTenantChannelManager} for the multi-tenant wiring.
 */
public final class DingTalkChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "dingtalk";

    private final String channelId;
    private final ChannelConfig config;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final DingTalkChannelRegistry registry;

    /** Fixed at construction: only http-mode credentials can be refreshed in place. */
    private final boolean httpMode;

    /** Present only in {@code stream} mode; {@code null} in {@code http} mode. */
    private final DingTalkStreamClient streamClient;

    /**
     * Yields the access-token store of one credential generation, and is called once per rebuild of
     * the outbound group. A store serves a single credential, so generations must not share one:
     * reusing an instance across generations would let a request that still holds the previous
     * outbound client write a token minted from the rotated-out credentials into the slot the new
     * generation reads.
     */
    private final Supplier<AccessTokenStore> tokenStoreFactory;

    /** Serializes credential replacement; reads of {@link #credentials} are lock-free. */
    private final Object credentialLock = new Object();

    private volatile Credentials credentials;

    private volatile Gateway gateway;

    private DingTalkChannel(
            String channelId,
            ChannelConfig config,
            Credentials credentials,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            DingTalkChannelRegistry registry,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tokenStoreFactory = Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
        // The stream client authenticates its WebSocket with the construction-time credentials and
        // holds the connection for its lifetime, so stream-mode channels cannot rotate in place.
        this.httpMode = DingTalkChannelProperties.MODE_HTTP.equals(credentials.properties().mode());
        this.streamClient =
                httpMode
                        ? null
                        : new DingTalkStreamClient(
                                credentials.properties(), this::onInboundPayload);
    }

    /**
     * Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. Uses a
     * process-local {@link IdempotencyStore} and {@link InMemoryAccessTokenStore}; use the
     * overloads taking {@link InboundEventDeduplicator} and {@link AccessTokenStore} to supply
     * shared-storage implementations.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (appKey, appSecret, robotCode, mode, ...)
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
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (appKey, appSecret, robotCode, mode, ...)
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
     * <p>A channel built here serves the one credential it is given: it does not rotate in place,
     * so the supplied store is reused for every rebuild of the outbound client. Multi-tenant
     * wiring, where a tenant's credentials change at runtime, goes through
     * {@link DingTalkTenantChannelManager}, which allocates one store per credential generation.
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
        Objects.requireNonNull(tokenStore, "tokenStore");
        return fromProperties(
                channelId,
                routing,
                DingTalkChannelProperties.from(channelId, rawProperties),
                idempotency,
                () -> tokenStore);
    }

    /**
     * Factory for callers that already hold resolved {@link DingTalkChannelProperties}. The
     * {@code tokenStoreFactory} is the seam multi-tenant wiring uses: it is called once per
     * credential generation instead of being handed one store for the channel's lifetime.
     */
    static DingTalkChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            DingTalkChannelProperties properties,
            InboundEventDeduplicator idempotency,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
        return new DingTalkChannel(
                channelId,
                routing,
                newCredentials(channelId, properties, null, tokenStoreFactory),
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                DingTalkChannelRegistry.instance(),
                tokenStoreFactory);
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
        if (streamClient != null) {
            streamClient.start();
        } else {
            registry.register(this);
        }
        Credentials snapshot = credentials;
        log.info(
                "DingTalk channel '{}' started in {} mode: appKey={}, robotCode={}",
                channelId,
                snapshot.properties().mode(),
                snapshot.properties().appKey(),
                snapshot.properties().robotCode());
    }

    @Override
    public void stop() {
        if (streamClient != null) {
            streamClient.stop();
        } else {
            registry.unregister(channelId, this);
        }
        log.info("DingTalk channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        return dispatch(message, credentials);
    }

    /**
     * Dispatches {@code message} with {@code snapshot} — the credential generation the caller
     * verified and mapped it with — so the reply goes out from that same generation even when a
     * rotation lands between the caller's read and this call.
     *
     * @param message the inbound event
     * @param snapshot the credential snapshot the request entered with
     */
    Mono<Msg> dispatch(InboundMessage message, Credentials snapshot) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(snapshot, "snapshot");
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
                .flatMap(
                        reply ->
                                sendReply(snapshot, route.outboundAddress(), reply)
                                        .thenReturn(reply));
    }

    @Override
    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        credentials
                .outboundClient()
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

    /** Handles {@code payload} under the credential generation current at call time. */
    void onInboundPayload(JsonNode payload) {
        onInboundPayload(payload, credentials);
    }

    /**
     * Handles a bot-message payload delivered by either reception mode ({@link
     * DingTalkStreamClient} in stream mode, {@link DingTalkCallbackController} in http mode):
     * deduplicates by {@code msgId}, maps, applies the bot-loop guard, then dispatches.
     *
     * <p>{@code snapshot} is the credential generation the caller received the payload under. Both
     * the mapping and the reply follow from it rather than from whatever is current when they run,
     * so a rotation landing mid-request cannot attribute the callback to another app key or send
     * its reply through another tenant's client.
     *
     * @param payload the bot-message payload
     * @param snapshot the credential snapshot the request entered with
     */
    void onInboundPayload(JsonNode payload, Credentials snapshot) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(snapshot, "snapshot");
        Optional<String> msgId = DingTalkInboundMapper.extractMsgId(payload);
        if (msgId.isPresent() && !idempotency.firstSeen(channelId + "|" + msgId.get())) {
            log.debug(
                    "DingTalk dispatch: duplicate msgId={} (channelId='{}')",
                    msgId.get(),
                    channelId);
            return;
        }
        Optional<InboundMessage> inbound = snapshot.mapper().map(payload);
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
        dispatch(in, snapshot)
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' dispatch failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Credentials
    // -----------------------------------------------------------------

    /**
     * Returns the credential snapshot current at call time. The snapshot is immutable; callers
     * that need credentials for more than one step of a request must hold on to the returned
     * instance rather than reading this accessor again, so a concurrent
     * {@link #refreshCredentials(DingTalkChannelProperties)} cannot mix two generations
     * mid-request.
     */
    Credentials credentials() {
        return credentials;
    }

    /**
     * The monitor guarding {@link #credentials}. {@link DingTalkTenantChannelManager} holds it
     * across resolve-and-refresh, so the snapshot it applies always reflects the newest resolver
     * answer rather than the last thread to arrive;
     * {@link #refreshCredentials(DingTalkChannelProperties)} locks the same monitor, and reentrant
     * acquisition there is intended.
     *
     * @return the credential monitor
     */
    Object credentialLock() {
        return credentialLock;
    }

    /**
     * Replaces the credential snapshot with {@code properties}, rebuilding only the collaborators
     * the changed fields actually feed:
     *
     * <ul>
     *   <li>callback credentials ({@code appSecret}, {@code aesKey}) build the {@link
     *       DingTalkCallbackCrypto};
     *   <li>app credentials ({@code apiBase}, {@code appKey}, {@code appSecret}, {@code robotCode})
     *       build the outbound client together with its access-token cache, and the inbound mapper
     *       keyed by {@code appKey};
     *   <li>so rotating only the callback AES key keeps the cached access token, rotating the app
     *       secret starts a generation that mints its own (it also feeds the crypto HMAC), and a
     *       change that touches neither group — a differing {@code oapiBase} or {@code
     *       streamRegisterUrl} for example — leaves the channel untouched.
     * </ul>
     *
     * <p>The channel object itself never changes identity, so a tenant keeps its sessions,
     * deduplication state and bot-loop guard across a rotation, and a request in flight keeps
     * verifying against the credential generation it started with. Safe to call concurrently;
     * concurrent rotations follow last-write-wins.
     *
     * <p>Only http-mode channels support in-place refresh: a stream-mode channel authenticates its
     * WebSocket at connect time, so its credentials cannot be swapped underneath the connection —
     * rebuild the channel instead.
     *
     * @param properties the tenant's current credentials; must configure {@code mode=http}
     * @throws IllegalArgumentException when {@code properties} does not configure {@code mode=http}
     * @throws IllegalStateException when this channel itself runs in stream mode
     */
    void refreshCredentials(DingTalkChannelProperties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!DingTalkChannelProperties.MODE_HTTP.equals(properties.mode())) {
            throw new IllegalArgumentException(
                    "refreshCredentials requires mode='http' properties; a stream-mode channel"
                            + " binds its credentials at WebSocket connect time and must be"
                            + " rebuilt instead");
        }
        if (!httpMode) {
            throw new IllegalStateException(
                    "DingTalkChannel '"
                            + channelId
                            + "' runs in stream mode; rebuild the channel with the new credentials"
                            + " instead of refreshing in place");
        }
        synchronized (credentialLock) {
            Credentials current = credentials;
            if (callbackCredentialsEqual(current.properties(), properties)
                    && appCredentialsEqual(current.properties(), properties)) {
                return;
            }
            credentials = newCredentials(channelId, properties, current, tokenStoreFactory);
        }
    }

    /**
     * Builds the snapshot for {@code properties}, reusing {@code previous}'s collaborators whose
     * inputs are unchanged.
     */
    private static Credentials newCredentials(
            String channelId,
            DingTalkChannelProperties properties,
            Credentials previous,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        boolean streamMode = DingTalkChannelProperties.MODE_STREAM.equals(properties.mode());
        boolean callbackUnchanged =
                previous != null && callbackCredentialsEqual(previous.properties(), properties);
        boolean appUnchanged =
                previous != null && appCredentialsEqual(previous.properties(), properties);
        DingTalkCallbackCrypto crypto =
                streamMode
                        ? null
                        : callbackUnchanged
                                ? previous.crypto()
                                : new DingTalkCallbackCrypto(
                                        properties.appSecret(), properties.aesKey());
        DingTalkOutboundClient outbound =
                appUnchanged
                        ? previous.outboundClient()
                        : new DingTalkOutboundClient(
                                properties.apiBase(),
                                new DingTalkAccessTokenProvider(
                                        properties.apiBase(),
                                        properties.appKey(),
                                        properties.appSecret(),
                                        tokenStoreFactory.get()),
                                properties.robotCode());
        DingTalkInboundMapper mapper =
                appUnchanged
                        ? previous.mapper()
                        : new DingTalkInboundMapper(channelId, properties.appKey());
        return new Credentials(properties, crypto, outbound, mapper);
    }

    /** Fields the callback path consumes: request-signature verification and decryption. */
    private static boolean callbackCredentialsEqual(
            DingTalkChannelProperties a, DingTalkChannelProperties b) {
        return a.appSecret().equals(b.appSecret()) && Objects.equals(a.aesKey(), b.aesKey());
    }

    /** Fields the outbound path and the app-key-keyed inbound mapper consume. */
    private static boolean appCredentialsEqual(
            DingTalkChannelProperties a, DingTalkChannelProperties b) {
        return a.apiBase().equals(b.apiBase())
                && a.appKey().equals(b.appKey())
                && a.appSecret().equals(b.appSecret())
                && a.robotCode().equals(b.robotCode());
    }

    // -----------------------------------------------------------------
    //  Internal accessors / helpers
    // -----------------------------------------------------------------

    InboundEventDeduplicator idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    private Mono<Void> sendReply(Credentials snapshot, OutboundAddress address, Msg reply) {
        if (reply == null) {
            return Mono.empty();
        }
        return snapshot.outboundClient()
                .send(address, List.of(reply))
                .doOnError(
                        err ->
                                log.warn(
                                        "DingTalk channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }

    /**
     * Immutable bundle of the collaborators derived from one credential generation.
     *
     * @param properties the credentials this snapshot was built from
     * @param crypto callback request verification and decryption; {@code null} in stream mode,
     *     where callbacks do not reach the channel
     * @param outboundClient outbound sends and their access-token cache
     * @param mapper inbound payload mapping, keyed by the tenant's app key
     */
    record Credentials(
            DingTalkChannelProperties properties,
            DingTalkCallbackCrypto crypto,
            DingTalkOutboundClient outboundClient,
            DingTalkInboundMapper mapper) {}
}
