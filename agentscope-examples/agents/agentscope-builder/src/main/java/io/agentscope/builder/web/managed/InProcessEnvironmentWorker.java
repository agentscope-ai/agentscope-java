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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.managed.selfhosted.LocalHandsToolExecutor;
import io.agentscope.builder.web.managed.selfhosted.PendingHandsToolService;
import io.agentscope.builder.web.managed.selfhosted.SessionInputStager;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Built-in Environment Worker that runs in the same JVM as the builder server for local/dev
 * {@code self_hosted} environments.
 *
 * <p>Under the event-driven model it claims work items, executes pending hands tools against a
 * local workspace directory, and resumes the turn via {@link SessionTurnRunner} — the same
 * protocol as {@code HandsWorkerMain}, without HTTP.
 *
 * <p>Disable with {@code builder.hands.in-process-worker=false} and run an external worker instead.
 */
@Component
@ConditionalOnProperty(
        prefix = "builder.hands",
        name = "in-process-worker",
        havingValue = "true",
        matchIfMissing = true)
public class InProcessEnvironmentWorker {

    private static final Logger log = LoggerFactory.getLogger(InProcessEnvironmentWorker.class);
    private static final String WORKER_ID = "in-process";
    private static final long POLL_TIMEOUT_MS = 500L;
    private static final long PENDING_POLL_MS = 200L;

    private final EnvironmentWorkQueue workQueue;
    private final PendingHandsToolService pendingHandsToolService;
    private final SessionTurnRunner turnRunner;
    private final SessionEventLog eventLog;
    private final ManagedSessionService sessionService;
    private final Path handsRoot;
    private final ScheduledExecutorService scheduler;
    private volatile boolean stopped = false;

    public InProcessEnvironmentWorker(
            EnvironmentWorkQueue workQueue,
            PendingHandsToolService pendingHandsToolService,
            @Lazy SessionTurnRunner turnRunner,
            SessionEventLog eventLog,
            @Lazy ManagedSessionService sessionService,
            SharedWorkspacePaths sharedWorkspacePaths) {
        this.workQueue = workQueue;
        this.pendingHandsToolService = pendingHandsToolService;
        this.turnRunner = turnRunner;
        this.eventLog = eventLog;
        this.sessionService = sessionService;
        this.handsRoot = sharedWorkspacePaths.workspaceRoot().resolve("hands");
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "in-process-environment-worker");
                            t.setDaemon(true);
                            return t;
                        });
    }

    @PostConstruct
    public void start() {
        scheduler.execute(this::pollLoop);
        log.info("InProcessEnvironmentWorker started, handsRoot={}", handsRoot);
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        scheduler.shutdownNow();
    }

    private void pollLoop() {
        workQueue.registerWorker(WORKER_ID, "local-hands-executor");
        while (!stopped) {
            try {
                Optional<EnvironmentWorkQueue.WorkItem> polled =
                        workQueue.poll(null, WORKER_ID, POLL_TIMEOUT_MS);
                if (polled.isPresent()) {
                    handleWork(polled.get());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                log.warn("[hands-worker] poll/execute cycle failed: {}", ex.getMessage(), ex);
            }
        }
    }

    private void handleWork(EnvironmentWorkQueue.WorkItem item) {
        try {
            Path workDir = handsRoot.resolve(sanitize(item.sessionId()));
            Files.createDirectories(workDir);
            workQueue.ack(item.leaseId(), WORKER_ID, workDir.toString());
            ManagedSessionDto session = sessionService.requireById(item.sessionId());
            SessionInputStager.stage(
                    SessionInputStager.metadataFromResources(session.resources()), workDir);
            LocalHandsToolExecutor executor = new LocalHandsToolExecutor(workDir);

            // Drain pending tools while the session stays in requires_action / running with
            // outstanding tool_use events. Exit when no pending tools remain after a short wait.
            int idleRounds = 0;
            while (!stopped && idleRounds < 50) {
                List<Map<String, Object>> pending =
                        pendingHandsToolService.listPending(item.sessionId());
                if (pending.isEmpty()) {
                    idleRounds++;
                    Thread.sleep(PENDING_POLL_MS);
                    continue;
                }
                idleRounds = 0;
                List<ToolResultBlock> blocks = new ArrayList<>();
                for (Map<String, Object> tool : pending) {
                    String id = stringOf(tool.get("id"));
                    String name = stringOf(tool.get("name"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input =
                            tool.get("input") instanceof Map<?, ?> m
                                    ? (Map<String, Object>) m
                                    : Map.of();
                    LocalHandsToolExecutor.ToolExecResult exec = executor.execute(name, input);
                    Map<String, Object> stored = new LinkedHashMap<>();
                    stored.put("tool_use_id", id);
                    stored.put("name", name);
                    stored.put("content", exec.content());
                    stored.put("is_error", exec.error());
                    eventLog.append(item.sessionId(), SessionEventTypes.USER_TOOL_RESULT, stored);
                    if (exec.error()) {
                        blocks.add(
                                ToolResultBlock.of(
                                        id,
                                        name,
                                        TextBlock.builder().text(exec.content()).build(),
                                        Map.of("error", true)));
                    } else {
                        blocks.add(
                                ToolResultBlock.of(
                                        id,
                                        name,
                                        TextBlock.builder().text(exec.content()).build()));
                    }
                }
                ManagedSessionDto current = sessionService.requireById(item.sessionId());
                turnRunner.resumeWithToolResults(current, blocks);
                workQueue.heartbeat(item.leaseId());
            }
            workQueue.stop(item.leaseId());
            log.debug("[hands-worker] finished session={}, workDir={}", item.sessionId(), workDir);
        } catch (Exception ex) {
            log.warn(
                    "[hands-worker] failed for session {}: {}",
                    item.sessionId(),
                    ex.getMessage(),
                    ex);
            try {
                workQueue.stop(item.leaseId());
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
