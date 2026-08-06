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
package io.agentscope.harness.agent.tool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool that blocks until async results arrive, either for a specific task barrier or (legacy)
 * until any message appears in the session inbox.
 *
 * <p>Prefer barrier mode ({@code task_ids} or {@code wait_all=true}): when the wait set reaches
 * terminal state, this tool embeds each task's status and result in the tool return so the model
 * can continue reasoning immediately without waiting for push-back delivery.
 *
 * <p>Without {@code task_ids} / {@code wait_all}, this keeps the legacy inbox-any behavior: the
 * tool returns as soon as <em>any</em> inbox message is present — it is not a wait-all barrier.
 *
 * <p>Guardrails prevent unbounded blocking:
 * <ul>
 *   <li>Timeout is clamped to {@value #MAX_TIMEOUT_SECONDS}s regardless of the LLM-supplied value.
 *   <li>After {@value #MAX_CONSECUTIVE_EMPTY_WAITS} consecutive timeouts with no results, the tool
 *       refuses further blocking waits and directs the LLM to use non-blocking alternatives.
 * </ul>
 */
public class WaitAsyncResultsTool {

    private static final Logger log = LoggerFactory.getLogger(WaitAsyncResultsTool.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private static final int MAX_CONSECUTIVE_EMPTY_WAITS = 2;
    private static final long POLL_INTERVAL_MS = 3000;

    private final MessageBus messageBus;
    private final TaskRepository taskRepository;
    private final ConcurrentHashMap<String, AtomicInteger> consecutiveEmptyWaitsBySession =
            new ConcurrentHashMap<>();
    private volatile BooleanSupplier externalWorkProbe;

    public WaitAsyncResultsTool(MessageBus messageBus) {
        this(messageBus, null);
    }

    public WaitAsyncResultsTool(MessageBus messageBus, TaskRepository taskRepository) {
        this.messageBus = messageBus;
        this.taskRepository = taskRepository;
    }

    /**
     * Registers a probe for outstanding work owned outside the subagent task repository (currently
     * AgentTeams teammates). Without it a lead with no subagent tasks would be told to stop waiting
     * while its teammates are still running.
     */
    public WaitAsyncResultsTool setExternalWorkProbe(BooleanSupplier probe) {
        this.externalWorkProbe = probe;
        return this;
    }

    private boolean hasExternalWork() {
        BooleanSupplier probe = this.externalWorkProbe;
        if (probe == null) {
            return false;
        }
        try {
            return probe.getAsBoolean();
        } catch (RuntimeException e) {
            log.debug("external work probe failed: {}", e.toString());
            return false;
        }
    }

    @Tool(
            name = "wait_async_results",
            description =
                    "Wait for background async tool, subagent, or teammate results. "
                            + "Prefer barrier mode: task_ids waits until those specific tasks are "
                            + "all terminal and returns their results in this tool output; "
                            + "wait_all=true waits for the snapshot of currently running tasks "
                            + "(tasks created while waiting are not added) and also returns their "
                            + "results. "
                            + "Without task_ids and without wait_all, this is legacy inbox-any "
                            + "mode: returns when ANY inbox message arrives — not wait-all. For "
                            + "must-collect-all groups use task_ids or wait_all=true. "
                            + "Also covers AgentTeams teammate work via the external-work probe. "
                            + "Max timeout is 120 seconds. "
                            + "If you have already waited without results, use task_list or "
                            + "task_output(block=false) to check status instead of waiting again.",
            readOnly = true)
    public String waitForResults(
            @ToolParam(
                            name = "timeout_seconds",
                            description =
                                    "Maximum seconds to wait. Default 60, max 120. "
                                            + "Values above 120 are clamped.")
                    Integer timeoutSeconds,
            @ToolParam(
                            name = "task_ids",
                            description =
                                    "Optional comma-separated task IDs to wait for. When set, the "
                                            + "tool returns only after all listed tasks are "
                                            + "terminal (or timeout), and embeds each task's "
                                            + "result in the response.",
                            required = false)
                    String taskIds,
            @ToolParam(
                            name = "wait_all",
                            description =
                                    "Optional barrier mode. When true and task_ids is empty, wait"
                                        + " for the snapshot of currently non-terminal tasks in"
                                        + " this session and embed their results. Tasks created"
                                        + " later are not added to the wait set. Not the same as"
                                        + " legacy no-arg inbox-any wait.",
                            required = false)
                    Boolean waitAll,
            RuntimeContext runtimeContext)
            throws InterruptedException {

        String sessionId = runtimeContext != null ? runtimeContext.getSessionId() : null;
        if (sessionId == null) {
            return "Cannot wait: no session context available.";
        }

        AtomicInteger emptyWaits =
                consecutiveEmptyWaitsBySession.computeIfAbsent(
                        sessionId, k -> new AtomicInteger(0));
        List<String> explicitTaskIds = parseTaskIds(taskIds);
        if (!explicitTaskIds.isEmpty() || Boolean.TRUE.equals(waitAll)) {
            return waitForTaskBarrier(
                    timeoutSeconds, runtimeContext, sessionId, explicitTaskIds, emptyWaits);
        }

        if (emptyWaits.get() >= MAX_CONSECUTIVE_EMPTY_WAITS) {
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info(
                        "wait_async_results: budget was exhausted but inbox now has messages,"
                                + " resetting counter, session={}",
                        sessionId);
                emptyWaits.set(0);
                return "Async results have arrived (inbox-any: at least one message). "
                        + "Continue reasoning — the results will be injected into your context "
                        + "automatically. Prefer wait_async_results(task_ids=...) or "
                        + "wait_all=true when you need every task in a group.";
            }
            log.info(
                    "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                    emptyWaits.get(),
                    sessionId);
            return "Wait budget exhausted: you have already waited "
                    + emptyWaits.get()
                    + " times without receiving results. "
                    + "Do NOT call wait_async_results again. Instead use task_list to check "
                    + "task status, or task_output(block=false) to poll for results without "
                    + "blocking.";
        }

        if (taskRepository != null) {
            boolean hasNonTerminal =
                    hasNonTerminalTasks(runtimeContext, sessionId) || hasExternalWork();
            if (!hasNonTerminal) {
                Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
                if (!Boolean.TRUE.equals(hasMessages)) {
                    log.info(
                            "wait_async_results: all tasks terminal and inbox empty,"
                                    + " returning immediately, session={}",
                            sessionId);
                    return "All background tasks have completed and no pending results in inbox."
                            + " Use task_list to review results, or task_output(task_id) to read"
                            + " a specific result.";
                }
            }
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);

        log.info(
                "wait_async_results: waiting up to {}s for any inbox message (legacy inbox-any),"
                        + " session={}",
                timeout,
                sessionId);

        long deadlineMs = System.currentTimeMillis() + (timeout * 1000L);

        while (true) {
            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (remainingMs <= 0) {
                break;
            }
            Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
            if (Boolean.TRUE.equals(hasMessages)) {
                log.info("wait_async_results: inbox has messages, session={}", sessionId);
                emptyWaits.set(0);
                return "Async results have arrived (inbox-any: at least one message). "
                        + "Continue reasoning — the results will be injected into your context "
                        + "automatically. Prefer wait_async_results(task_ids=...) or "
                        + "wait_all=true when you need every task in a group.";
            }
            // Cap sleep to the remaining budget so the tool never overshoots the caller's timeout.
            Thread.sleep(Math.min(POLL_INTERVAL_MS, remainingMs));
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s (consecutive empty waits: {}), session={}",
                timeout,
                emptyCount,
                sessionId);
        return "Timeout after "
                + timeout
                + "s. No async results yet (empty wait "
                + emptyCount
                + "/"
                + MAX_CONSECUTIVE_EMPTY_WAITS
                + "). "
                + "Use task_list to check task status, or task_output(block=false) to poll "
                + "without blocking.";
    }

    public String waitForResults(Integer timeoutSeconds, RuntimeContext runtimeContext)
            throws InterruptedException {
        return waitForResults(timeoutSeconds, null, null, runtimeContext);
    }

    private String waitForTaskBarrier(
            Integer timeoutSeconds,
            RuntimeContext runtimeContext,
            String sessionId,
            List<String> explicitTaskIds,
            AtomicInteger emptyWaits)
            throws InterruptedException {
        if (taskRepository == null) {
            return "Cannot wait for task completion: task repository is unavailable. "
                    + "Use task_list or task_output(block=false) to check status.";
        }

        if (!explicitTaskIds.isEmpty()) {
            List<String> missingTaskIds =
                    explicitTaskIds.stream()
                            .filter(
                                    id ->
                                            taskRepository.getTask(runtimeContext, sessionId, id)
                                                    == null)
                            .toList();
            if (!missingTaskIds.isEmpty()) {
                return "Cannot wait: unknown task_ids "
                        + missingTaskIds
                        + ". Use task_list to find valid task IDs.";
            }
        }

        List<String> waitSet =
                !explicitTaskIds.isEmpty()
                        ? explicitTaskIds
                        : snapshotNonTerminalTaskIds(runtimeContext, sessionId);
        if (waitSet.isEmpty()) {
            log.info(
                    "wait_async_results: wait_all snapshot empty,"
                            + " returning immediately, session={}",
                    sessionId);
            emptyWaits.set(0);
            return "No running background tasks found at wait start. Continue reasoning, "
                    + "or use task_list to review existing task status.";
        }

        String completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
        if (completed != null) {
            emptyWaits.set(0);
            return completed;
        }

        String rejected = rejectIfWaitBudgetExhausted(sessionId, emptyWaits);
        if (rejected != null) {
            return rejected;
        }

        int timeout = normalizeTimeout(timeoutSeconds, sessionId);
        log.info(
                "wait_async_results: waiting up to {}s for task barrier, session={}, tasks={}",
                timeout,
                sessionId,
                waitSet);

        long deadlineMs = System.currentTimeMillis() + (timeout * 1000L);
        while (true) {
            completed = completeTaskBarrierIfReady(runtimeContext, sessionId, waitSet);
            if (completed != null) {
                emptyWaits.set(0);
                return completed;
            }
            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (remainingMs <= 0) {
                break;
            }
            sleepForPollInterval(remainingMs);
        }

        int emptyCount = emptyWaits.incrementAndGet();
        log.info(
                "wait_async_results: timeout after {}s waiting for tasks {}"
                        + " (consecutive empty waits: {}), session={}",
                timeout,
                waitSet,
                emptyCount,
                sessionId);
        return "Timeout after "
                + timeout
                + "s. Requested background tasks are not all terminal yet (empty wait "
                + emptyCount
                + "/"
                + MAX_CONSECUTIVE_EMPTY_WAITS
                + "). "
                + "Use task_list to check task status, or task_output(block=false) to poll "
                + "without blocking.";
    }

    private String completeTaskBarrierIfReady(
            RuntimeContext runtimeContext, String sessionId, List<String> waitSet) {
        List<BackgroundTask> terminalTasks = new ArrayList<>(waitSet.size());
        for (String taskId : waitSet) {
            BackgroundTask task = taskRepository.getTask(runtimeContext, sessionId, taskId);
            if (task == null) {
                log.info(
                        "wait_async_results: task barrier missing task {}, session={}",
                        taskId,
                        sessionId);
                return "Cannot wait: task_id "
                        + taskId
                        + " is no longer available. Use task_list to refresh task status.";
            }
            if (!task.getTaskStatus().isTerminal()) {
                return null;
            }
            terminalTasks.add(task);
        }
        log.info(
                "wait_async_results: task barrier complete, tasks={}, session={}",
                waitSet,
                sessionId);
        return formatBarrierResults(runtimeContext, sessionId, terminalTasks);
    }

    /**
     * Embeds each terminal task's status/result in the tool return so the parent can continue
     * without waiting for inbox push-back. Marks tasks delivered to avoid duplicate reminders.
     */
    private String formatBarrierResults(
            RuntimeContext runtimeContext, String sessionId, List<BackgroundTask> tasks) {
        StringBuilder sb = new StringBuilder();
        sb.append("All requested background tasks are terminal. Results are included below.")
                .append('\n');
        for (BackgroundTask task : tasks) {
            task.updateLastCheckedAt();
            if (task.isCompleted() && task.getTaskStatus().isTerminal()) {
                try {
                    taskRepository.markDelivered(runtimeContext, sessionId, task.getTaskId());
                } catch (RuntimeException ignore) {
                    // Best-effort: failure only risks a redundant push reminder.
                }
            }
            sb.append("---").append('\n');
            sb.append("task_id: ").append(task.getTaskId()).append('\n');
            if (task.getAgentId() != null) {
                sb.append("agent_id: ").append(task.getAgentId()).append('\n');
            }
            sb.append("status: ").append(task.getStatus()).append('\n');
            if (task.getResult() != null) {
                sb.append("Result:\n").append(task.getResult()).append('\n');
            } else if (task.getError() != null) {
                Exception err = task.getError();
                sb.append("Error:\n").append(err.getMessage()).append('\n');
                if (err.getCause() != null) {
                    sb.append("Cause: ").append(err.getCause().getMessage()).append('\n');
                }
            } else {
                sb.append("Task completed with no result.").append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String rejectIfWaitBudgetExhausted(String sessionId, AtomicInteger emptyWaits) {
        if (emptyWaits.get() < MAX_CONSECUTIVE_EMPTY_WAITS) {
            return null;
        }
        Boolean hasMessages = messageBus.inboxHasMessages(sessionId).block();
        if (Boolean.TRUE.equals(hasMessages)) {
            log.info(
                    "wait_async_results: budget was exhausted but inbox now has messages,"
                            + " resetting counter, session={}",
                    sessionId);
            emptyWaits.set(0);
            return "Async results have arrived (inbox-any: at least one message). "
                    + "Continue reasoning — the results will be injected into your context "
                    + "automatically. Prefer wait_async_results(task_ids=...) or "
                    + "wait_all=true when you need every task in a group.";
        }
        log.info(
                "wait_async_results: rejected — {} consecutive empty waits reached, session={}",
                emptyWaits.get(),
                sessionId);
        return "Wait budget exhausted: you have already waited "
                + emptyWaits.get()
                + " times without receiving results. "
                + "Do NOT call wait_async_results again. Instead use task_list to check "
                + "task status, or task_output(block=false) to poll for results without "
                + "blocking.";
    }

    private int normalizeTimeout(Integer timeoutSeconds, String sessionId) {
        int raw =
                timeoutSeconds != null && timeoutSeconds > 0
                        ? timeoutSeconds
                        : DEFAULT_TIMEOUT_SECONDS;
        int timeout = Math.min(raw, MAX_TIMEOUT_SECONDS);
        if (raw > MAX_TIMEOUT_SECONDS) {
            log.info(
                    "wait_async_results: clamped timeout from {}s to {}s, session={}",
                    raw,
                    timeout,
                    sessionId);
        }
        return timeout;
    }

    private void sleepForPollInterval(long remainingMs) throws InterruptedException {
        Thread.sleep(Math.min(POLL_INTERVAL_MS, remainingMs));
    }

    private List<String> parseTaskIds(String taskIds) {
        if (taskIds == null || taskIds.isBlank()) {
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        for (String raw : taskIds.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                parsed.add(id);
            }
        }
        return parsed;
    }

    private List<String> snapshotNonTerminalTaskIds(RuntimeContext rc, String sessionId) {
        Collection<BackgroundTask> tasks = taskRepository.listTasks(rc, sessionId, null);
        return tasks.stream()
                .filter(t -> !t.getTaskStatus().isTerminal())
                .map(BackgroundTask::getTaskId)
                .toList();
    }

    private boolean hasNonTerminalTasks(RuntimeContext rc, String sessionId) {
        Collection<BackgroundTask> tasks = taskRepository.listTasks(rc, sessionId, null);
        return tasks.stream().anyMatch(t -> !t.getTaskStatus().isTerminal());
    }
}
