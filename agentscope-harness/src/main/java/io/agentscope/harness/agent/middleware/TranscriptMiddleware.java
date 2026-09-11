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
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.MemoryBackgroundTasks;
import io.agentscope.harness.agent.memory.session.SessionTranscriptWriter;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.transcript.TranscriptStore;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Persists the conversation transcript at the end of every agent call.
 *
 * <p>Independent of memory flush: wired even when {@code disableMemoryHooks} is set, so session
 * history stays complete for Operate / {@code SessionSearchTool} / resumption.
 *
 * <p>For calls without an active per-call sandbox, transcript persistence is dispatched after the
 * agent stream completes and does not delay the downstream completion signal. The background task
 * is tracked so lifecycle shutdown can wait for pending writes before releasing workspace
 * resources. Sandbox calls keep persistence in the stream so the per-call sandbox remains alive
 * until the writer has finished.
 */
public class TranscriptMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(TranscriptMiddleware.class);

    private final WorkspaceManager workspaceManager;
    private final TranscriptStore transcriptStore;
    private final String tenant;

    public TranscriptMiddleware(WorkspaceManager workspaceManager) {
        this(workspaceManager, null, null);
    }

    public TranscriptMiddleware(
            WorkspaceManager workspaceManager, TranscriptStore transcriptStore, String tenant) {
        this.workspaceManager = workspaceManager;
        this.transcriptStore = transcriptStore;
        this.tenant = tenant;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        final RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        Flux<AgentEvent> stream = next.apply(input);
        // The per-call sandbox is released by the outer Flux.using scope as soon as the stream
        // completes. Keep transcript persistence in the stream for sandbox calls so the writer
        // cannot start after the sandbox has been released.
        if (rc.get(SandboxAcquireResult.class) != null) {
            return stream.concatWith(
                    Mono.defer(() -> appendTranscript(agent, rc))
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(
                                    e -> {
                                        log.warn(
                                                "Transcript append failed for agent={}, session={}:"
                                                        + " {}",
                                                agentNameForLog(agent),
                                                rc.getSessionId(),
                                                e.getMessage());
                                        return Mono.empty();
                                    })
                            .then(Mono.<AgentEvent>empty()));
        }
        return stream.doOnComplete(() -> scheduleTranscript(agent, rc));
    }

    private void scheduleTranscript(Agent agent, RuntimeContext rc) {
        // AgentState is mutable and may be reused by a later turn, so capture this turn's
        // defensive snapshot before detaching the transcript write from the stream.
        List<Msg> messages = snapshotMessages(agent, rc);
        if (messages.isEmpty()) {
            return;
        }
        MemoryBackgroundTasks.begin();
        try {
            Mono.defer(() -> appendTranscript(agent, rc, messages))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doFinally(signal -> MemoryBackgroundTasks.end())
                    .subscribe(
                            null,
                            e ->
                                    log.warn(
                                            "Transcript append failed for agent={}, session={}: {}",
                                            agentNameForLog(agent),
                                            rc.getSessionId(),
                                            e.getMessage()));
        } catch (RuntimeException e) {
            MemoryBackgroundTasks.end();
            log.warn(
                    "Transcript append could not be scheduled for agent={}, session={}: {}",
                    agentNameForLog(agent),
                    rc.getSessionId(),
                    e.getMessage());
        }
    }

    private List<Msg> snapshotMessages(Agent agent, RuntimeContext rc) {
        try {
            AgentState state = RuntimeContext.resolveAgentState(rc, agent);
            return state == null ? List.of() : state.getContext();
        } catch (RuntimeException e) {
            log.warn(
                    "Transcript snapshot failed for agent={}, session={}: {}",
                    agentNameForLog(agent),
                    rc.getSessionId(),
                    e.getMessage());
            return List.of();
        }
    }

    private static String agentNameForLog(Agent agent) {
        try {
            return agent.getName();
        } catch (RuntimeException ignored) {
            return "<unknown>";
        }
    }

    private Mono<Void> appendTranscript(Agent agent, RuntimeContext rc) {
        AgentState state = RuntimeContext.resolveAgentState(rc, agent);
        if (state == null) {
            return Mono.empty();
        }
        List<Msg> messages = state.getContext();
        if (messages.isEmpty()) {
            return Mono.empty();
        }
        return appendTranscript(agent, rc, messages);
    }

    private Mono<Void> appendTranscript(Agent agent, RuntimeContext rc, List<Msg> messages) {
        String agentId = agent.getName();
        if (agent instanceof HarnessAgent harnessAgent) {
            String stable = harnessAgent.getAgentId();
            if (stable != null && !stable.isBlank()) {
                agentId = stable;
            }
        }
        String sessionId =
                rc.getSessionId() != null && !rc.getSessionId().isBlank()
                        ? rc.getSessionId()
                        : "default";
        final String key = agentId;
        return Mono.fromRunnable(
                () -> {
                    SessionTranscriptWriter writer =
                            new SessionTranscriptWriter(workspaceManager, transcriptStore, tenant);
                    writer.appendMessages(rc, messages, key, sessionId);
                });
    }
}
