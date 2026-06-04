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
package io.agentscope.dataagent.runtime.gateway;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.dataagent.runtime.channel.OutboundAddress;
import io.agentscope.dataagent.runtime.session.PendingCompletion;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.runtime.session.SessionConstants;
import io.agentscope.dataagent.runtime.session.SessionEntry;
import io.agentscope.dataagent.runtime.session.SessionFreshness;
import io.agentscope.dataagent.runtime.session.SessionFreshnessEvaluator;
import io.agentscope.dataagent.runtime.session.SessionKind;
import io.agentscope.dataagent.runtime.session.SessionResetPolicy;
import io.agentscope.dataagent.runtime.session.SessionView;
import io.agentscope.dataagent.runtime.session.SpawnResult;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Gateway-style gateway: routes inbound turns by {@link MsgContext}, and dispatches subagent
 * completion announces as new {@link HarnessAgent} runs on the root requester (OpenClaw gateway
 * analogue).
 *
 * <p>AgentStateStore management is delegated to {@link SessionAgentManager}. The gateway wires itself as
 * the {@link SessionAgentManager.AnnounceDispatcher} and {@link
 * SessionAgentManager.SpawnInterceptor} on creation, so subagent spawns and announces flow through
 * the correct channel gates.
 *
 * <h2>AgentStateStore routing</h2>
 *
 * On the first {@link #run} call for a given {@link MsgContext#canonicalKey()}, a MAIN session is
 * registered in the {@link SessionAgentManager}. Subsequent calls for the same key reuse that
 * session. Multiple harness agents may be registered with {@link #registerAgent} for agentId-based
 * routing.
 *
 * <h2>Per-session turn serialization</h2>
 *
 * Each {@link MsgContext#canonicalKey()} has a fair lock ({@link SessionTurnGate}) so at most one
 * {@code HarnessAgent.call} runs at a time for that key.
 */
public final class HarnessGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChannelManager channelManager;

    /**
     * Optional registry that owns one live Docker {@link Sandbox} per {@code (userId, agentId)}.
     * When non-null, every {@link #run} / {@link #tryDispatchAnnounce} turn injects that sandbox
     * into the {@link RuntimeContext} as a {@link SandboxContext#getExternalSandbox()} so the
     * harness takes its Priority-1 acquire path and the agent runs against the same container the
     * browser workspace controllers read/write through.
     *
     * <p>Set via constructor or {@link #setUserSandboxRegistry(UserSandboxRegistry)} (one-shot).
     */
    private volatile UserSandboxRegistry userSandboxRegistry;

    /** Primary agent, set via {@link #bindMainAgent}. Used as routing fallback. */
    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();

    /** agentId -> HarnessAgent, populated by {@link #bindMainAgent} and {@link #registerAgent}. */
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();

    /** The agentId registered by the most recent {@link #bindMainAgent} call. */
    private volatile String defaultAgentId = null;

    /**
     * gateKey -> main session key (in SessionAgentManager). Populated on first {@link #run} per
     * key; used to resume the same MAIN session across turns.
     */
    private final ConcurrentHashMap<String, String> contextKeyToSessionKey =
            new ConcurrentHashMap<>();

    /**
     * main session key -> gateKey. Populated when a MAIN session is created. Used by {@link
     * #tryDispatchAnnounce} to deliver completion announces on the correct channel gate.
     */
    private final ConcurrentHashMap<String, String> sessionKeyToGateKey = new ConcurrentHashMap<>();

    /**
     * main session key -> agentId. Used by {@link #tryDispatchAnnounce} to route the completion
     * announce to the same agent that originated the run.
     */
    private final ConcurrentHashMap<String, String> sessionKeyToAgentId = new ConcurrentHashMap<>();

    /**
     * session key -> last outbound address. Updated on every inbound {@link #run} call so
     * proactive outbound messages (announces) can be delivered to the correct channel/peer.
     */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySessionKey =
            new ConcurrentHashMap<>();

    private final SessionTurnGate sessionTurnGate = new SessionTurnGate();

    private HarnessGateway(
            SessionAgentManager sessionAgentManager,
            ChannelManager channelManager,
            UserSandboxRegistry userSandboxRegistry) {
        this.sessionAgentManager = Objects.requireNonNull(sessionAgentManager);
        this.channelManager = channelManager;
        this.userSandboxRegistry = userSandboxRegistry;
    }

    /**
     * Creates a gateway wired to the given {@link SessionAgentManager}, {@link ChannelManager},
     * and {@link UserSandboxRegistry}. Sets up the announce dispatcher and spawn interceptor on
     * the session manager, and restores persisted MAIN session routing maps from the session
     * store (if available).
     *
     * @param sessionAgentManager session and agent lifecycle manager
     * @param channelManager channel registry for outbound delivery; may be null if outbound
     *     delivery is not needed
     * @param userSandboxRegistry per-{@code (userId, agentId)} sandbox registry; when non-null,
     *     each turn injects the user's live sandbox into {@link SandboxContext#getExternalSandbox()}
     *     so the agent runs against the same container the browser path uses. May be null in
     *     test setups that don't care about workspace isolation.
     */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager,
            ChannelManager channelManager,
            UserSandboxRegistry userSandboxRegistry) {
        Objects.requireNonNull(sessionAgentManager, "sessionAgentManager");
        HarnessGateway gateway =
                new HarnessGateway(sessionAgentManager, channelManager, userSandboxRegistry);
        sessionAgentManager.setAnnounceDispatcher(gateway::tryDispatchAnnounce);
        sessionAgentManager.setSpawnInterceptor(gateway::onSpawn);
        gateway.restorePersistedMainSessions();
        return gateway;
    }

    /** Creates a gateway without sandbox-injection (legacy callers/tests). */
    public static HarnessGateway create(
            SessionAgentManager sessionAgentManager, ChannelManager channelManager) {
        return create(sessionAgentManager, channelManager, null);
    }

    /** Creates a gateway without a channel manager or sandbox registry. */
    public static HarnessGateway create(SessionAgentManager sessionAgentManager) {
        return create(sessionAgentManager, null, null);
    }

    /** The session agent manager used by this gateway. */
    public SessionAgentManager sessionAgentManager() {
        return sessionAgentManager;
    }

    /** The channel manager for outbound delivery, or null if not configured. */
    public ChannelManager channelManager() {
        return channelManager;
    }

    /**
     * Attach (or replace) the per-user sandbox registry. Intended for one-shot wiring at
     * application startup — typically called by Spring config after {@link
     * io.agentscope.dataagent.runtime.DataAgentBootstrap} has produced the gateway, since the
     * bootstrap layer doesn't depend on the web layer.
     */
    public void setUserSandboxRegistry(UserSandboxRegistry registry) {
        this.userSandboxRegistry = registry;
    }

    /**
     * Restores gateway routing maps from persisted MAIN sessions in the session store. Only
     * fresh sessions (per the configured {@link SessionResetPolicy}) are restored; stale
     * sessions are skipped so a new session will be created on the next inbound turn.
     */
    private void restorePersistedMainSessions() {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        long now = System.currentTimeMillis();
        int restored = 0;
        for (SessionEntry entry : sessionAgentManager.allSessions()) {
            if (entry.kind() != SessionKind.MAIN) {
                continue;
            }
            String gateKey = entry.gateKey();
            if (gateKey == null || gateKey.isBlank()) {
                continue;
            }
            if (policy.mode() != SessionResetPolicy.ResetMode.NEVER) {
                SessionFreshness freshness =
                        SessionFreshnessEvaluator.evaluate(entry.lastActivityMs(), now, policy);
                if (!freshness.fresh()) {
                    log.debug(
                            "Skipping stale persisted session: sessionKey={}, gateKey={}",
                            entry.sessionKey(),
                            gateKey);
                    continue;
                }
            }
            contextKeyToSessionKey.putIfAbsent(gateKey, entry.sessionKey());
            sessionKeyToGateKey.putIfAbsent(entry.sessionKey(), gateKey);
            sessionKeyToAgentId.putIfAbsent(entry.sessionKey(), entry.agentId());
            restored++;
        }
        if (restored > 0) {
            log.info("Restored {} persisted MAIN session routing maps", restored);
        }
    }

    /**
     * Binds the primary harness agent. Also registers it under its {@link
     * HarnessAgent#getAgentId()} for routing.
     */
    @Override
    public void bindMainAgent(HarnessAgent agent) {
        Objects.requireNonNull(agent, "agent");
        mainAgent.set(agent);
        String id = resolveAgentId(agent);
        agentRegistry.put(id, agent);
        defaultAgentId = id;
    }

    @Override
    public void registerAgent(String agentId, HarnessAgent agent) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(agent, "agent");
        agentRegistry.put(agentId, agent);
    }

    /**
     * Returns the {@link HarnessAgent} registered under {@code gatewayId} (typically either a
     * global agent id or a {@code uca-{userId}-{agentId}} namespaced id), or {@code null} if no
     * agent is currently registered for that id. Exposed so platform controllers can introspect
     * a built-and-registered agent (e.g. enumerate its skill repositories) without going through
     * the routing path.
     */
    public HarnessAgent findAgent(String gatewayId) {
        if (gatewayId == null) return null;
        return agentRegistry.get(gatewayId);
    }

    /**
     * Direct or channel-originated turn. Resolves or creates a MAIN session keyed by {@link
     * MsgContext#canonicalKey()}, routes to the appropriate agent, and runs the turn under the
     * per-key {@link SessionTurnGate}.
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    /**
     * Inbound turn with outbound address tracking. Records the {@code outboundAddress} as the
     * session's "last route" so proactive outbound messages (subagent announces) can be delivered
     * to the correct channel/peer.
     */
    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? (String) ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, ctx.userId());
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        if (outboundAddress != null) {
            lastRouteBySessionKey.put(sessionKey, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("sessionKey", sessionKey);
        if (ctx.userId() != null) {
            rtcBuilder.userId(ctx.userId());
        }
        attachUserSandboxContext(rtcBuilder, ctx.userId(), resolveAgentId(ha));
        RuntimeContext runtimeContext = rtcBuilder.build();
        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    /**
     * Spawn interceptor: records gate-key, agent-id, and last-route mappings so announces for
     * child sessions route to the correct channel gate, agent, and outbound delivery target.
     */
    private void onSpawn(SpawnResult result, String parentSessionKey) {
        if (parentSessionKey != null) {
            String gateKey =
                    sessionKeyToGateKey.getOrDefault(
                            parentSessionKey, MsgContext.defaultContext().canonicalKey());
            sessionKeyToGateKey.put(result.sessionKey(), gateKey);
            String parentAgentId = sessionKeyToAgentId.get(parentSessionKey);
            if (parentAgentId != null) {
                sessionKeyToAgentId.put(result.sessionKey(), parentAgentId);
            }
            OutboundAddress parentRoute = lastRouteBySessionKey.get(parentSessionKey);
            if (parentRoute != null) {
                lastRouteBySessionKey.put(result.sessionKey(), parentRoute);
            }
        }
    }

    /**
     * Attempts gateway-style dispatch on subagent completion: schedules a new agent turn carrying
     * {@link PendingCompletion#announceText()} on the requester's gate, then delivers the agent's
     * reply through the originating channel via {@link ChannelManager}.
     */
    boolean tryDispatchAnnounce(PendingCompletion completion) {
        String requesterKey = completion.requesterSessionKey();
        String gateKey = sessionKeyToGateKey.get(requesterKey);
        String requesterAgentId = sessionKeyToAgentId.get(requesterKey);

        boolean isRootRequester = SessionConstants.ROOT_REQUESTER_SESSION_KEY.equals(requesterKey);
        if (gateKey == null && !isRootRequester) {
            return false;
        }
        if (gateKey == null) {
            gateKey = MsgContext.defaultContext().canonicalKey();
        }

        HarnessAgent ha = resolveAgent(requesterAgentId);
        if (ha == null) {
            log.debug(
                    "Announce skipped: no agent found for requesterKey={} agentId={}",
                    requesterKey,
                    requesterAgentId);
            return false;
        }

        Msg m =
                Msg.builder()
                        .role(MsgRole.USER)
                        .name("subagent_announce")
                        .textContent(completion.announceText())
                        .build();

        // Retrieve the userId stored on the requester session for namespace continuity
        String sessionUserId =
                sessionAgentManager.getSession(requesterKey).map(SessionEntry::userId).orElse(null);

        String sessionKey = resolveOrCreateMainSession(gateKey, ha, sessionUserId);
        String sessionId =
                sessionAgentManager
                        .viewSession(sessionKey)
                        .map(SessionView::sessionId)
                        .orElse(sessionKey);

        RuntimeContext.Builder ctxBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("announce", Boolean.TRUE)
                        .put("childRunId", completion.runId())
                        .put("childSessionKey", completion.childSessionKey());
        if (sessionUserId != null) {
            ctxBuilder.userId(sessionUserId);
        }
        attachUserSandboxContext(ctxBuilder, sessionUserId, resolveAgentId(ha));
        RuntimeContext ctx = ctxBuilder.build();

        OutboundAddress lastRoute = lastRouteBySessionKey.get(requesterKey);
        if (lastRoute == null) {
            lastRoute = lastRouteBySessionKey.get(sessionKey);
        }
        final OutboundAddress deliveryTarget = lastRoute;

        final String resolvedGateKey = gateKey;
        withGatedTurn(resolvedGateKey, () -> ha.call(List.of(m), ctx))
                .subscribe(
                        reply -> deliverAnnounceReply(deliveryTarget, reply),
                        err -> log.warn("Announce agent run failed: {}", err.toString()));
        return true;
    }

    /**
     * Delivers the agent's announce reply through the channel manager. If no channel manager or no
     * last route is available, the reply is logged but not delivered.
     */
    private void deliverAnnounceReply(OutboundAddress target, Msg reply) {
        if (reply == null) {
            return;
        }
        String text = reply.getTextContent();
        if (text != null && text.strip().equalsIgnoreCase("NO_REPLY")) {
            return;
        }
        if (target == null || channelManager == null) {
            log.debug("Announce reply not delivered (no route or no channel manager)");
            return;
        }
        try {
            channelManager.deliver(target, List.of(reply));
        } catch (Exception e) {
            log.warn(
                    "Failed to deliver announce reply: channel={}, to={}",
                    target.channelId(),
                    target.to(),
                    e);
        }
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private HarnessAgent resolveAgent(String agentId) {
        if (agentId != null && agentRegistry.containsKey(agentId)) {
            return agentRegistry.get(agentId);
        }
        HarnessAgent def = mainAgent.get();
        if (def != null) {
            return def;
        }
        return defaultAgentId != null ? agentRegistry.get(defaultAgentId) : null;
    }

    private String resolveOrCreateMainSession(String gateKey, HarnessAgent ha, String userId) {
        return contextKeyToSessionKey.compute(
                gateKey,
                (k, existingSessionKey) -> {
                    if (existingSessionKey != null) {
                        if (isSessionFresh(existingSessionKey)) {
                            return existingSessionKey;
                        }
                        log.info(
                                "AgentStateStore stale, rolling over: gateKey={}, oldSessionKey={}",
                                gateKey,
                                existingSessionKey);
                    }
                    String aid = resolveAgentId(ha);
                    SpawnResult r =
                            sessionAgentManager.registerMainSession(aid, null, gateKey, userId);
                    sessionKeyToGateKey.put(r.sessionKey(), gateKey);
                    sessionKeyToAgentId.put(r.sessionKey(), aid);
                    return r.sessionKey();
                });
    }

    private boolean isSessionFresh(String sessionKey) {
        SessionResetPolicy policy = sessionAgentManager.getConfig().sessionResetPolicy();
        if (policy.mode() == SessionResetPolicy.ResetMode.NEVER) {
            return true;
        }
        return sessionAgentManager
                .getSession(sessionKey)
                .map(
                        entry -> {
                            SessionFreshness f =
                                    SessionFreshnessEvaluator.evaluate(
                                            entry.lastActivityMs(),
                                            System.currentTimeMillis(),
                                            policy);
                            return f.fresh();
                        })
                .orElse(false);
    }

    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.getAgentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    /**
     * Borrows the per-{@code (userId, agentId)} {@link Sandbox} from the registry (if configured)
     * and attaches it to the {@link RuntimeContext} as a {@link SandboxContext#getExternalSandbox()
     * external sandbox}, so {@code SandboxManager.acquire} takes its Priority-1 path and the agent
     * runs against the same container the browser workspace controllers use. No-op when the
     * registry is unconfigured or {@code userId} is missing.
     */
    private void attachUserSandboxContext(
            RuntimeContext.Builder builder, String userId, String agentId) {
        if (userSandboxRegistry == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            Sandbox sb = userSandboxRegistry.borrow(userId, agentId);
            SandboxContext sandboxCtx =
                    SandboxContext.builder()
                            .externalSandbox(sb)
                            .isolationScope(IsolationScope.USER)
                            .build();
            builder.put(SandboxContext.class, sandboxCtx);
        } catch (RuntimeException e) {
            log.warn(
                    "[gateway] Failed to borrow sandbox for user={}, agent={} — agent turn will"
                            + " fall back to the default SandboxContext: {}",
                    userId,
                    agentId,
                    e.getMessage(),
                    e);
        }
    }

    private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        return Mono.defer(turn::get)
                .doOnSubscribe(
                        s -> {
                            try {
                                sessionTurnGate.acquire(gateKey);
                                acquired.set(true);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                        })
                .doFinally(
                        sig -> {
                            if (acquired.get()) {
                                sessionTurnGate.release(gateKey);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
