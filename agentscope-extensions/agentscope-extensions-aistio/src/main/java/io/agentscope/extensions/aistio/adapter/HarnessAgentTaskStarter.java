/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.aistio.model.AgentTaskAssignment;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
            boolean teamCoordinator = envelope.path("task").path("leaderTask").asBoolean(false);

            String payload = new String(assignment.payload(), StandardCharsets.UTF_8);
            String prompt =
                    "AgentTask "
                            + assignment.agentTaskId()
                            + " is ready. The JSON below is the authoritative Issue, discussion"
                            + " inputs, Team role, and artifacts. Complete the requested work and"
                            + " return a concise result; use CollaborationClient for fresh reads,"
                            + " progress comments, artifacts, or child Issues.\ncontextUrl="
                            + nullToEmpty(assignment.contextUrl())
                            + "\n"
                            + envelope
                            + (payload.isBlank() ? "" : "\neventPayload=" + payload);
            Msg kickoff = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
            RuntimeContext context =
                    RuntimeContext.builder().sessionId(assignment.agentTaskId()).build();
            Msg response = agent.get().call(kickoff, context).block();
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
            if (teamCoordinator) {
                completeTeamCoordinator(assignment, result);
            }
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

    /**
     * A Team leader task and its coordinator node have deliberately separate lifecycles. The
     * framework adapter owns the final explicit coordinator action for the simple case where the
     * leader returned without leaving delegated work behind. If workers, child Issues, or dynamic
     * nodes are still active, the control plane rejects the completion and keeps the coordinator
     * waiting for a later turn.
     */
    private void completeTeamCoordinator(
            AgentTaskAssignment assignment, Map<String, String> output) {
        try {
            collaboration.completeRunNode(assignment.agentTaskId(), assignment.taskToken(), output);
            LOG.log(
                    Level.INFO,
                    "Team coordinator completed for AgentTask: {0}",
                    assignment.agentTaskId());
        } catch (RuntimeException e) {
            // The logical task is already complete. A coordinator conflict is not a task failure;
            // it means active delegated work must converge before another coordinator turn can
            // explicitly close the node.
            LOG.log(
                    Level.WARNING,
                    "Team coordinator remains waiting after AgentTask "
                            + assignment.agentTaskId()
                            + ": "
                            + e.getMessage());
        }
    }

    /**
     * ASDP reports the physical Attempt start before invoking the framework adapter. That report
     * normally moves the logical AgentTask to {@code running} as part of the same control-plane
     * transaction, so issuing a second task start would fail its optimistic version check. Keep
     * the HTTP start as a fallback for transports where the ASDP report has not been observed yet.
     */
    private JsonNode ensureRunning(
            AgentTaskAssignment assignment, JsonNode envelope, long expectedVersion) {
        if ("running".equals(envelope.path("task").path("status").asText())) {
            return envelope;
        }
        try {
            return collaboration.start(
                    assignment.agentTaskId(), assignment.taskToken(), expectedVersion);
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
