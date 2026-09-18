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
import java.util.function.Function;
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
 *
 * <p>In a multi-tenant deployment one instance serves one tenant, and its credentials are not
 * fixed at construction: {@link #refreshCredentials(WeComChannelProperties)} swaps the
 * credential-derived collaborators in place, so rotating a tenant's callback token or secret never
 * replaces the channel object and never disturbs its sessions. See
 * {@link WeComTenantChannelManager} for the wiring.
 */
public final class WeComChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(WeComChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "wecom";

    private final String channelId;
    private final ChannelConfig config;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final WeComChannelRegistry registry;

    /**
     * Yields the access-token store of one credential generation, and is called once per rebuild of
     * the outbound group with that generation's properties. The store contract binds one instance
     * to one credential: reusing an instance across generations would let a request that still
     * holds the previous outbound client write a token minted from the rotated-out credentials into
     * the slot the new generation reads, so the new outbound client would send with the old
     * generation's token. The properties let implementations scope shared storage (for example a
     * Redis key hashed from the credential fields): instances on different processes holding the
     * same generation share one slot, while a rotation moves to a fresh one.
     */
    private final Function<WeComChannelProperties, AccessTokenStore> tokenStoreFactory;

    /** Serializes credential replacement; reads of {@link #credentials} are lock-free. */
    private final Object credentialLock = new Object();

    private volatile Credentials credentials;

    private volatile Gateway gateway;

    private WeComChannel(
            String channelId,
            ChannelConfig config,
            Credentials credentials,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            WeComChannelRegistry registry,
            Function<WeComChannelProperties, AccessTokenStore> tokenStoreFactory) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.tokenStoreFactory = Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
    }

    /**
     * Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. Uses a
     * process-local {@link IdempotencyStore} and {@link InMemoryAccessTokenStore}; use the
     * overloads taking {@link InboundEventDeduplicator} and {@link AccessTokenStore} to supply
     * shared-storage implementations.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (corpId, agentId, secret, token,
     *     encodingAesKey, ...)
     */
    public static WeComChannel fromProperties(
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
     * @param rawProperties provider-specific properties (corpId, agentId, secret, token,
     *     encodingAesKey, ...)
     * @param idempotency deduplicator for inbound events; must be thread-safe
     */
    public static WeComChannel fromProperties(
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
     * @param rawProperties provider-specific properties (corpId, agentId, secret, token,
     *     encodingAesKey, ...)
     * @param idempotency deduplicator for inbound events; must be thread-safe
     * @param tokenStore cache for this channel's access token — one instance per credential, not
     *     to be shared across channels with different credentials; must be thread-safe. Channels
     *     built here do not rotate credentials in place, so the instance serves this channel's
     *     single credential generation; the in-place refresh path is the tenant manager's, and it
     *     allocates a fresh store per generation.
     */
    public static WeComChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        Objects.requireNonNull(tokenStore, "tokenStore");
        return fromProperties(
                channelId,
                routing,
                WeComChannelProperties.from(channelId, rawProperties),
                idempotency,
                properties -> tokenStore);
    }

    /**
     * Factory for callers that already hold resolved {@link WeComChannelProperties}.
     *
     * @param tokenStoreFactory yields the access-token store for each credential generation, called
     *     with that generation's properties; each call must return a store serving only that
     *     generation's credential
     */
    static WeComChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            WeComChannelProperties properties,
            InboundEventDeduplicator idempotency,
            Function<WeComChannelProperties, AccessTokenStore> tokenStoreFactory) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
        return new WeComChannel(
                channelId,
                routing,
                newCredentials(channelId, properties, null, tokenStoreFactory),
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                WeComChannelRegistry.instance(),
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
        registry.register(this);
        Credentials snapshot = credentials;
        log.info(
                "WeCom channel '{}' started: corpId={}, agentId={}, callbackPath={}",
                channelId,
                snapshot.properties().corpId(),
                snapshot.properties().agentId(),
                snapshot.properties().callbackPath());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("WeCom channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        return dispatch(message, credentials);
    }

    /**
     * Dispatches {@code message}, delivering the reply through {@code snapshot}'s outbound client.
     * The callback intake passes the snapshot it captured at request entry, so the whole request —
     * verification, decryption, mapping and reply — stays on one credential generation even when a
     * rotation lands while it is in flight; callers without a request-scoped snapshot use
     * {@link #dispatch(InboundMessage)}.
     *
     * @param message the mapped inbound message
     * @param snapshot the credential snapshot the request was handled with
     */
    Mono<Msg> dispatch(InboundMessage message, Credentials snapshot) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(snapshot, "snapshot");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("WeComChannel '" + channelId + "' has no gateway"));
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
                                        "WeCom channel '{}' deliver failed: {}",
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
     * {@link #refreshCredentials(WeComChannelProperties)} cannot mix two generations mid-request.
     */
    Credentials credentials() {
        return credentials;
    }

    /**
     * The monitor guarding {@link #credentials}. {@link WeComTenantChannelManager} holds it across
     * resolve-and-refresh, so the snapshot it applies always reflects the newest resolver answer
     * rather than the last thread to arrive; {@link #refreshCredentials(WeComChannelProperties)}
     * locks the same monitor, and reentrant acquisition there is intended.
     */
    Object credentialLock() {
        return credentialLock;
    }

    /**
     * Replaces the credential snapshot with {@code properties}, rebuilding only the collaborators
     * the changed fields actually feed:
     *
     * <ul>
     *   <li>callback credentials ({@code token}, {@code encodingAesKey}, {@code corpId}) build the
     *       {@link WeComCrypto} and the inbound mapper;
     *   <li>app credentials ({@code corpId}, {@code secret}, {@code agentId}, {@code apiBase})
     *       build the outbound client together with its access-token cache;
     *   <li>so rotating only the callback token keeps the cached access token; rotating the app
     *       credentials discards it by giving the rebuilt outbound client a store of its own, which
     *       starts empty and is unreachable from the previous generation; and a change that touches
     *       neither group — a differing {@code callbackPath} for example — leaves the channel
     *       untouched.
     * </ul>
     *
     * <p>The channel object itself never changes identity, so a tenant keeps its sessions,
     * deduplication state and bot-loop guard across a rotation, and a request in flight keeps
     * verifying against the credential generation it started with. Safe to call concurrently;
     * concurrent rotations follow last-write-wins.
     *
     * @param properties the tenant's current credentials
     */
    void refreshCredentials(WeComChannelProperties properties) {
        Objects.requireNonNull(properties, "properties");
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
            WeComChannelProperties properties,
            Credentials previous,
            Function<WeComChannelProperties, AccessTokenStore> tokenStoreFactory) {
        boolean callbackUnchanged =
                previous != null && callbackCredentialsEqual(previous.properties(), properties);
        boolean appUnchanged =
                previous != null && appCredentialsEqual(previous.properties(), properties);
        WeComCrypto crypto =
                callbackUnchanged
                        ? previous.crypto()
                        : new WeComCrypto(
                                properties.token(),
                                properties.encodingAesKey(),
                                properties.corpId());
        WeComInboundMapper mapper =
                callbackUnchanged
                        ? previous.mapper()
                        : new WeComInboundMapper(channelId, properties.corpId());
        // A rebuilt outbound group takes a store of its own: the previous generation's store is
        // still reachable from requests in flight with the old outbound client, and a token they
        // mint from the rotated-out credentials must not be readable here.
        WeComOutboundClient outbound =
                appUnchanged
                        ? previous.outboundClient()
                        : new WeComOutboundClient(
                                properties.apiBase(),
                                new WeComAccessTokenProvider(
                                        properties.apiBase(),
                                        properties.corpId(),
                                        properties.secret(),
                                        tokenStoreFactory.apply(properties)),
                                properties.agentId());
        return new Credentials(properties, crypto, outbound, mapper);
    }

    /** Fields the callback path consumes: signature verification and decryption. */
    private static boolean callbackCredentialsEqual(
            WeComChannelProperties a, WeComChannelProperties b) {
        return a.token().equals(b.token())
                && a.encodingAesKey().equals(b.encodingAesKey())
                && a.corpId().equals(b.corpId());
    }

    /** Fields the outbound path consumes: token minting and message addressing. */
    private static boolean appCredentialsEqual(WeComChannelProperties a, WeComChannelProperties b) {
        return a.apiBase().equals(b.apiBase())
                && a.corpId().equals(b.corpId())
                && a.secret().equals(b.secret())
                && a.agentId() == b.agentId();
    }

    // -----------------------------------------------------------------
    //  Internal accessors for WeComCallbackController
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
                                        "WeCom channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }

    /**
     * Immutable bundle of the collaborators derived from one credential generation.
     *
     * @param properties the credentials this snapshot was built from
     * @param crypto callback signature verification and decryption
     * @param outboundClient outbound sends and their access-token cache
     * @param mapper inbound payload mapping
     */
    record Credentials(
            WeComChannelProperties properties,
            WeComCrypto crypto,
            WeComOutboundClient outboundClient,
            WeComInboundMapper mapper) {}
}
