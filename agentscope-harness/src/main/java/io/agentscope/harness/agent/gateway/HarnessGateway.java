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
package io.agentscope.harness.agent.gateway;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default {@link Gateway} implementation. Routes inbound turns by {@link MsgContext#canonicalKey()},
 * serializes concurrent turns per session via {@link SessionTurnGate}, and dispatches to the
 * appropriate registered {@link HarnessAgent}.
 *
 * <p>This implementation provides:
 * <ul>
 *   <li>Multi-agent registry with fallback to a main agent
 *   <li>Stable session mapping: same {@link MsgContext#canonicalKey()} always resolves to the same
 *       session id, giving each logical conversation its own memory
 *   <li>Per-session fair mutual exclusion preventing concurrent turns from racing
 *   <li>Outbound address tracking for proactive delivery (e.g. subagent announces)
 * </ul>
 */
public final class HarnessGateway implements Gateway {

    private static final Logger log = LoggerFactory.getLogger(HarnessGateway.class);

    private final ChannelManager channelManager;
    private final SessionTurnGate sessionTurnGate = new SessionTurnGate();

    private final AtomicReference<HarnessAgent> mainAgent = new AtomicReference<>();
    private final ConcurrentHashMap<String, HarnessAgent> agentRegistry = new ConcurrentHashMap<>();
    private volatile String defaultAgentId = null;

    /** canonicalKey → stable session id */
    private final ConcurrentHashMap<String, String> sessionMap = new ConcurrentHashMap<>();

    /** threadId → exposed subagent session */
    private final ConcurrentHashMap<String, ExposedSession> exposedSessions =
            new ConcurrentHashMap<>();

    /** session id → last outbound address (for proactive push delivery) */
    private final ConcurrentHashMap<String, OutboundAddress> lastRouteBySession =
            new ConcurrentHashMap<>();

    private HarnessGateway(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /** Creates a gateway with a channel manager for outbound delivery. */
    public static HarnessGateway create(ChannelManager channelManager) {
        return new HarnessGateway(Objects.requireNonNull(channelManager, "channelManager"));
    }

    /** Creates a gateway without outbound delivery support. */
    public static HarnessGateway create() {
        return new HarnessGateway(null);
    }

    /** The channel manager for outbound delivery, or null if not configured. */
    public ChannelManager channelManager() {
        return channelManager;
    }

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
     * Returns the agent registered under {@code agentId}, or null if not found. Exposed for
     * introspection (e.g. enumerate an agent's skill repositories).
     */
    public HarnessAgent findAgent(String agentId) {
        if (agentId == null) return null;
        return agentRegistry.get(agentId);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages) {
        return run(context, messages, null);
    }

    @Override
    public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Mono.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before run(...)"));
        }

        String sessionId = sessionMap.computeIfAbsent(gateKey, k -> generateSessionId());

        if (outboundAddress != null) {
            lastRouteBySession.put(sessionId, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", outboundAddress);
        if (ctx.userId() != null && !ctx.userId().isBlank()) {
            rtcBuilder.userId(ctx.userId());
        }
        RuntimeContext runtimeContext = rtcBuilder.build();

        return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    }

    @Override
    public Flux<AgentEvent> runStream(
            MsgContext context, List<Msg> messages, OutboundAddress outboundAddress) {
        MsgContext ctx = context != null ? context : MsgContext.defaultContext();
        String gateKey = ctx.canonicalKey();

        String requestedAgentId = ctx.extra() != null ? ctx.extra().get("agentId") : null;
        HarnessAgent ha = resolveAgent(requestedAgentId);
        if (ha == null) {
            return Flux.error(
                    new IllegalStateException(
                            "HarnessGateway.bindMainAgent must be called before runStream(...)"));
        }

        String sessionId = sessionMap.computeIfAbsent(gateKey, k -> generateSessionId());

        if (outboundAddress != null) {
            lastRouteBySession.put(sessionId, outboundAddress);
        }

        RuntimeContext.Builder rtcBuilder =
                RuntimeContext.builder()
                        .sessionId(sessionId)
                        .put("msgContext", ctx)
                        .put("gateKey", gateKey)
                        .put("outboundAddress", outboundAddress);
        if (ctx.userId() != null && !ctx.userId().isBlank()) {
            rtcBuilder.userId(ctx.userId());
        }
        RuntimeContext runtimeContext = rtcBuilder.build();

        return withGatedStream(gateKey, () -> ha.streamEvents(messages, runtimeContext));
    }

    /**
     * Delivers proactive outbound messages through the channel manager using the session's last
     * recorded outbound address. Returns false if no route or no channel manager is available.
     */
    public boolean deliverToSession(String sessionId, List<Msg> messages) {
        if (channelManager == null || sessionId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        OutboundAddress target = lastRouteBySession.get(sessionId);
        if (target == null) {
            log.debug("Cannot deliver to session {}: no outbound address recorded", sessionId);
            return false;
        }
        channelManager.deliver(target, messages);
        return true;
    }

    // -----------------------------------------------------------------
    //  Exposed subagent routing
    // -----------------------------------------------------------------

    private record ExposedSession(
            String subagentId,
            String agentId,
            String sessionId,
            Agent agent,
            OutboundAddress replyTo) {}

    /**
     * Exposes a spawned subagent as a user-addressable entry point. Returns the assigned
     * subagentId.
     *
     * @param agentId the subagent type identifier
     * @param sessionId the session id assigned to the subagent
     * @param agent the agent instance
     * @param replyTo the outbound address for delivering replies; may be null
     * @return the subagentId handle for direct addressing
     */
    public String exposeSubagent(
            String agentId, String sessionId, Agent agent, OutboundAddress replyTo) {
        Objects.requireNonNull(agent, "agent");
        String subagentId = "sub-" + UUID.randomUUID().toString().substring(0, 8);
        exposedSessions.put(
                subagentId, new ExposedSession(subagentId, agentId, sessionId, agent, replyTo));
        log.debug(
                "Exposed subagent agentId={} sessionId={} as subagentId={}",
                agentId,
                sessionId,
                subagentId);
        return subagentId;
    }

    /** Revokes a previously exposed subagent, removing it from routing. */
    public void revokeSubagent(String subagentId) {
        if (subagentId != null) {
            exposedSessions.remove(subagentId);
        }
    }

    @Override
    public Mono<Msg> runSubagent(String subagentId, List<Msg> messages) {
        ExposedSession session = exposedSessions.get(subagentId);
        if (session == null) {
            return Mono.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedTurn(gateKey, () -> invokeExposedAgent(session.agent(), messages, rtc))
                .doOnNext(
                        reply -> {
                            if (channelManager != null
                                    && session.replyTo() != null
                                    && reply != null) {
                                channelManager.deliver(session.replyTo(), List.of(reply));
                            }
                        });
    }

    private Mono<Msg> invokeExposedAgent(Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.call(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.call(messages, ctx);
        }
        return agent.call(messages);
    }

    // -----------------------------------------------------------------
    //  Internal
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

    private static String resolveAgentId(HarnessAgent ha) {
        String id = ha != null ? ha.getAgentId() : null;
        return (id != null && !id.isBlank()) ? id : "main";
    }

    private static String generateSessionId() {
        return "gw-" + UUID.randomUUID();
    }

    @Override
    public Flux<AgentEvent> runSubagentStream(String subagentId, List<Msg> messages) {
        ExposedSession session = exposedSessions.get(subagentId);
        if (session == null) {
            return Flux.error(new IllegalArgumentException("Unknown subagentId: " + subagentId));
        }

        RuntimeContext rtc = RuntimeContext.builder().sessionId(session.sessionId()).build();
        String gateKey = "subagent:" + subagentId;

        return withGatedStream(gateKey, () -> streamExposedAgent(session.agent(), messages, rtc));
    }

    private Flux<AgentEvent> streamExposedAgent(
            Agent agent, List<Msg> messages, RuntimeContext ctx) {
        if (agent instanceof HarnessAgent ha) {
            return ha.streamEvents(messages, ctx);
        }
        if (agent instanceof ReActAgent ra) {
            return ra.streamEvents(messages, ctx);
        }
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent type "
                                + agent.getClass().getName()
                                + " does not support streaming"));
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

    private Flux<AgentEvent> withGatedStream(String gateKey, Supplier<Flux<AgentEvent>> stream) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        return Flux.defer(stream::get)
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
