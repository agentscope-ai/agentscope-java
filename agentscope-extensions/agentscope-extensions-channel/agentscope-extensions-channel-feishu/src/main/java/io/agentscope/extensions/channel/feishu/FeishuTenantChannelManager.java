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

import io.agentscope.extensions.channel.common.AccessTokenStore;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.extensions.channel.common.InMemoryAccessTokenStore;
import io.agentscope.extensions.channel.common.InboundEventDeduplicator;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the {@link FeishuChannel} of each tenant in a multi-tenant deployment, materializing it
 * on first use from {@link FeishuCredentialResolver} results.
 *
 * <p>Wire one instance into {@link FeishuCallbackController} and tenants can be added, rotated and
 * removed at runtime without the application constructing or registering a channel per tenant.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>{@link #channelFor(String)} resolves the tenant's credentials on every call, and the single
 * call that materializes a tenant resolves twice — the credential lock the second resolve runs
 * under belongs to the channel the first one creates, and {@link #channelFor(String)} documents
 * which of the two answers the tenant keeps. The first call materializes the tenant's channel;
 * later calls return that same instance after refreshing its credentials in place when they
 * changed — see {@link FeishuChannel#refreshCredentials}. A rotation
 * therefore never replaces the channel object: the tenant keeps its sessions and bot-loop guard,
 * and the credential fields that did not change keep their access-token cache.
 *
 * <p>The tenant key doubles as the channel id, so conversations and bot-loop guards are namespaced
 * per tenant by construction — two tenants whose user ids collide do not share sessions.
 * Per-tenant agent routing is expressed with {@code channel}-tier bindings in the shared
 * {@link ChannelConfig}.
 *
 * <p>{@link #evict(String)} drops a tenant. Call it when the tenant is deleted so its channel and
 * its token cache are released; the manager does not evict on its own, since a lookup that
 * transiently returns nothing must not tear down a live tenant's runtime.
 *
 * <h2>Scope</h2>
 *
 * <p>Materialized channels serve the inbound callback path — verify, decrypt, dispatch and reply.
 * They are owned by this manager: they are neither registered in the process-wide
 * {@link FeishuChannelRegistry} nor with a harness {@code ChannelManager}, so proactive outbound
 * delivery through {@code ChannelManager#deliver} does not reach them.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Safe for concurrent use. Materialization is atomic per tenant key; resolve-and-refresh is
 * serialized per tenant on the channel's credential lock, so the applied snapshot is the newest
 * resolver answer even when callbacks race a rotation; request paths read the credential snapshot
 * lock-free. The lock is per tenant, so lookups for different tenants proceed independently, while
 * a lookup for one tenant waits out the resolver call of another lookup for the same tenant — keep
 * the resolver's own work to a lookup, not a remote round trip on the callback path.
 */
public final class FeishuTenantChannelManager {

    private static final Logger log = LoggerFactory.getLogger(FeishuTenantChannelManager.class);

    private final FeishuCredentialResolver resolver;
    private final ChannelConfig routing;
    private final Gateway gateway;
    private final InboundEventDeduplicator idempotency;
    private final BiFunction<String, FeishuChannelProperties, AccessTokenStore> tokenStoreFactory;
    private final ConcurrentHashMap<String, FeishuChannel> channels = new ConcurrentHashMap<>();

    /**
     * Creates a manager using a process-local {@link IdempotencyStore}; see
     * {@link #FeishuTenantChannelManager(FeishuCredentialResolver, ChannelConfig, Gateway,
     * InboundEventDeduplicator)} and {@link #FeishuTenantChannelManager(FeishuCredentialResolver,
     * ChannelConfig, Gateway, InboundEventDeduplicator, BiFunction)} to supply shared-storage
     * implementations instead.
     *
     * @param resolver resolves credentials per tenant key; consulted on every callback, and twice
     *     on the call that first materializes the tenant
     * @param routing the {@link ChannelConfig} every tenant channel is built with (default agent,
     *     bindings, dm scope)
     * @param gateway the gateway tenant channels dispatch into
     */
    public FeishuTenantChannelManager(
            FeishuCredentialResolver resolver, ChannelConfig routing, Gateway gateway) {
        this(resolver, routing, gateway, new IdempotencyStore());
    }

    /**
     * Creates a manager with an application-supplied deduplicator — for example a shared-storage
     * implementation so platform redeliveries are recognized across instances. One deduplicator
     * instance serves every tenant channel; the Feishu callback controller itself delegates
     * deduplication to the durable intake, so the store only matters to code that consults the
     * channel's idempotency directly.
     *
     * <p>Each tenant channel allocates a process-local access-token store per credential
     * generation; see {@link #FeishuTenantChannelManager(FeishuCredentialResolver, ChannelConfig,
     * Gateway, InboundEventDeduplicator, BiFunction)} to supply shared-storage token stores
     * instead.
     *
     * @param resolver resolves credentials per tenant key; consulted on every callback, and twice
     *     on the call that first materializes the tenant
     * @param routing the {@link ChannelConfig} every tenant channel is built with (default agent,
     *     bindings, dm scope)
     * @param gateway the gateway tenant channels dispatch into
     * @param idempotency deduplicator shared by all tenant channels; must be thread-safe
     */
    public FeishuTenantChannelManager(
            FeishuCredentialResolver resolver,
            ChannelConfig routing,
            Gateway gateway,
            InboundEventDeduplicator idempotency) {
        this(
                resolver,
                routing,
                gateway,
                idempotency,
                (tenantKey, properties) -> new InMemoryAccessTokenStore());
    }

    /**
     * Creates a manager with an application-supplied deduplicator and access-token store factory —
     * for example shared-storage implementations so platform redeliveries are recognized, and one
     * instance's token refresh or invalidation serves the whole deployment.
     *
     * <p>The store factory is invoked each time a tenant's outbound runtime is (re)built — once
     * when the tenant is materialized, and once per app-credential rotation — with the tenant key
     * and that generation's {@code properties}. The store contract binds one instance to one
     * credential generation: in-memory implementations must return a fresh instance per call,
     * while shared-storage implementations key the slot by the tenant key and the generation's
     * credential fields, so every instance of the deployment serves one token per generation and a
     * rotation moves to a fresh slot.
     *
     * @param resolver resolves credentials per tenant key; consulted on every callback, and twice
     *     on the call that first materializes the tenant
     * @param routing the {@link ChannelConfig} every tenant channel is built with (default agent,
     *     bindings, dm scope)
     * @param gateway the gateway tenant channels dispatch into
     * @param idempotency deduplicator shared by all tenant channels; must be thread-safe
     * @param tokenStoreFactory yields the access-token store of one credential generation; must be
     *     thread-safe and must not return one instance for different credential generations
     */
    public FeishuTenantChannelManager(
            FeishuCredentialResolver resolver,
            ChannelConfig routing,
            Gateway gateway,
            InboundEventDeduplicator idempotency,
            BiFunction<String, FeishuChannelProperties, AccessTokenStore> tokenStoreFactory) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.tokenStoreFactory = Objects.requireNonNull(tokenStoreFactory, "tokenStoreFactory");
    }

    /**
     * Returns the channel serving {@code tenantKey}, materializing it on first use and refreshing
     * its credentials when they changed.
     *
     * <p>Resolution and refresh are serialized per tenant on the channel's credential lock, so two
     * callbacks that resolve different credential generations apply them in the order the resolver
     * answered: the snapshot the tenant keeps is the newest answer, not the one whose thread
     * happened to arrive last.
     *
     * <p>The one call that materializes a tenant resolves a second time, before the serialization
     * above can apply: the lock lives on the channel, and the channel does not exist until a resolve
     * has succeeded, so that first resolve only seeds the materialization while the resolve under
     * the lock is the one the channel keeps. A resolver must therefore tolerate being called more
     * than once for a single callback — make it a lookup, and keep any logging or metering free of
     * assumptions about the call count.
     *
     * @param tenantKey the routing key from the callback URL path
     * @return the tenant's channel, or empty when the resolver reports no such tenant
     */
    public Optional<FeishuChannel> channelFor(String tenantKey) {
        Objects.requireNonNull(tenantKey, "tenantKey");
        FeishuChannel channel = channels.get(tenantKey);
        if (channel == null) {
            // First use: the channel object carrying the lock does not exist yet, so this resolve
            // runs outside it. The result only seeds the materialization — the resolve below is
            // what the channel keeps, so a rotation racing this step still ends on the newest
            // answer rather than on this one.
            Optional<FeishuChannelProperties> initial = resolver.resolve(tenantKey);
            if (initial.isEmpty()) {
                return Optional.empty();
            }
            FeishuChannelProperties seed = initial.get();
            channel = channels.computeIfAbsent(tenantKey, key -> materialize(key, seed));
        }
        synchronized (channel.credentialLock()) {
            Optional<FeishuChannelProperties> resolved = resolver.resolve(tenantKey);
            if (resolved.isEmpty()) {
                // A lookup that transiently returns nothing serves no callback but keeps the live
                // channel: the platform retries, and the tenant's runtime is not torn down.
                return Optional.empty();
            }
            channel.refreshCredentials(resolved.get());
        }
        return Optional.of(channel);
    }

    /**
     * Drops the channel cached for {@code tenantKey}, if any. The next {@link #channelFor(String)}
     * call materializes a fresh one.
     *
     * @param tenantKey the routing key from the callback URL path
     */
    public void evict(String tenantKey) {
        Objects.requireNonNull(tenantKey, "tenantKey");
        if (channels.remove(tenantKey) != null) {
            log.info("Feishu tenant '{}' evicted", tenantKey);
        }
    }

    private FeishuChannel materialize(String tenantKey, FeishuChannelProperties properties) {
        FeishuChannel channel =
                FeishuChannel.fromProperties(
                        tenantKey,
                        routing,
                        properties,
                        idempotency,
                        generation -> tokenStoreFactory.apply(tenantKey, generation));
        channel.init(gateway);
        log.info("Feishu tenant '{}' channel materialized", tenantKey);
        return channel;
    }
}
