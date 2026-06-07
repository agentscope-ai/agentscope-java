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
package io.agentscope.extensions.channel.github;

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
 * GitHub channel adapter. Reacts to {@code issue_comment} and {@code pull_request_review_comment}
 * webhooks delivered to {@link GitHubWebhookController}; replies are posted as new comments via
 * {@link GitHubOutboundClient}.
 */
public final class GitHubChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(GitHubChannel.class);

    /** {@code type} value used in {@code agentscope.json} and {@link io.agentscope.harness.agent.gateway.channel.ChannelFactory}. */
    public static final String TYPE = "github";

    private final String channelId;
    private final ChannelConfig config;
    private final GitHubChannelProperties properties;
    private final GitHubSignatureVerifier signatureVerifier;
    private final GitHubOutboundClient outboundClient;
    private final GitHubInboundMapper mapper;
    private final GitHubBotIdentityResolver botIdentityResolver;
    private final IdempotencyStore idempotency;
    private final BotLoopGuard botLoopGuard;
    private final ChannelRouter router;
    private final GitHubChannelRegistry registry;

    private volatile Gateway gateway;

    private GitHubChannel(
            String channelId,
            ChannelConfig config,
            GitHubChannelProperties properties,
            GitHubSignatureVerifier signatureVerifier,
            GitHubOutboundClient outboundClient,
            GitHubInboundMapper mapper,
            GitHubBotIdentityResolver botIdentityResolver,
            IdempotencyStore idempotency,
            BotLoopGuard botLoopGuard,
            ChannelRouter router,
            GitHubChannelRegistry registry) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.config = Objects.requireNonNull(config, "config");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
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
    public static GitHubChannel fromProperties(
            String channelId, ChannelConfig routing, Map<String, Object> rawProperties) {
        GitHubChannelProperties props = GitHubChannelProperties.from(channelId, rawProperties);
        GitHubSignatureVerifier sig = new GitHubSignatureVerifier(props.webhookSecret());
        GitHubOutboundClient outbound = new GitHubOutboundClient(props.apiBase(), props.token());
        GitHubInboundMapper mapper = new GitHubInboundMapper(channelId);
        GitHubBotIdentityResolver identity =
                new GitHubBotIdentityResolver(props.apiBase(), props.token(), props.botUserLogin());
        return new GitHubChannel(
                channelId,
                routing,
                props,
                sig,
                outbound,
                mapper,
                identity,
                new IdempotencyStore(),
                new BotLoopGuard(),
                new ChannelRouter(routing.defaultAgentId()),
                GitHubChannelRegistry.instance());
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
        // Resolve bot identity once at startup so bot-loop filtering works on the first inbound.
        botIdentityResolver.refresh();
        log.info(
                "GitHub channel '{}' started: apiBase={}, webhookPath={}, botLogin={}",
                channelId,
                properties.apiBase(),
                properties.webhookPath(),
                botIdentityResolver.botLogin());
    }

    @Override
    public void stop() {
        registry.unregister(channelId);
        log.info("GitHub channel '{}' stopped", channelId);
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage message) {
        Objects.requireNonNull(message, "message");
        Gateway g = gateway;
        if (g == null) {
            return Mono.error(
                    new IllegalStateException("GitHubChannel '" + channelId + "' has no gateway"));
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
                                        "GitHub channel '{}' deliver failed: {}",
                                        channelId,
                                        err.getMessage()))
                .subscribe();
    }

    // -----------------------------------------------------------------
    //  Internal accessors for GitHubWebhookController
    // -----------------------------------------------------------------

    GitHubSignatureVerifier signatureVerifier() {
        return signatureVerifier;
    }

    GitHubInboundMapper mapper() {
        return mapper;
    }

    GitHubBotIdentityResolver botIdentity() {
        return botIdentityResolver;
    }

    IdempotencyStore idempotency() {
        return idempotency;
    }

    BotLoopGuard botLoopGuard() {
        return botLoopGuard;
    }

    GitHubChannelProperties properties() {
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
                                        "GitHub channel '{}' reply send failed: {}",
                                        channelId,
                                        err.getMessage()));
    }
}
