/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Default Java external-agent executor for ASDP AgentTask deliveries. */
public final class HarnessAgentTaskStarter implements AgentTaskStarter {

    private static final Logger LOG = Logger.getLogger(HarnessAgentTaskStarter.class.getName());

    private final Supplier<HarnessAgent> agent;
    private final CollaborationClient collaboration;
    private final Set<String> acceptedEvents = ConcurrentHashMap.newKeySet();

    public HarnessAgentTaskStarter(
            Supplier<HarnessAgent> agent, CollaborationClient collaboration) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.collaboration = Objects.requireNonNull(collaboration, "collaboration");
    }

    @Override
    public Mono<Void> start(AgentTaskAssignment assignment) {
        return Mono.fromRunnable(() -> execute(assignment))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private void execute(AgentTaskAssignment assignment) {
        require(assignment.agentTaskId(), "agentTaskId");
        require(assignment.taskToken(), "taskToken");
        String eventKey = assignment.attemptId() + ":" + assignment.generation();
        if (!acceptedEvents.add(eventKey)) {
            return;
        }

        long version = 0;
        try {
            JsonNode envelope =
                    collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
            version = envelope.path("task").path("version").asLong();
            List<String> inputIds = inputIds(envelope);
            if (!inputIds.isEmpty()) {
                collaboration.acknowledge(
                        assignment.agentTaskId(), assignment.taskToken(), inputIds);
            }
            JsonNode running = ensureRunning(assignment, envelope, version);
            version = running.path("task").path("version").asLong(version);
            envelope = running;

            HarnessAgent runtimeAgent = agent.get();
            registerCollaborationTools(runtimeAgent, assignment, availableActions(envelope));

            String payload = new String(assignment.payload(), StandardCharsets.UTF_8);
            String prompt =
                    "AgentTask "
                            + assignment.agentTaskId()
                            + " is ready. The JSON below contains the authoritative Run input,"
                            + " Issue, discussion inputs, Team role, and artifacts. Complete the"
                            + " requested work and"
                            + " return a concise result; use CollaborationClient for fresh reads,"
                            + " progress comments, artifacts, or child Issues. The available"
                            + " CollaborationClient actions are registered as tools with the exact"
                            + " names shown in availableActions. The adapter owns task.complete and"
                            + " task.fail; do not call them."
                            + roleInstructions(envelope, inputIds)
                            + "\ncontextUrl="
                            + nullToEmpty(assignment.contextUrl())
                            + "\n"
                            + envelope
                            + (payload.isBlank() ? "" : "\neventPayload=" + payload);
            Msg kickoff = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
            String sessionId =
                    assignment.sessionId() == null || assignment.sessionId().isBlank()
                            ? assignment.agentTaskId()
                            : assignment.sessionId();
            RuntimeContext context =
                    RuntimeContext.builder()
                            .sessionId(sessionId)
                            .put(
                                    AgentTaskToolContext.class,
                                    new AgentTaskToolContext(
                                            assignment.agentTaskId(), assignment.taskToken()))
                            .build();
            Msg response = runtimeAgent.call(kickoff, context).block();
            int approvalRounds = 0;
            while (response != null
                    && response.getGenerateReason() == GenerateReason.PERMISSION_ASKING) {
                if (++approvalRounds > 32) {
                    throw new IllegalStateException("runtime approval round limit exceeded");
                }
                List<ToolUseBlock> pending = response.getContentBlocks(ToolUseBlock.class);
                if (pending.isEmpty()) {
                    throw new IllegalStateException(
                            "PERMISSION_ASKING response contains no tool calls");
                }
                List<ConfirmResult> confirmations = new ArrayList<>(pending.size());
                for (ToolUseBlock toolCall : pending) {
                    CollaborationClient.RuntimeApprovalDecision decision =
                            collaboration.awaitRuntimeToolApproval(
                                    assignment.agentTaskId(),
                                    assignment.taskToken(),
                                    toolCall.getId(),
                                    toolCall.getName(),
                                    toolCall.getInput());
                    confirmations.add(new ConfirmResult(decision.allow(), toolCall));
                }
                Msg resume =
                        Msg.builder()
                                .role(MsgRole.USER)
                                .textContent("Human tool approval decisions received")
                                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmations))
                                .build();
                response = runtimeAgent.call(resume, context).block();
            }
            if (approvalRounds > 0) {
                version =
                        collaboration
                                .taskContext(assignment.agentTaskId(), assignment.taskToken())
                                .path("task")
                                .path("version")
                                .asLong(version);
            }
            String summary = AgentScopeAdapter.textOf(response);
            if (summary == null || summary.isBlank()) {
                summary = "AgentTask completed";
            }
            Map<String, String> result = Map.of("content", summary);
            collaboration.complete(
                    assignment.agentTaskId(),
                    assignment.taskToken(),
                    version,
                    summary,
                    result,
                    inputIds,
                    List.of());
            LOG.log(Level.INFO, "AgentTask completed: {0}", assignment.agentTaskId());
        } catch (RuntimeException e) {
            try {
                collaboration.fail(
                        assignment.agentTaskId(),
                        assignment.taskToken(),
                        version,
                        "agent_failed",
                        e.getMessage());
            } catch (RuntimeException reportError) {
                e.addSuppressed(reportError);
            }
            acceptedEvents.remove(eventKey);
            throw e;
        }
    }

    private void registerCollaborationTools(
            HarnessAgent runtimeAgent,
            AgentTaskAssignment assignment,
            Set<String> availableActions) {
        Object toolkit = runtimeAgent.getToolkit();
        synchronized (toolkit) {
            for (JsonNode definition :
                    collaboration.tools(assignment.agentTaskId(), assignment.taskToken())) {
                String name = definition.path("name").asText();
                // The starter owns the physical task lifecycle. Exposing these two actions would
                // race the adapter's fenced completion/failure reporting.
                if ("task.complete".equals(name) || "task.fail".equals(name)) {
                    continue;
                }
                if (!availableActions.contains(name)) {
                    continue;
                }
                if (!runtimeAgent.getToolkit().getToolNames().contains(name)) {
                    runtimeAgent
                            .getToolkit()
                            .registerAgentTool(
                                    new AgentTaskCollaborationTool(collaboration, definition));
                }
            }
        }
    }

    static String roleInstructions(JsonNode envelope, List<String> inputIds) {
        JsonNode task = envelope.path("task");
        if (task.path("teamId").asText().isBlank()) {
            return " Complete the requested work and return the result.";
        }
        if (!task.path("leaderTask").asBoolean(false)) {
            return " You are a Team worker, not its coordinator. Do not create or accept child"
                    + " Issues and do not call run.node.complete, run.node.fail, or run.replan."
                    + " Complete only the assigned work and return its result; the adapter will"
                    + " complete this AgentTask.";
        }
        if (inputIds.isEmpty()) {
            return " You are the Team leader's initial task. If you delegate child work, return"
                    + " immediately after issue.child.create succeeds; do not wait through local"
                    + " session/task tools and do not call run.node.complete yet. The control plane"
                    + " will deliver a fresh leader follow-up when a worker result arrives. If no"
                    + " work is delegated, call run.node.complete after your own work converges."
                    + " Returning text alone never completes a Team coordinator.";
        }
        return " You are a Team leader follow-up with new worker inputs. Validate the supplied"
                + " result, call issue.accept and wait for its result, then make a separate"
                + " run.node.complete call only when every child Issue and worker node has"
                + " converged. Never send those mutations in parallel. run.node.complete also"
                + " completes this leader AgentTask; do not call task.complete afterwards."
                + " Returning text alone never completes a Team coordinator.";
    }

    private static Set<String> availableActions(JsonNode envelope) {
        Set<String> actions = new HashSet<>();
        for (JsonNode action : envelope.path("availableActions")) {
            String name = action.asText();
            if (!name.isBlank()) {
                actions.add(name);
            }
        }
        return actions;
    }

    /**
     * ASDP reports the physical Attempt start before invoking the framework adapter. That report
     * normally moves the logical AgentTask to {@code running} as part of the same control-plane
     * transaction, so issuing a second task start would fail its optimistic version check. Keep
     * the HTTP start as a fallback for transports where the ASDP report has not been observed yet.
     */
    JsonNode ensureRunning(
            AgentTaskAssignment assignment, JsonNode envelope, long expectedVersion) {
        if ("running".equals(envelope.path("task").path("status").asText())) {
            return envelope;
        }
        try {
            collaboration.start(assignment.agentTaskId(), assignment.taskToken(), expectedVersion);
            // task.start intentionally returns a compact {task: ...} response. Always refresh the
            // authoritative context so Issue, discussion inputs, Team, artifacts, and actions are
            // never lost on this timing-dependent fallback path.
            return collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
        } catch (CollaborationClient.CollaborationHttpException e) {
            if (e.status() != 409) {
                throw e;
            }
            // The ASDP start and this fallback can race. A conflict is success only when a fresh
            // fenced read proves that this same task is already running.
            JsonNode refreshed =
                    collaboration.taskContext(assignment.agentTaskId(), assignment.taskToken());
            if ("running".equals(refreshed.path("task").path("status").asText())) {
                return refreshed;
            }
            throw e;
        }
    }

    private static List<String> inputIds(JsonNode envelope) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : envelope.path("inputs")) {
            String id = item.path("input").path("id").asText();
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
