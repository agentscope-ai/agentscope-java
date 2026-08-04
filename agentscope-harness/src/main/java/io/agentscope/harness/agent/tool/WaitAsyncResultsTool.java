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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool that blocks until async results arrive in the session's inbox, or until a timeout is
 * reached. This gives the LLM the option to wait for background results within a single
 * {@code call()} invocation instead of returning and relying on a wakeup.
 *
 * <p>After this tool returns, the next reasoning step's {@code InboxMiddleware} will drain the
 * inbox and inject the results into context.
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

    public WaitAsyncResultsTool(MessageBus messageBus) {
        this(messageBus, null);
    }

    public WaitAsyncResultsTool(MessageBus messageBus, TaskRepository taskRepository) {
        this.messageBus = messageBus;
        this.taskRepository = taskRepository;
    }

    @Tool(
            name = "wait_async_results",
            description =
                    "Wait for background async tool or subagent results to arrive. "
                            + "Call this when you have launched async tasks and want to wait for "
                            + "their completion instead of returning to the user. After this tool "
                            + "returns successfully, continue reasoning — the results will be "
                            + "automatically injected into your context. "
                            + "Use task_ids to wait until specific task IDs are all terminal, "
                            + "or wait_all=true to wait until all currently running tasks in the "
                            + "session are terminal. "
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
                                            + "terminal, or timeout.",
                            required = false)
                    String taskIds,
            @ToolParam(
                            name = "wait_all",
                            description =
                                    "Optional barrier mode. When true and task_ids is empty, wait "
                                            + "for the snapshot of currently non-terminal tasks in "
                                            + "this session. Tasks created later are not added to "
                                            + "the wait set.",
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
                return "Async results have arrived. Continue reasoning — "
                        + "the results will be injected into your context automatically.";
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
            boolean hasNonTerminal = hasNonTerminalTasks(runtimeContext, sessionId);
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

        log.info(
                "wait_async_results: waiting up to {}s for inbox messages, session={}",
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
                return "Async results have arrived. Continue reasoning — "
                        + "the results will be injected into your context automatically.";
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
        }
        log.info(
                "wait_async_results: task barrier complete, tasks={}, session={}",
                waitSet,
                sessionId);
        return "All requested background tasks are terminal. Continue reasoning — "
                + "results will be injected automatically when delivered, or use "
                + "task_output(task_id) to read a specific result.";
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
            return "Async results have arrived. Continue reasoning — "
                    + "the results will be injected into your context automatically.";
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
