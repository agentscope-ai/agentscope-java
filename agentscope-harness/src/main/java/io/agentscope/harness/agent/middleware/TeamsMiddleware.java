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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.tool.TeamTool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Injects AgentTeams context into the system prompt and exposes a role-clipped {@link TeamTool}.
 *
 * <p>Optional {@link #wireMessageBus(MessageBus, String)} pushes mailbox wakeups into the session
 * inbox path used by {@link InboxMiddleware}.
 */
public final class TeamsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(TeamsMiddleware.class);

    /** sessionId → middleware, for ASDP TeamEvent → wakeup bridging. */
    private static final ConcurrentHashMap<String, TeamsMiddleware> BY_SESSION =
            new ConcurrentHashMap<>();

    /**
     * "team|role" → middleware, so a TeamEvent that only names the member can reach the session
     * before (or without) a session id being bound.
     */
    private static final ConcurrentHashMap<String, TeamsMiddleware> BY_MEMBER =
            new ConcurrentHashMap<>();

    private final TeamClient teamClient;
    private final TeamContext teamContext;
    private final TeamTool teamTool;

    private volatile MessageBus messageBus;
    private volatile String agentId = "main";
    private volatile String boundSessionId;

    public TeamsMiddleware(TeamClient teamClient, TeamContext teamContext) {
        this.teamClient = Objects.requireNonNull(teamClient, "teamClient");
        this.teamContext = Objects.requireNonNull(teamContext, "teamContext");
        this.teamTool = new TeamTool(teamClient, teamContext);
        String memberKey = memberKey(teamContext.teamName(), teamContext.myRole());
        if (memberKey != null) {
            BY_MEMBER.put(memberKey, this);
        }
    }

    /** Registers this middleware under a runtime session id for TeamEvent delivery. */
    public void bindSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prev = this.boundSessionId;
        if (prev != null && !prev.equals(sessionId)) {
            BY_SESSION.remove(prev, this);
        }
        this.boundSessionId = sessionId;
        BY_SESSION.put(sessionId, this);
    }

    /** Registers {@code sessionId} against an already-built middleware instance. */
    public static void registerSession(String sessionId, TeamsMiddleware middleware) {
        if (middleware != null) {
            middleware.bindSession(sessionId);
        }
    }

    /** Drops a session binding; the role binding survives a session restart. */
    public static void unregisterSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        TeamsMiddleware mw = BY_SESSION.remove(sessionId);
        if (mw != null && sessionId.equals(mw.boundSessionId)) {
            mw.boundSessionId = null;
        }
    }

    /** Wakes the teammate bound to {@code sessionId}. Returns false when unknown. */
    public static boolean wakeupSession(String sessionId) {
        TeamsMiddleware mw = sessionId == null ? null : BY_SESSION.get(sessionId);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(sessionId);
        return true;
    }

    /** Wakes a teammate identified by team + member name. Returns false when unknown. */
    public static boolean wakeupTeamMember(String teamName, String memberName) {
        String key = memberKey(teamName, memberName);
        TeamsMiddleware mw = key == null ? null : BY_MEMBER.get(key);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(mw.boundSessionId);
        return true;
    }

    private static String memberKey(String teamName, String memberName) {
        if (teamName == null || teamName.isBlank() || memberName == null || memberName.isBlank()) {
            return null;
        }
        return teamName + "|" + memberName;
    }

    /** Tools to register on the agent toolkit at build time. */
    public List<Object> getTools() {
        return List.of(teamTool);
    }

    public TeamContext teamContext() {
        return teamContext;
    }

    public TeamClient teamClient() {
        return teamClient;
    }

    /**
     * Wires mailbox wakeups. {@code agentId} is passed to {@link MessageBus#enqueueWakeup} so the
     * gateway can route idle rounds.
     */
    public void wireMessageBus(MessageBus messageBus, String agentId) {
        this.messageBus = messageBus;
        if (agentId != null && !agentId.isBlank()) {
            this.agentId = agentId;
        }
    }

    /**
     * Notifies this teammate session that a team event arrived (message / task assign). Safe to
     * call from adapters or LocalTeamClient hooks.
     */
    public void notifyWakeup(String sessionId) {
        MessageBus bus = this.messageBus;
        final String sid = sessionId == null || sessionId.isBlank() ? boundSessionId : sessionId;
        if (bus == null || sid == null || sid.isBlank()) {
            return;
        }
        try {
            Map<String, Object> hint =
                    Map.of(
                            "type",
                            "team_event",
                            "team",
                            teamContext.teamName() == null ? "" : teamContext.teamName(),
                            "role",
                            teamContext.myRole() == null ? "" : teamContext.myRole());
            bus.inboxPush(sid, hint)
                    .then(bus.enqueueWakeup("", sid, agentId))
                    .subscribe(
                            null,
                            err ->
                                    log.debug(
                                            "team wakeup failed session={}: {}",
                                            sid,
                                            err.toString()));
        } catch (RuntimeException e) {
            log.debug("team wakeup error: {}", e.toString());
        }
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String section = renderTeamSection(teamContext);
        List<Msg> rebuilt = prependToSystemMessage(input.messages(), section);
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    static String renderTeamSection(TeamContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## Agent Team\n\n");
        sb.append("You are participating in Agent Team **")
                .append(nullToEmpty(ctx.teamName()))
                .append("**");
        if (ctx.isLead()) {
            sb.append(" as the **lead**");
        } else {
            sb.append(" as teammate **").append(nullToEmpty(ctx.myRole())).append("**");
        }
        sb.append(".\n\n");
        if (ctx.objective() != null && !ctx.objective().isBlank()) {
            sb.append("**Objective:** ").append(ctx.objective()).append("\n\n");
        }
        if (ctx.members() != null && !ctx.members().isEmpty()) {
            sb.append("**Roster:**\n");
            for (TeamContext.MemberSnapshot m : ctx.members()) {
                sb.append("- ")
                        .append(nullToEmpty(m.name()))
                        .append(" (")
                        .append(nullToEmpty(m.agentRef()))
                        .append(") — ")
                        .append(nullToEmpty(m.status()))
                        .append('\n');
            }
            sb.append('\n');
        }
        sb.append("Use the `team` tool for the shared task board and mailbox.\n");
        if (ctx.availableActions() != null && !ctx.availableActions().isEmpty()) {
            sb.append("Allowed actions for your role: ")
                    .append(String.join(", ", ctx.availableActions()))
                    .append(".\n");
        }
        if (ctx.isLead()) {
            sb.append(
                    "As lead: create and assign tasks, spawn/shutdown teammates when available,"
                            + " approve/reject plans, and call completeTeam when the objective is"
                            + " done.\n");
        } else {
            sb.append(
                    "As worker: claim unassigned unblocked tasks (or start assigned ones), complete"
                        + " them, submit plans when required, and message peers with short text or"
                        + " artifact refs.\n");
        }
        TeamContext.RecoveryContext recovery = ctx.recoveryContext();
        if (recovery != null) {
            sb.append("\n**Recovery context:** you were restarted");
            if (recovery.restartCount() > 0) {
                sb.append(" (restart #").append(recovery.restartCount()).append(')');
            }
            if (recovery.previousSessionId() != null && !recovery.previousSessionId().isBlank()) {
                sb.append("; previous session ").append(recovery.previousSessionId());
            }
            sb.append(".\n");
            if (recovery.interruptedTask() != null) {
                sb.append("- Interrupted task: ")
                        .append(nullToEmpty(recovery.interruptedTask().id()))
                        .append(" — ")
                        .append(nullToEmpty(recovery.interruptedTask().subject()))
                        .append('\n');
            }
            if (recovery.completedTasks() != null && !recovery.completedTasks().isEmpty()) {
                sb.append("- Completed before crash:\n");
                for (TeamContext.CompletedTask t : recovery.completedTasks()) {
                    sb.append("  - ")
                            .append(nullToEmpty(t.id()))
                            .append(": ")
                            .append(nullToEmpty(t.subject()))
                            .append('\n');
                }
            }
            if (recovery.recentMessages() != null && !recovery.recentMessages().isEmpty()) {
                sb.append("- Recent team messages:\n");
                for (TeamContext.RecentMessage m : recovery.recentMessages()) {
                    sb.append("  - ")
                            .append(nullToEmpty(m.from()))
                            .append(": ")
                            .append(nullToEmpty(m.content()))
                            .append('\n');
                }
            }
        }
        return sb.toString();
    }

    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (messages == null || messages.isEmpty() || extra == null || extra.isEmpty()) {
            return messages;
        }
        List<Msg> out = new ArrayList<>(messages.size());
        boolean applied = false;
        for (Msg msg : messages) {
            if (!applied && msg != null && msg.getRole() == MsgRole.SYSTEM) {
                String text = extractText(msg);
                out.add(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(TextBlock.builder().text(text + extra).build())
                                .build());
                applied = true;
            } else {
                out.add(msg);
            }
        }
        if (!applied) {
            List<Msg> withSys = new ArrayList<>(messages.size() + 1);
            withSys.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            withSys.addAll(messages);
            return withSys;
        }
        return out;
    }

    private static String extractText(Msg msg) {
        if (msg.getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        msg.getContent()
                .forEach(
                        b -> {
                            if (b instanceof TextBlock tb && tb.getText() != null) {
                                sb.append(tb.getText());
                            }
                        });
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
