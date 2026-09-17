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
package io.agentscope.extensions.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.channel.common.BotLoopGuard;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/** Personal Weixin adapter backed by Tencent's official iLink long-poll API. */
public final class WeixinChannel implements Channel {
    public static final String TYPE = "weixin";
    private static final Logger log = LoggerFactory.getLogger(WeixinChannel.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final ChannelConfig config;
    private final WeixinChannelProperties p;
    private final WeixinInboundMapper mapper;
    private final WeixinOutboundClient outbound;
    private final ChannelRouter router;
    private final WeixinStateStore stateStore;
    private final WeixinRuntimeListener runtimeListener;
    private final BotLoopGuard guard = new BotLoopGuard();
    private final ScheduledExecutorService executor;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "weixin-lease-renewal");
                        t.setDaemon(true);
                        return t;
                    });
    private volatile LeaseSession active;
    private volatile Gateway gateway;
    private volatile boolean running;
    private volatile long backoff = 1000;
    private final String leaseHolder = java.util.UUID.randomUUID().toString();

    private WeixinChannel(
            String id,
            ChannelConfig c,
            WeixinChannelProperties p,
            WeixinCredentialProvider credentialProvider,
            WeixinStateStore stateStore,
            WeixinRuntimeListener runtimeListener) {
        this.id = id;
        this.config = c;
        this.p = p;
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.runtimeListener = Objects.requireNonNull(runtimeListener, "runtimeListener");
        // iLink's ilink_user_id identifies the person who scanned the QR code. Incoming
        // messages from peers must therefore not be filtered against that value; the bot/account
        // id is the identity used for loop prevention.
        this.mapper = new WeixinInboundMapper(id, p.accountId(), p.accountId());
        this.outbound = new WeixinOutboundClient(p, credentialProvider);
        this.router = new ChannelRouter(c.defaultAgentId());
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "weixin-" + p.accountId());
                            t.setDaemon(true);
                            return t;
                        });
    }

    public static WeixinChannel fromProperties(
            String id, ChannelConfig c, Map<String, Object> props) {
        Map<String, Object> raw = props == null ? Map.of() : props;
        Object token = raw.get("botToken");
        if (token == null || token.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "weixin.botToken is required by the standalone properties factory");
        }
        log.warn(
                "Weixin channel '{}' uses the standalone properties factory: credentials and"
                        + " runtime state stay in this process and are lost on restart. Production"
                        + " hosts should call WeixinChannel.create(...) with a durable"
                        + " WeixinStateStore.",
                id);
        return create(
                id,
                c,
                WeixinChannelProperties.from(id, raw),
                WeixinCredentialProvider.fixed(token.toString()),
                WeixinStateStore.inMemory(),
                WeixinRuntimeListener.noOp());
    }

    /** Constructs a channel with host-provided credentials, state, and runtime observations. */
    public static WeixinChannel create(
            String id,
            ChannelConfig config,
            WeixinChannelProperties properties,
            WeixinCredentialProvider credentialProvider,
            WeixinStateStore stateStore,
            WeixinRuntimeListener runtimeListener) {
        return new WeixinChannel(
                Objects.requireNonNull(id, "id"),
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(properties, "properties"),
                Objects.requireNonNull(credentialProvider, "credentialProvider"),
                Objects.requireNonNull(stateStore, "stateStore"),
                Objects.requireNonNull(runtimeListener, "runtimeListener"));
    }

    public String channelId() {
        return id;
    }

    public ChannelConfig config() {
        return config;
    }

    public void init(Gateway g) {
        if (gateway == null) gateway = Objects.requireNonNull(g, "gateway");
    }

    public synchronized void start() {
        if (running) return;
        if (executor.isShutdown())
            throw new IllegalStateException("Stopped channel cannot restart");
        running = true;
        executor.execute(this::poll);
        log.info("Weixin channel '{}' started: accountId={}", id, p.accountId());
    }

    public void stop() {
        running = false;
        LeaseSession session = active;
        if (session != null) session.cancelled.tryEmitEmpty();
        heartbeat.shutdownNow();
        executor.shutdownNow();
        log.info("Weixin channel '{}' stopped", id);
    }

    public Mono<Msg> dispatch(InboundMessage in) {
        return Mono.defer(() -> dispatch(in, active));
    }

    private Mono<Msg> dispatch(InboundMessage in, LeaseSession session) {
        return Mono.defer(
                () -> {
                    requireLease(session);
                    Gateway g = gateway;
                    if (g == null)
                        return Mono.error(
                                new IllegalStateException("Weixin channel has no gateway"));
                    RouteResult route = router.resolveRoute(config, in);
                    return g.run(
                                    route.context(),
                                    in.messages(),
                                    route.outboundAddress(),
                                    in.runtimeContext(),
                                    in)
                            .flatMap(
                                    reply ->
                                            outbound.sendWithContext(
                                                            route.outboundAddress(),
                                                            List.of(reply),
                                                            context(in),
                                                            () -> requireLease(session))
                                                    .thenReturn(reply))
                            .takeUntilOther(session.cancelled.asMono());
                });
    }

    public void deliver(OutboundAddress address, List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return;
        LeaseSession session = active;
        requireLease(session);
        outbound.sendWithContext(
                        address,
                        messages,
                        stateStore.loadContextToken(p.accountId(), peerId(address)),
                        () -> requireLease(session))
                .doOnError(
                        error ->
                                log.warn(
                                        "Weixin delivery failed: {}",
                                        error.getClass().getSimpleName()))
                .subscribe(
                        ignored -> {},
                        error -> {
                            if (active == session
                                    && session.valid
                                    && error
                                            instanceof WeixinCredentialRejectedException rejected) {
                                notifyCredentialRejected(rejected);
                                stop();
                            }
                        });
    }

    private static String peerId(OutboundAddress address) {
        String value = address.to();
        int i = value.lastIndexOf(':');
        return i < 0 ? value : value.substring(i + 1);
    }

    private String context(InboundMessage in) {
        if (in.messages().isEmpty() || in.messages().get(0).getMetadata() == null) return null;
        Object v = in.messages().get(0).getMetadata().get("weixinContextToken");
        return v == null ? null : v.toString();
    }

    private void poll() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    Optional<WeixinLease> lease =
                            stateStore.acquireLease(p.accountId(), leaseHolder, p.leaseMs());
                    if (lease.isEmpty()) {
                        sleep(Math.min(1000, Math.max(10, p.leaseMs() / 3)));
                        continue;
                    }
                    consume(new LeaseSession(lease.get()));
                } catch (RuntimeException error) {
                    if (running) {
                        log.warn("Weixin consumer retry: {}", safeMessage(error));
                        sleep(backoff);
                    }
                }
            }
        } finally {
            running = false;
        }
    }

    private void consume(LeaseSession session) {
        active = session;
        java.util.concurrent.ScheduledFuture<?> renewal = null;
        boolean providerStarted = false;
        try {
            renewal =
                    heartbeat.scheduleWithFixedDelay(
                            () -> renew(session),
                            Math.max(1, p.leaseMs() / 3),
                            Math.max(1, p.leaseMs() / 3),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
            notifyListener(
                    "lease acquired",
                    listener -> listener.onLeaseAcquired(p.accountId(), session.lease));
            try {
                requireLease(session);
                outbound.notifyStart(() -> requireLease(session));
                providerStarted = true;
                notifyListener("running", listener -> listener.onRunning(p.accountId()));
            } catch (WeixinCredentialRejectedException error) {
                throw error;
            } catch (Exception error) {
                notifyListener(
                        "transient failure",
                        listener -> listener.onTransientFailure(p.accountId(), safeMessage(error)));
                // The provider session never started: there is nothing to poll and nothing to stop.
                // Leave the lease so the outer loop backs off, reacquires and retries startup.
                throw new IllegalStateException("Weixin provider session did not start");
            }
            while (running && session.valid && !Thread.currentThread().isInterrupted()) {
                try {
                    requireLease(session);
                    // Recover accepted work before contacting the provider again.
                    drainInbox(session);
                    requireLease(session);
                    var response = outbound.updates(stateStore.loadCursor(p.accountId()));
                    requireLease(session);
                    if (response.ret() == -14
                            || (response.errcode() != null && response.errcode() == -14))
                        throw new WeixinCredentialRejectedException("getupdates", -14);
                    if (response.ret() != 0
                            || (response.errcode() != null && response.errcode() != 0))
                        throw new IllegalStateException("iLink polling failed");
                    if (!stateStore.acceptBatch(
                            p.accountId(),
                            session.lease,
                            response.get_updates_buf(),
                            inboxMessages(response.msgs()))) {
                        loseLease(session);
                        break;
                    }
                    drainInbox(session);
                    notifyListener("recovered", listener -> listener.onRecovered(p.accountId()));
                    backoff = 1000;
                } catch (WeixinCredentialRejectedException error) {
                    throw error;
                } catch (Exception error) {
                    if (!running || !session.valid) break;
                    notifyListener(
                            "transient failure",
                            listener ->
                                    listener.onTransientFailure(p.accountId(), safeMessage(error)));
                    sleep(backoff);
                    backoff = Math.min(p.maxBackoffMs(), backoff * 2);
                }
            }
        } catch (WeixinCredentialRejectedException error) {
            notifyCredentialRejected(error);
            running = false;
        } finally {
            // Stop is an external side effect too: a stale consumer must not stop its successor.
            try {
                if (providerStarted
                        && session.valid
                        && stateStore.isLeaseCurrent(p.accountId(), session.lease))
                    outbound.notifyStop(
                            () -> {
                                if (!stateStore.isLeaseCurrent(p.accountId(), session.lease))
                                    throw new IllegalStateException("Weixin lease lost");
                            });
            } catch (Exception error) {
                log.debug("Weixin notifyStop failed: {}", safeMessage(error));
            } finally {
                if (renewal != null) renewal.cancel(false);
                loseLease(session);
                try {
                    stateStore.releaseLease(p.accountId(), session.lease);
                } catch (RuntimeException error) {
                    log.warn("Weixin lease release failed: {}", safeMessage(error));
                }
                active = null;
                notifyListener("stopped", listener -> listener.onStopped(p.accountId()));
            }
        }
    }

    private void renew(LeaseSession session) {
        try {
            if (!running
                    || !session.valid
                    || !stateStore.renewLease(p.accountId(), session.lease, p.leaseMs())) {
                loseLease(session);
            }
        } catch (RuntimeException error) {
            loseLease(session);
        }
    }

    private void requireLease(LeaseSession session) {
        if (session == null || !running || !session.valid || active != session)
            throw new IllegalStateException("Weixin consumer is not active");
        try {
            if (stateStore.isLeaseCurrent(p.accountId(), session.lease)) return;
        } catch (RuntimeException error) {
            loseLease(session);
            throw error;
        }
        loseLease(session);
        throw new IllegalStateException("Weixin lease lost");
    }

    private void loseLease(LeaseSession session) {
        session.valid = false;
        session.cancelled.tryEmitEmpty();
    }

    private void processMessage(JsonNode message, LeaseSession session) {
        mapper.map(message)
                .ifPresent(
                        inbound -> {
                            requireLease(session);
                            if (!guard.allow(inbound.peer().key()))
                                throw new IllegalStateException("Weixin peer rate limited");
                            String token = context(inbound);
                            if (token != null
                                    && !stateStore.saveContextToken(
                                            p.accountId(),
                                            session.lease,
                                            inbound.peer().id(),
                                            token)) {
                                loseLease(session);
                                throw new IllegalStateException("Weixin context update fenced");
                            }
                            dispatch(inbound, session)
                                    .block(Duration.ofMillis(p.dispatchTimeoutMs()));
                            requireLease(session);
                        });
    }

    private void drainInbox(LeaseSession session) throws Exception {
        while (running && session.valid && !Thread.currentThread().isInterrupted()) {
            requireLease(session);
            List<WeixinInboxClaim> claims =
                    stateStore.claimMessages(
                            p.accountId(),
                            session.lease,
                            1,
                            (long) p.dispatchTimeoutMs() + p.leaseMs());
            if (claims.isEmpty()) return;
            WeixinInboxClaim claim = claims.get(0);
            try {
                processMessage(JSON.readTree(claim.payload()), session);
                if (!stateStore.completeMessage(p.accountId(), session.lease, claim)) {
                    loseLease(session);
                    throw new IllegalStateException("Weixin inbox completion fenced");
                }
            } catch (Exception error) {
                stateStore.failMessage(p.accountId(), session.lease, claim);
                throw error;
            }
        }
    }

    private static final class LeaseSession {
        final WeixinLease lease;
        final reactor.core.publisher.Sinks.Empty<Void> cancelled =
                reactor.core.publisher.Sinks.empty();
        volatile boolean valid = true;

        LeaseSession(WeixinLease lease) {
            this.lease = lease;
        }
    }

    /**
     * Derives one durable inbox id per message.
     *
     * <p>A message the provider sends without {@code message_id} is keyed by its position in the
     * batch plus a digest of the payload. The position is what keeps two byte-identical id-less
     * messages — a user typing the same thing twice — from collapsing into a single claim and
     * losing one of them, while a re-delivered batch keeps the same ids and is still deduplicated.
     */
    private List<WeixinInboxMessage> inboxMessages(List<JsonNode> messages) {
        List<JsonNode> batch = messages == null ? List.of() : messages;
        List<WeixinInboxMessage> result = new ArrayList<>(batch.size());
        for (int position = 0; position < batch.size(); position++) {
            JsonNode message = batch.get(position);
            String payload = message.toString();
            int index = position;
            String messageId =
                    WeixinInboundMapper.messageId(message)
                            .orElseGet(() -> "payload-" + index + "-" + digest(payload));
            result.add(new WeixinInboxMessage(messageId, payload));
        }
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void notifyListener(String event, Consumer<WeixinRuntimeListener> notification) {
        try {
            notification.accept(runtimeListener);
        } catch (RuntimeException e) {
            log.warn(
                    "Weixin runtime listener failed during '{}' for account '{}': {}",
                    event,
                    p.accountId(),
                    safeMessage(e));
        }
    }

    private void notifyCredentialRejected(WeixinCredentialRejectedException error) {
        notifyListener(
                "credential rejected",
                listener -> listener.onCredentialRejected(p.accountId(), error.getMessage()));
    }

    private static String safeMessage(Exception error) {
        return error.getClass().getSimpleName();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
