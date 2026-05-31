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
package io.agentscope.spring.boot.admin.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.AgentState;
import io.agentscope.spring.boot.admin.dto.AgentTaskView;
import io.agentscope.spring.boot.admin.dto.CompactRequest;
import io.agentscope.spring.boot.admin.dto.CompactResponse;
import io.agentscope.spring.boot.admin.dto.MessageView;
import io.agentscope.spring.boot.admin.dto.PlanModeView;
import io.agentscope.spring.boot.admin.properties.AdminProperties;
import io.agentscope.spring.boot.admin.registry.AgentRegistry;
import io.agentscope.spring.boot.admin.snapshot.AgentStateRestorer;
import io.agentscope.spring.boot.admin.snapshot.SnapshotStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Business logic for the per-session data-plane admin actions.
 *
 * <p>Designed so that the REST controller is a thin shell: it parses the request, defers to one
 * method here, and serializes the result. All blocking I/O (Session reads, JSON serialization) is
 * pushed off the request thread via {@link Schedulers#boundedElastic()}.
 */
public final class SessionOperations {

    private final AgentRegistry registry;
    private final SummarizationStrategy summarizer;
    private final AdminProperties properties;
    private final SnapshotStore snapshots;

    public SessionOperations(
            AgentRegistry registry,
            SummarizationStrategy summarizer,
            AdminProperties properties,
            SnapshotStore snapshots) {
        this.registry = registry;
        this.summarizer = summarizer;
        this.properties = properties;
        this.snapshots = snapshots;
    }

    /** Read-only listing of known session keys from {@link Session#listSessionKeys()}. */
    public Mono<List<String>> listSessions(Session session) {
        if (session == null) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(
                        () -> {
                            Set<?> keys = session.listSessionKeys();
                            List<String> out = new ArrayList<>(keys.size());
                            for (Object k : keys) {
                                out.add(String.valueOf(k));
                            }
                            Collections.sort(out);
                            return out;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Return the in-memory message list of a registered agent. */
    public Mono<List<MessageView>> listMessages(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            List<Msg> ctx = react.getAgentState().getContext();
                            List<MessageView> out = new ArrayList<>(ctx.size());
                            for (Msg msg : ctx) {
                                out.add(MessageView.of(msg));
                            }
                            return out;
                        });
    }

    /** Render the conversation as a Markdown transcript. */
    public Mono<String> exportMarkdown(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            AgentState state = react.getAgentState();
                            StringBuilder sb = new StringBuilder();
                            sb.append("# Session ").append(state.getSessionId()).append("\n\n");
                            if (state.getSummary() != null && !state.getSummary().isBlank()) {
                                sb.append("## Rolling summary\n\n")
                                        .append(state.getSummary())
                                        .append("\n\n");
                            }
                            sb.append("## Messages\n\n");
                            for (Msg msg : state.getContext()) {
                                String role =
                                        msg.getRole() == null
                                                ? "?"
                                                : msg.getRole().name().toLowerCase();
                                sb.append("### ")
                                        .append(role)
                                        .append(" — ")
                                        .append(msg.getName() == null ? "" : msg.getName())
                                        .append("\n\n");
                                String text;
                                try {
                                    text = msg.getTextContent();
                                } catch (RuntimeException e) {
                                    text = "";
                                }
                                sb.append(text == null ? "" : text).append("\n\n");
                            }
                            return sb.toString();
                        });
    }

    /** Dump the full {@link AgentState} JSON for a session. */
    public Mono<String> dumpStateJson(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId).map(react -> react.getAgentState().toJson());
    }

    /** Cooperatively interrupt the agent. */
    public Mono<Void> abort(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId)
                .doOnNext(Agent::interrupt)
                .then();
    }

    /**
     * Compact a session: summarize the older portion of the context, truncate to the last
     * {@code keepLast} messages, and persist via the agent's {@link Session}.
     *
     * <p>Returns metrics describing the change. Falls back gracefully when summarization fails —
     * the prior summary is preserved and the context is left untouched.
     */
    public Mono<CompactResponse> compact(String agentIdOrSessionId, CompactRequest request) {
        CompactRequest req = request == null ? CompactRequest.defaults() : request;
        int keepLast =
                req.keepLastMessages() == null
                        ? properties.getCompactKeepLastMessages()
                        : Math.max(0, req.keepLastMessages());
        boolean replace = req.replaceSummary() != null && req.replaceSummary();

        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            AgentState state = react.getAgentState();
                            List<Msg> context = state.contextMutable();
                            int before = context.size();
                            String previousSummary =
                                    state.getSummary() == null ? "" : state.getSummary();
                            int summaryBefore = previousSummary.length();
                            if (before <= keepLast) {
                                return Mono.just(
                                        new CompactResponse(
                                                state.getSessionId(),
                                                before,
                                                before,
                                                summaryBefore,
                                                summaryBefore));
                            }

                            List<Msg> toFold =
                                    new ArrayList<>(context.subList(0, before - keepLast));
                            // Snapshot BEFORE we touch the live state, so undo can revert.
                            snapshots.push(state.getSessionId(), state.toJson());
                            return summarizer
                                    .summarize(react.getModel(), previousSummary, toFold)
                                    .defaultIfEmpty(previousSummary)
                                    .flatMap(
                                            newSummary -> {
                                                String merged =
                                                        replace || previousSummary.isBlank()
                                                                ? newSummary
                                                                : previousSummary
                                                                        + "\n\n"
                                                                        + newSummary;
                                                state.setSummary(merged);
                                                // truncate in-place
                                                List<Msg> kept =
                                                        new ArrayList<>(
                                                                context.subList(
                                                                        before - keepLast, before));
                                                context.clear();
                                                context.addAll(kept);
                                                return persist(react, state)
                                                        .thenReturn(
                                                                new CompactResponse(
                                                                        state.getSessionId(),
                                                                        before,
                                                                        context.size(),
                                                                        summaryBefore,
                                                                        merged.length()));
                                            });
                        });
    }

    /**
     * Restore the agent's state to the snapshot stored before the most recent mutation. Returns
     * empty (not an error) if there is no undo history.
     */
    public Mono<UndoResult> undo(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            String sessionId = react.getAgentState().getSessionId();
                            String currentJson = react.getAgentState().toJson();
                            Optional<String> snap = snapshots.undo(sessionId, currentJson);
                            if (snap.isEmpty()) {
                                return Mono.just(
                                        new UndoResult(
                                                sessionId,
                                                false,
                                                snapshots.undoDepth(sessionId),
                                                snapshots.redoDepth(sessionId)));
                            }
                            AgentStateRestorer.restore(react.getAgentState(), snap.get());
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            new UndoResult(
                                                    sessionId,
                                                    true,
                                                    snapshots.undoDepth(sessionId),
                                                    snapshots.redoDepth(sessionId)));
                        });
    }

    /** Reverse a previous {@link #undo(String)}. */
    public Mono<UndoResult> redo(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            String sessionId = react.getAgentState().getSessionId();
                            String currentJson = react.getAgentState().toJson();
                            Optional<String> snap = snapshots.redo(sessionId, currentJson);
                            if (snap.isEmpty()) {
                                return Mono.just(
                                        new UndoResult(
                                                sessionId,
                                                false,
                                                snapshots.undoDepth(sessionId),
                                                snapshots.redoDepth(sessionId)));
                            }
                            AgentStateRestorer.restore(react.getAgentState(), snap.get());
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            new UndoResult(
                                                    sessionId,
                                                    true,
                                                    snapshots.undoDepth(sessionId),
                                                    snapshots.redoDepth(sessionId)));
                        });
    }

    /** Outcome of an undo/redo invocation. */
    public record UndoResult(
            String sessionId, boolean restored, int undoDepth, int redoDepth) {}

    // ------------------------------------------------------------------------
    // Plan mode
    // ------------------------------------------------------------------------

    private static final String PLAN_MIDDLEWARE_CLASS =
            "io.agentscope.harness.agent.middleware.PlanModeMiddleware";

    /** Read the current plan-mode state of a session. */
    public Mono<PlanModeView> planState(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react ->
                                PlanModeView.of(
                                        react.getAgentState().getSessionId(),
                                        react.getAgentState().getPlanModeContext(),
                                        hasPlanModeMiddleware(react)));
    }

    /** Switch the agent into plan mode. Persists via the same hook ReActAgent uses internally. */
    public Mono<PlanModeView> enterPlanMode(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            // Snapshot for undo BEFORE flipping the flag.
                            snapshots.push(
                                    react.getAgentState().getSessionId(),
                                    react.getAgentState().toJson());
                            react.enterPlanMode();
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            PlanModeView.of(
                                                    react.getAgentState().getSessionId(),
                                                    react.getAgentState().getPlanModeContext(),
                                                    hasPlanModeMiddleware(react)));
                        });
    }

    /** Exit plan mode. */
    public Mono<PlanModeView> exitPlanMode(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .flatMap(
                        react -> {
                            snapshots.push(
                                    react.getAgentState().getSessionId(),
                                    react.getAgentState().toJson());
                            react.exitPlanMode();
                            return persist(react, react.getAgentState())
                                    .thenReturn(
                                            PlanModeView.of(
                                                    react.getAgentState().getSessionId(),
                                                    react.getAgentState().getPlanModeContext(),
                                                    hasPlanModeMiddleware(react)));
                        });
    }

    // ------------------------------------------------------------------------
    // Per-session in-AgentState task list (TodoWrite-style work items)
    // ------------------------------------------------------------------------

    /** List the per-{@link io.agentscope.core.state.AgentState} task records of a session. */
    public Mono<List<AgentTaskView>> listAgentTasks(String agentIdOrSessionId) {
        return resolveReact(agentIdOrSessionId)
                .map(
                        react -> {
                            var tasksCtx = react.getAgentState().getTasksContext();
                            if (tasksCtx == null) return List.<AgentTaskView>of();
                            List<AgentTaskView> out = new ArrayList<>();
                            for (var t : tasksCtx.getTasks()) {
                                AgentTaskView view = AgentTaskView.of(t);
                                if (view != null) out.add(view);
                            }
                            return out;
                        });
    }

    private static boolean hasPlanModeMiddleware(ReActAgent react) {
        try {
            return react.getMiddlewares().stream()
                    .anyMatch(m -> PLAN_MIDDLEWARE_CLASS.equals(m.getClass().getName()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------------

    private Mono<Agent> resolveAgent(String agentIdOrSessionId) {
        return Mono.defer(
                () -> {
                    // First try direct lookup by agentId.
                    return registry.find(agentIdOrSessionId)
                            .map(Mono::just)
                            .orElseGet(
                                    () -> {
                                        // Fall back: scan registered agents for matching sessionId
                                        // on their AgentState — this keeps callers from needing to
                                        // know whether they hold an agentId or a sessionId.
                                        for (Agent agent : registry.list()) {
                                            if (agent instanceof ReActAgent react) {
                                                AgentState state = react.getAgentState();
                                                if (state != null
                                                        && agentIdOrSessionId.equals(
                                                                state.getSessionId())) {
                                                    return Mono.just(agent);
                                                }
                                            }
                                        }
                                        return Mono.error(
                                                new NoSuchElementException(
                                                        "No agent or session found for id="
                                                                + agentIdOrSessionId));
                                    });
                });
    }

    private Mono<ReActAgent> resolveReact(String agentIdOrSessionId) {
        return resolveAgent(agentIdOrSessionId)
                .flatMap(
                        a -> {
                            if (a instanceof ReActAgent react) {
                                return Mono.just(react);
                            }
                            return Mono.error(
                                    new IllegalStateException(
                                            "Admin op only supported on ReActAgent, found: "
                                                    + a.getClass().getSimpleName()));
                        });
    }

    private Mono<Void> persist(ReActAgent react, AgentState state) {
        Session session = react.getSession();
        if (session == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> session.save(react.getSessionKey(), "agent_state", state))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
