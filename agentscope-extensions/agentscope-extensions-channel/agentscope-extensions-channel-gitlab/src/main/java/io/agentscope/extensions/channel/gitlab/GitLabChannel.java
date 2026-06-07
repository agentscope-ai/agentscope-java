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
package io.agentscope.extensions.channel.gitlab;

import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.extensions.channel.common.IdempotencyStore;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.core.message.Msg;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * GitLab channel adapter. Reacts to {@code Note Hook} webhooks delivered to
 * {@link GitLabWebhookController}; replies are posted as new notes via {@link GitLabOutboundClient}.
 */
public final class GitLabChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(GitLabChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "gitlab";

    private final String channelId;
    private final ChannelConfig config;
    private final GitLabChannelProperties properties;
    private final GitLabOutboundClient outboundClient;
    private final GitLabInboundMapper mapper;
    private final GitLabBotIdentityResolver botIdentityResolver;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final GitLabChannelRegistry registry;

    private volatile Gateway gateway;

    private GitLabChannel(
            String channelId,
            ChannelConfig config,
            GitLabChannelProperties properties,
            GitLabOutboundClient outboundClient,
            GitLabInboundMapper mapper,
            GitLabBotIdentityResolver botIdentityResolver,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            GitLabChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.outboundClient = Objects.requireNonNull(outboundClient, "outboundClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.botIdentityResolver =
                Objects.requireNonNull(botIdentityResolver, "botIdentityResolver");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.botLoopGuard = Objects.requireNonNull(botLoopGuard, "botLoopGuard");
        this.router = Objects.requireNonNull(router, "router");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Factory used by {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static GitLabChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        GitLabChannelProperties props = GitLabChannelProperties.from(channelId, rawProperties);
        GitLabOutboundClient outbound = new GitLabOutboundClient(props.apiBase(), props.token());
        GitLabInboundMapper mapper = new GitLabInboundMapper(channelId);
        GitLabBotIdentityResolver identity =
                new GitLabBotIdentityResolver(props.apiBase(), props.token());
        return new GitLabChannel(
                channelId,
                routing,
                props,
                outbound,
                mapper,
                identity,
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                GitLabChannelRegistry.instance());
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
        botIdentityResolver.refresh();
        log.info(
                "GitLab channel '{}' started: apiBase={}, webhookPath={}, botUsername={}",
                channelId,
                properties.apiBase(),
                properties.webhookPath(),
                botIdentityResolver.botUsername());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("GitLab channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("GitLabChannel '" + channelId + "' has no gateway"));
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
                                        "GitLab channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Internal accessors for GitLabWebhookController
    // -----------------------------------------------------------------

    GitLabInboundMapper mapper() {
        return mapper;
    }

    GitLabBotIdentityResolver botIdentity() {
        return botIdentityResolver;
    }

    IdempotencyStore idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    GitLabChannelProperties properties() {
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
                                        "GitLab channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
