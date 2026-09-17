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
import java.util.function.Supplier;
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
 *
 * <p>In a multi-tenant deployment one instance serves one tenant, and its credentials are not
 * fixed at construction: {@link #refreshCredentials(FeishuChannelProperties)} swaps the
 * credential-derived collaborators in place, so rotating a tenant's app secret or encrypt key
 * never replaces the channel object and never disturbs its sessions. See
 * {@link FeishuTenantChannelManager} for the wiring.
 */
public final class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "feishu";

    private final String channelId;
    private final ChannelConfig config;
    private final FeishuInboundMapper mapper;
    private final InboundEventDeduplicator idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final FeishuChannelRegistry registry;

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

    private FeishuChannel(
            String channelId,
            ChannelConfig config,
            Credentials credentials,
            FeishuInboundMapper mapper,
            InboundEventDeduplicator idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            FeishuChannelRegistry registry,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
     * @param rawProperties provider-specific properties (appId, appSecret, encryptKey, ...)
     */
    public static FeishuChannel fromProperties(
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
     * otherwise. Note the Feishu callback controller currently delegates deduplication to the
     * durable intake, so the supplied store is held for channels that consult it.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (appId, appSecret, encryptKey, ...)
     * @param idempotency deduplicator for inbound events; must be thread-safe
     */
    public static FeishuChannel fromProperties(
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
     * {@link FeishuTenantChannelManager}, which allocates one store per credential generation.
     *
     * @param channelId the channel id (key in {@code agentscope.json#channels})
     * @param routing the {@link ChannelConfig} parsed from the file entry's routing block
     * @param rawProperties provider-specific properties (appId, appSecret, encryptKey, ...)
     * @param idempotency deduplicator for inbound events; must be thread-safe
     * @param tokenStore cache for this channel's access token — one instance per credential, not
     *     to be shared across channels with different credentials; must be thread-safe
     */
    public static FeishuChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            Map<String, Object> rawProperties,
            InboundEventDeduplicator idempotency,
            AccessTokenStore tokenStore) {
        Objects.requireNonNull(tokenStore, "tokenStore");
        return fromProperties(
                channelId,
                routing,
                FeishuChannelProperties.from(channelId, rawProperties),
                idempotency,
                () -> tokenStore);
    }

    /**
     * Factory for callers that already hold resolved {@link FeishuChannelProperties}. The
     * {@code tokenStoreFactory} is the seam multi-tenant wiring uses: it is called once per
     * credential generation instead of being handed one store for the channel's lifetime.
     */
    static FeishuChannel fromProperties(
            String channelId,
            ChannelConfig routing,
            FeishuChannelProperties properties,
            InboundEventDeduplicator idempotency,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
        return new FeishuChannel(
                channelId,
                routing,
                newCredentials(properties, null, tokenStoreFactory),
                new FeishuInboundMapper(channelId),
                idempotency,
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                FeishuChannelRegistry.instance(),
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
                "Feishu channel '{}' started: appId={}, callbackPath={}, encrypted={}",
                channelId,
                snapshot.properties().appId(),
                snapshot.properties().callbackPath(),
                snapshot.properties().isEncrypted());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("Feishu channel '{}' stopped", channelId);
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
                    new IllegalStateException("FeishuChannel '" + channelId + "' has no gateway"));
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
    public Mono<String> deliverWithReceipt(
            OutboundAddress address, Msg message, String deliveryId) {
        return credentials.outboundClient().sendWithReceipt(address, message, deliveryId);
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
                                        "Feishu channel '{}' deliver failed: {}",
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
     * {@link #refreshCredentials(FeishuChannelProperties)} cannot mix two generations mid-request.
     */
    Credentials credentials() {
        return credentials;
    }

    /**
     * The monitor guarding {@link #credentials}. {@link FeishuTenantChannelManager} holds it across
     * resolve-and-refresh, so the snapshot it applies always reflects the newest resolver answer
     * rather than the last thread to arrive; {@link #refreshCredentials(FeishuChannelProperties)}
     * locks the same monitor, and reentrant acquisition there is intended.
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
     *   <li>callback credentials ({@code encryptKey}, {@code verificationToken}) build the
     *       {@link FeishuCrypto} — {@code null} when the new credentials configure no encrypt key —
     *       and the verification token the controller matches events against;
     *   <li>app credentials ({@code appId}, {@code appSecret}, {@code apiBase}) build the outbound
     *       client together with its tenant-access-token cache;
     *   <li>so rotating only the encrypt key or the verification token keeps the cached access
     *       token, rotating the app secret starts a generation that mints its own, and a change
     *       that touches neither group — a differing {@code callbackPath} for example — leaves the
     *       channel untouched.
     * </ul>
     *
     * <p>The channel object itself never changes identity, so a tenant keeps its sessions and
     * bot-loop guard across a rotation, and a request in flight keeps verifying against the
     * credential generation it started with. Safe to call concurrently; concurrent rotations follow
     * last-write-wins.
     *
     * @param properties the tenant's current credentials
     */
    void refreshCredentials(FeishuChannelProperties properties) {
        Objects.requireNonNull(properties, "properties");
        synchronized (credentialLock) {
            Credentials current = credentials;
            if (callbackCredentialsEqual(current.properties(), properties)
                    && appCredentialsEqual(current.properties(), properties)) {
                return;
            }
            credentials = newCredentials(properties, current, tokenStoreFactory);
        }
    }

    /**
     * Builds the snapshot for {@code properties}, reusing {@code previous}'s collaborators whose
     * inputs are unchanged.
     */
    private static Credentials newCredentials(
            FeishuChannelProperties properties,
            Credentials previous,
            Supplier<AccessTokenStore> tokenStoreFactory) {
        boolean callbackUnchanged =
                previous != null && callbackCredentialsEqual(previous.properties(), properties);
        boolean appUnchanged =
                previous != null && appCredentialsEqual(previous.properties(), properties);
        FeishuCrypto crypto =
                callbackUnchanged
                        ? previous.crypto()
                        : properties.isEncrypted()
                                ? new FeishuCrypto(properties.encryptKey())
                                : null;
        FeishuOutboundClient outbound =
                appUnchanged
                        ? previous.outboundClient()
                        : new FeishuOutboundClient(
                                properties.apiBase(),
                                new FeishuAccessTokenProvider(
                                        properties.apiBase(),
                                        properties.appId(),
                                        properties.appSecret(),
                                        tokenStoreFactory.get()));
        return new Credentials(properties, crypto, outbound);
    }

    /** Fields the callback path consumes: signature verification, decryption, event tokens. */
    private static boolean callbackCredentialsEqual(
            FeishuChannelProperties a, FeishuChannelProperties b) {
        return Objects.equals(a.encryptKey(), b.encryptKey())
                && Objects.equals(a.verificationToken(), b.verificationToken());
    }

    /** Fields the outbound path consumes: token minting and message addressing. */
    private static boolean appCredentialsEqual(
            FeishuChannelProperties a, FeishuChannelProperties b) {
        return a.apiBase().equals(b.apiBase())
                && a.appId().equals(b.appId())
                && a.appSecret().equals(b.appSecret());
    }

    // -----------------------------------------------------------------
    //  Internal accessors for FeishuCallbackController
    // -----------------------------------------------------------------

    FeishuInboundMapper mapper() {
        return mapper;
    }

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
                                        "Feishu channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }

    /**
     * Immutable bundle of the collaborators derived from one credential generation.
     *
     * @param properties the credentials this snapshot was built from
     * @param crypto callback signature verification and decryption; {@code null} when the
     *     credentials configure no encrypt key (plaintext callbacks)
     * @param outboundClient outbound sends and their access-token cache
     */
    record Credentials(
            FeishuChannelProperties properties,
            FeishuCrypto crypto,
            FeishuOutboundClient outboundClient) {}
}
