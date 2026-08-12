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
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default BYO {@link TeamSessionStarter} for a long-lived {@link HarnessAgent}.
 *
 * <p>On {@code team_join}: parse {@link TeamContext}, optionally register the CP session id with
 * the host gateway, bind {@link TeamsMiddleware} for TeamEvent wakeup, register the role-clipped
 * {@code team} tool, and fire an async kickoff turn (does not block the join HTTP response on the
 * full agent run).
 *
 * <p>Limitation: the shared agent toolkit keeps one {@code team} tool — last join wins. Prefer one
 * active team session per process, or rebuild per session (Managed path).
 */
public final class HarnessTeamSessionStarter implements TeamSessionStarter {

    private static final Logger LOG = Logger.getLogger(HarnessTeamSessionStarter.class.getName());
    private static final ObjectMapper MAPPER = ControlPlaneHttpClient.mapper();

    private final Supplier<HarnessAgent> agent;
    private final TeamClient teamClient;
    private final BiConsumer<String, String> externalSessionRegistrar;
    private final MessageBus messageBus;
    private final ConcurrentHashMap<String, TeamsMiddleware> bySession = new ConcurrentHashMap<>();

    /**
     * @param agent live HarnessAgent supplier (usually {@code bootstrap::mainAgent})
     * @param teamClient control-plane or local {@link TeamClient}
     * @param externalSessionRegistrar optional {@code (sessionId, gateKey) ->
     *     gateway.registerExternalSession(...)} ; may be {@code null} when the host calls the agent
     *     with {@code RuntimeContext#sessionId()} directly
     * @param messageBus optional bus for TeamEvent → inbox wakeup; may be {@code null}
     */
    public HarnessTeamSessionStarter(
            Supplier<HarnessAgent> agent,
            TeamClient teamClient,
            BiConsumer<String, String> externalSessionRegistrar,
            MessageBus messageBus) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.teamClient = Objects.requireNonNull(teamClient, "teamClient");
        this.externalSessionRegistrar = externalSessionRegistrar;
        this.messageBus = messageBus;
    }

    /** Convenience: no gateway registrar / message bus. */
    public HarnessTeamSessionStarter(Supplier<HarnessAgent> agent, TeamClient teamClient) {
        this(agent, teamClient, null, null);
    }

    @Override
    public Mono<Void> join(String sessionId, byte[] params) {
        return Mono.fromCallable(
                        () -> {
                            if (sessionId == null || sessionId.isBlank()) {
                                throw new IllegalArgumentException("sessionId required");
                            }
                            TeamContext ctx = parseContext(params);
                            String gateKey =
                                    "team:"
                                            + nullToEmpty(ctx.teamName())
                                            + ":"
                                            + nullToEmpty(ctx.myRole());
                            if (externalSessionRegistrar != null) {
                                externalSessionRegistrar.accept(sessionId, gateKey);
                            }

                            TeamsMiddleware mw = new TeamsMiddleware(teamClient, ctx);
                            HarnessAgent harness = agent.get();
                            if (messageBus != null) {
                                String agentId = harness.getAgentId();
                                mw.wireMessageBus(
                                        messageBus,
                                        agentId != null && !agentId.isBlank() ? agentId : "main");
                            }
                            mw.bindSession(sessionId);
                            bySession.put(sessionId, mw);

                            Toolkit toolkit = harness.getToolkit();
                            if (toolkit != null) {
                                toolkit.removeTool("team");
                                for (Object tool : mw.getTools()) {
                                    toolkit.registerTool(tool);
                                }
                            }

                            kickoffAsync(harness, sessionId, ctx);
                            return (Void) null;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> leave(String sessionId) {
        return Mono.fromRunnable(
                        () -> {
                            bySession.remove(sessionId);
                            TeamsMiddleware.unregisterSession(sessionId);
                            Toolkit toolkit = agent.get().getToolkit();
                            if (toolkit != null) {
                                toolkit.removeTool("team");
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void kickoffAsync(HarnessAgent harness, String sessionId, TeamContext ctx) {
        String body =
                "Team session started.\n"
                        + TeamsMiddleware.renderTeamSection(ctx)
                        + "\nUse the `team` tool to coordinate (list/claim tasks, messages).";
        Msg kickoff = Msg.builder().role(MsgRole.USER).textContent(body).build();
        RuntimeContext rc = RuntimeContext.builder().sessionId(sessionId).build();
        harness.call(kickoff, rc)
                .subscribe(
                        ignored ->
                                LOG.log(
                                        Level.INFO,
                                        "team kickoff finished session={0} team={1} role={2}",
                                        new Object[] {sessionId, ctx.teamName(), ctx.myRole()}),
                        err ->
                                LOG.log(
                                        Level.WARNING,
                                        "team kickoff failed session=" + sessionId,
                                        err));
    }

    private static TeamContext parseContext(byte[] params) {
        if (params == null || params.length == 0) {
            throw new IllegalArgumentException("team_join params (TeamContext JSON) required");
        }
        try {
            return MAPPER.readValue(params, TeamContext.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid TeamContext JSON", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
