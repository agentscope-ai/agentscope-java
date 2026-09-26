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
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamTask;
import io.agentscope.harness.agent.team.TeamWakeups;
import io.agentscope.harness.agent.tool.TeamTool;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    private static final Duration BOARD_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Roster name of the lead, assigned by the control plane when a team starts. */
    private static final String LEAD = "lead";

    /** Longest closing message recorded as a failure reason; a summary, not a transcript. */
    private static final int MAX_CLOSING_REASON = 4000;

    /** Longest per-task result echoed in the lead's board digest. */
    private static final int MAX_DIGEST_RESULT = 600;

    /** Board as of this turn's start, rendered for the lead. Empty for workers and empty boards. */
    private volatile String boardDigest = "";

    /** Notices buffered because no session was bound (or the inbox push failed). */
    private final Queue<String> pendingNotices = new ConcurrentLinkedQueue<>();

    private volatile MessageBus messageBus;
    private volatile String agentId = "main";
    private volatile String boundSessionId;

    static {
        // Lets TeamClient implementations wake a member by name; they cannot
        // resolve runtime session ids on their own.
        TeamWakeups.register(TeamsMiddleware::wakeupTeamMember);
    }

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

    /**
     * Drops every registry entry for a session that is gone for good, so a later team reusing the
     * same team and member names cannot wake this dead session. Call it only when the session was
     * deleted, not when it merely went idle.
     *
     * <p>The member entry is removed only while it still points at this instance: a member that was
     * rebuilt under a new session has already replaced it.
     */
    public static void unregisterSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        TeamsMiddleware mw = BY_SESSION.remove(sessionId);
        if (mw == null) {
            return;
        }
        if (sessionId.equals(mw.boundSessionId)) {
            mw.boundSessionId = null;
        }
        String memberKey = memberKey(mw.teamContext.teamName(), mw.teamContext.myRole());
        if (memberKey != null) {
            BY_MEMBER.remove(memberKey, mw);
        }
    }

    /** Wakes the teammate bound to {@code sessionId}. Returns false when unknown. */
    public static boolean wakeupSession(String sessionId) {
        return wakeupSession(sessionId, null);
    }

    /** Wakes the teammate bound to {@code sessionId}, injecting {@code notice} into its next turn. */
    public static boolean wakeupSession(String sessionId, String notice) {
        TeamsMiddleware mw = sessionId == null ? null : BY_SESSION.get(sessionId);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(sessionId, notice);
        return true;
    }

    /** Wakes a teammate identified by team + member name. Returns false when unknown. */
    public static boolean wakeupTeamMember(String teamName, String memberName) {
        return wakeupTeamMember(teamName, memberName, null);
    }

    /** Wakes a teammate by team + member name, injecting {@code notice} into its next turn. */
    public static boolean wakeupTeamMember(String teamName, String memberName, String notice) {
        String key = memberKey(teamName, memberName);
        TeamsMiddleware mw = key == null ? null : BY_MEMBER.get(key);
        if (mw == null) {
            return false;
        }
        mw.notifyWakeup(mw.boundSessionId, notice);
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

    /** Notifies this teammate session that a team event arrived (message / task settled). */
    public void notifyWakeup(String sessionId) {
        notifyWakeup(sessionId, null);
    }

    /**
     * Notifies this teammate session, injecting {@code notice} so the woken turn starts with the
     * event content instead of an empty prompt. Safe to call from adapters or team client hooks.
     *
     * <p>When no session is bound yet the notice is buffered and injected on the next reasoning
     * step, so events that race session binding are not lost.
     */
    public void notifyWakeup(String sessionId, String notice) {
        MessageBus bus = this.messageBus;
        final String sid = sessionId == null || sessionId.isBlank() ? boundSessionId : sessionId;
        final String text = renderNotice(notice);
        if (bus == null || sid == null || sid.isBlank()) {
            pendingNotices.add(text);
            return;
        }
        try {
            Map<String, Object> hint =
                    Map.of(
                            "type",
                            "hint",
                            "id",
                            UUID.randomUUID().toString().replace("-", ""),
                            "hint",
                            text,
                            "source",
                            "team_event");
            bus.inboxPush(sid, hint)
                    .then(bus.enqueueWakeup("", sid, agentId))
                    .subscribe(
                            null,
                            err -> {
                                log.debug("team wakeup failed session={}: {}", sid, err.toString());
                                pendingNotices.add(text);
                            });
        } catch (RuntimeException e) {
            log.debug("team wakeup error: {}", e.toString());
            pendingNotices.add(text);
        }
    }

    private String renderNotice(String notice) {
        String team = teamContext.teamName() == null ? "" : teamContext.teamName();
        String role = teamContext.myRole() == null ? "" : teamContext.myRole();
        String body =
                notice == null || notice.isBlank()
                        ? "A team event arrived. Call listMessages and listTasks to see the current"
                                + " board and mailbox."
                        : notice;
        return "<system-notification>Team event for "
                + role
                + " in team "
                + team
                + ":\n\n"
                + body
                + "</system-notification>";
    }

    /**
     * Gives the lead the board as it stands when its turn starts, and settles a worker's tasks when
     * its turn ends. The lead's tasks are left alone: it answers to a user who can pick the
     * conversation back up, so its turn ending is not the end of its execution.
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        if (teamContext.isLead()) {
            return Mono.fromRunnable(this::refreshBoardDigest)
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(
                            e -> {
                                log.debug("board digest refresh failed: {}", e.toString());
                                return Mono.empty();
                            })
                    .thenMany(Flux.defer(() -> next.apply(input)));
        }
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        return next.apply(input)
                .concatWith(
                        Mono.<AgentEvent>fromRunnable(() -> settleOwnedTasks(agent, rc))
                                .subscribeOn(Schedulers.boundedElastic())
                                .onErrorResume(
                                        e -> {
                                            log.debug(
                                                    "team turn-end settlement failed: {}",
                                                    e.toString());
                                            return Mono.empty();
                                        }));
    }

    private void settleOwnedTasks(Agent agent, RuntimeContext rc) {
        settleOwnedTasks(lastAssistantText(agent, rc));
    }

    /**
     * Fails every task this member was still executing, using its closing message as the reason.
     *
     * <p>A worker's turn is its whole execution: it runs when woken and stops when the turn ends,
     * with no human to answer it and no later round of its own. So a task it has not settled by then
     * is not being worked on by anyone, and leaving it in progress makes the board claim otherwise
     * while the lead waits for a result that is never coming. Failing it records what the worker
     * last said and notifies the lead, who decides whether to answer, retry or drop it.
     */
    void settleOwnedTasks(String closing) {
        String me = teamContext.myRole();
        if (me == null || me.isBlank() || LEAD.equals(me)) {
            return;
        }
        String reason =
                closing == null || closing.isEmpty()
                        ? "Ended its turn without reporting a result."
                        : closing;
        for (TeamTask task : tasksInProgressBy(me)) {
            try {
                teamClient
                        .failTask(
                                teamContext.resolvedNamespace(),
                                teamContext.teamName(),
                                task.taskId(),
                                reason)
                        .block(BOARD_PROBE_TIMEOUT);
            } catch (RuntimeException e) {
                log.debug("settling task {} at turn end failed: {}", task.taskId(), e.toString());
            }
        }
    }

    /**
     * Tasks this member was executing during the turn. A task merely assigned to it is still pending
     * and waiting for its own wakeup, so it is not something this turn failed to finish.
     */
    private List<TeamTask> tasksInProgressBy(String me) {
        List<TeamTask> tasks =
                teamClient
                        .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                        .block(BOARD_PROBE_TIMEOUT);
        if (tasks == null) {
            return List.of();
        }
        return tasks.stream()
                .filter(t -> me.equals(t.owner()) && TeamTask.IN_PROGRESS.equals(t.state()))
                .toList();
    }

    private static String lastAssistantText(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return "";
        }
        List<Msg> messages = state.getContext();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg == null || msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            String text = extractText(msg).trim();
            if (text.isEmpty()) {
                continue;
            }
            return text.length() > MAX_CLOSING_REASON
                    ? text.substring(0, MAX_CLOSING_REASON) + "… (truncated)"
                    : text;
        }
        return "";
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        String section = renderTeamSection(teamContext);
        String buffered = drainPendingNotices();
        List<Msg> rebuilt =
                prependToSystemMessage(input.messages(), section + boardDigest + buffered);
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    /** Reads the board once per turn, so every step of the turn renders the same digest. */
    private void refreshBoardDigest() {
        List<TeamTask> tasks;
        try {
            tasks =
                    teamClient
                            .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                            .block(BOARD_PROBE_TIMEOUT);
        } catch (RuntimeException e) {
            log.debug("board probe for digest failed: {}", e.toString());
            return;
        }
        boardDigest = renderBoardDigest(tasks, teamContext.myRole());
    }

    /**
     * Renders the whole board for the lead: what every task is, who owns it, and what the settled
     * ones produced.
     *
     * <p>Without this the lead only ever learns about tasks one notification at a time and has to
     * poll for the rest, so it loses track of what it took on itself and cannot tell that the
     * objective is finished. The results are included because the final summary is written from
     * them.
     */
    static String renderBoardDigest(List<TeamTask> tasks, String me) {
        if (tasks == null || tasks.isEmpty()) {
            return "";
        }
        List<TeamTask> mineOpen = new ArrayList<>();
        boolean allSettled = true;
        StringBuilder sb = new StringBuilder("\n**Board** (start of this turn):\n");
        for (TeamTask t : tasks) {
            boolean terminal = TeamTask.isTerminal(t.state());
            allSettled &= terminal;
            String owner = t.owner() == null || t.owner().isBlank() ? "unassigned" : t.owner();
            sb.append("- ")
                    .append(t.taskId())
                    .append(" — ")
                    .append(nullToEmpty(t.subject()))
                    .append(" [")
                    .append(nullToEmpty(t.state()))
                    .append(", ")
                    .append(owner)
                    .append(']');
            if (terminal && t.result() != null && !t.result().isBlank()) {
                sb.append("\n  ").append(abbreviate(t.result(), MAX_DIGEST_RESULT));
            }
            sb.append('\n');
            if (!terminal && me != null && me.equals(t.owner())) {
                mineOpen.add(t);
            }
        }
        if (!mineOpen.isEmpty()) {
            sb.append("\n**You own ")
                    .append(mineOpen.size())
                    .append(" unsettled task(s): ")
                    .append(String.join(", ", mineOpen.stream().map(TeamTask::taskId).toList()))
                    .append(".** Nobody else will do them. Do the work in this turn and call")
                    .append(" completeTask with the result, hand each one to a teammate with")
                    .append(" assignTask, or call failTask — do not end the turn still owning")
                    .append(" them.\n");
        }
        if (allSettled) {
            sb.append(
                    "\n**Every task is settled.** Write the consolidated answer to the objective"
                            + " now, using the results above, and then call completeTeam. Do not"
                            + " create more tasks unless something is genuinely missing.\n");
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        String flat = text.strip().replace("\n", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    /**
     * Reports whether the shared board still has non-terminal tasks, so blocking waits know that
     * teammates are still working even when this session owns no background subagent tasks.
     */
    public boolean hasOutstandingTeamWork() {
        try {
            List<TeamTask> tasks =
                    teamClient
                            .listTasks(teamContext.resolvedNamespace(), teamContext.teamName())
                            .block(BOARD_PROBE_TIMEOUT);
            if (tasks == null) {
                return false;
            }
            return tasks.stream().anyMatch(t -> !TeamTask.isTerminal(t.state()));
        } catch (RuntimeException e) {
            log.debug("team board probe failed: {}", e.toString());
            return false;
        }
    }

    /** Consumes notices that could not be delivered through the inbox. */
    String drainPendingNotices() {
        if (pendingNotices.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String notice;
        while ((notice = pendingNotices.poll()) != null) {
            sb.append('\n').append(notice).append('\n');
        }
        return sb.toString();
    }

    /** Renders the system-prompt team section (also used by BYO {@code team_join} kickoff). */
    public static String renderTeamSection(TeamContext ctx) {
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
            sb.append(
                    "**Roster** (use the member name in bold for every owner / member / to"
                            + " argument — never the agent name):\n");
            for (TeamContext.MemberSnapshot m : ctx.members()) {
                sb.append("- **")
                        .append(nullToEmpty(m.name()))
                        .append("** — agent ")
                        .append(nullToEmpty(m.agentRef()))
                        .append(", ")
                        .append(nullToEmpty(m.status()))
                        .append('\n');
            }
            sb.append('\n');
        }
        sb.append(
                "Coordinate **only** via registered tools whose names match the actions below (for"
                    + " example `createTask`, `sendMessage`) or the unified `team` tool with"
                    + " parameter `action`. Never invent XML tags, ✿TEAM✿ markers, or prose-only"
                    + " fake calls — those are not executed.\n");
        sb.append(
                "When you reply to a teammate, address them by their roster name — sendMessage to"
                        + " your own name is rejected, and it would only wake you again instead of"
                        + " reaching them.\n");
        if (ctx.availableActions() != null && !ctx.availableActions().isEmpty()) {
            sb.append("Allowed tool / action names for your role: ")
                    .append(String.join(", ", ctx.availableActions()))
                    .append(".\n");
        }
        if (ctx.isLead()) {
            sb.append(
                    "As lead: create and assign tasks, spawn/shutdown teammates when available,"
                            + " approve/reject plans, and call completeTeam when the objective is"
                            + " done.\n");
            sb.append(
                    "Teammate outcomes reach you automatically: every completeTask / failTask and"
                        + " every member failure arrives as a team notification that wakes you, and"
                        + " the board below is refreshed at the start of each of your turns. So do"
                        + " not busy-poll listTasks in a loop — if you have nothing to do until a"
                        + " teammate reports back, call wait_async_results, or end your turn and"
                        + " you will be woken. Use listTasks only for detail the digest omits, such"
                        + " as a full result.\n");
            sb.append(
                    "Never end a turn owning a task you have not settled. Announcing that you will"
                        + " work on something is not work: there is no later turn unless a"
                        + " notification wakes you. Either do the work now and call completeTask,"
                        + " or assign the task to a teammate and wait for their report.\n");
            sb.append(
                    "You own the final answer. When every task is settled, do not just relay the"
                            + " individual results: reason over the whole board, resolve"
                            + " disagreements between teammates, state the conclusion the objective"
                            + " asked for, and only then call completeTeam.\n");
        } else {
            sb.append(
                    "As worker: call listClaimableTasks (or listTasks) first — it includes tasks"
                        + " assigned to you, not only the open board. Then claimTask before product"
                        + " tools. You may omit expected_version on claimTask.\n");
            sb.append(
                    "An empty board is normal — it usually means the lead has not assigned you"
                        + " anything yet. Do not start solving the team objective on your own and"
                        + " do not poll in a loop: just end your turn. Every assignment arrives as"
                        + " a team notification that wakes you with the task id and"
                        + " description.\n");
            sb.append(
                    "Always close the loop: call completeTask with a result summary when you"
                        + " finish, or failTask with a reason when you cannot. Both notify the lead"
                        + " automatically — the result text you pass is what the lead sees, so make"
                        + " it self-contained. Never finish a task silently, and use sendMessage"
                        + " for anything the lead needs before the task is done.\n");
            sb.append(
                    "**This turn is your whole execution, and there is no human in this session.**"
                        + " Nobody reads your prose, so never ask the user a question, request"
                        + " documents or credentials, or say you will continue later. When a tool"
                        + " is unavailable, a credential is missing, or the request is ambiguous,"
                        + " either proceed with what you can and report the gap in your"
                        + " completeTask result, or call failTask with the reason. Any task you"
                        + " still own when the turn ends is failed for you, with your closing"
                        + " message recorded as the reason — so settle it yourself and keep control"
                        + " of what the lead reads.\n");
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
