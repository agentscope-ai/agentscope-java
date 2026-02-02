/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.agentscope.core.training.runner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Task Execution Registry
 *
 * <p>Stores execution contexts for all Runs of all Tasks, supports querying and analysis.
 *
 * <h2>Core features:</h2>
 * <ul>
 *   <li>Register {@link RunExecutionContext} for each Run</li>
 *   <li>Query all Runs for a Task</li>
 *   <li>Query specific (Task, Run)</li>
 *   <li>Statistical analysis (total Task count, total Run count, etc.)</li>
 * </ul>
 *
 * <h2>Data structure:</h2>
 * <pre>
 * Map&lt;String, List&lt;RunExecutionContext&gt;&gt;
 *   ├─ "task-001" → [ctx(run=0), ctx(run=1), ctx(run=2)]
 *   ├─ "task-002" → [ctx(run=0), ctx(run=1)]
 *   └─ "task-003" → [ctx(run=0)]
 * </pre>
 *
 * <h2>Thread safety:</h2>
 * <ul>
 *   <li>Uses {@link ConcurrentHashMap} for storage</li>
 *   <li>List uses synchronized wrapper</li>
 *   <li>Supports concurrent read/write</li>
 * </ul>
 *
 * <h2>Memory management:</h2>
 * <ul>
 *   <li>Provides manual cleanup interface {@link #cleanup(String)}</li>
 *   <li>Provides clear all data interface {@link #clearAll()}</li>
 *   <li>Recommendation: Periodically clean up expired data to avoid memory leaks</li>
 * </ul>
 *
 * @see RunExecutionContext
 * @see RunRegistry
 */
public class TaskExecutionRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TaskExecutionRegistry.class);

    /**
     * Task ID → List of RunExecutionContext (sorted by Run ID)
     */
    private static final ConcurrentHashMap<String, List<RunExecutionContext>> registry =
            new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private TaskExecutionRegistry() {}

    /**
     * Register a RunExecutionContext
     *
     * <p><b>Use case:</b> Called by TrainingRouter after Shadow Agent execution completes.
     *
     * @param context Run execution context
     */
    public static void register(RunExecutionContext context) {
        if (context == null) {
            logger.warn("Attempted to register null context, ignoring");
            return;
        }

        String taskId = context.getTaskId();
        registry.compute(
                taskId,
                (k, existingList) -> {
                    List<RunExecutionContext> list =
                            existingList != null
                                    ? existingList
                                    : Collections.synchronizedList(new ArrayList<>());
                    list.add(context);
                    return list;
                });

        logger.debug(
                "Registered context: {}, total runs for this task: {}",
                context,
                registry.get(taskId).size());
    }

    /**
     * Get contexts for all Runs of specified Task
     *
     * <p><b>Return value:</b> Copy of list sorted by Run ID in ascending order.
     *
     * @param taskId Task ID
     * @return All Run contexts for this Task (returns empty list if Task doesn't exist)
     */
    public static List<RunExecutionContext> getRunsByTask(String taskId) {
        if (taskId == null) {
            return Collections.emptyList();
        }

        List<RunExecutionContext> contexts = registry.get(taskId);
        if (contexts == null || contexts.isEmpty()) {
            return Collections.emptyList();
        }

        // Return copy, sorted by runId
        synchronized (contexts) {
            return contexts.stream()
                    .sorted(Comparator.comparing(ctx -> Integer.parseInt(ctx.getRunId())))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Get context for specified (Task, Run)
     *
     * @param taskId Task ID
     * @param runId Run ID
     * @return Corresponding context, returns null if not found
     */
    public static RunExecutionContext getRun(String taskId, String runId) {
        if (taskId == null || runId == null) {
            return null;
        }

        List<RunExecutionContext> contexts = registry.get(taskId);
        if (contexts == null) {
            return null;
        }

        synchronized (contexts) {
            return contexts.stream()
                    .filter(ctx -> ctx.getRunId().equals(runId))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Get all Task IDs
     *
     * @return Set of all Task IDs
     */
    public static Set<String> getAllTaskIds() {
        return new HashSet<>(registry.keySet());
    }

    /**
     * Get Run count for specified Task
     *
     * @param taskId Task ID
     * @return Run count (returns 0 if Task doesn't exist)
     */
    public static int getRunCount(String taskId) {
        if (taskId == null) {
            return 0;
        }
        List<RunExecutionContext> contexts = registry.get(taskId);
        return contexts != null ? contexts.size() : 0;
    }

    /**
     * Get total Task count
     *
     * @return Task count
     */
    public static int getTaskCount() {
        return registry.size();
    }

    /**
     * Get total Run count (all Runs of all Tasks)
     *
     * @return Total Run count
     */
    public static int getTotalRunCount() {
        return registry.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Clean up all data for specified Task
     *
     * <p>Used to release memory and avoid memory leaks from long-running processes.
     *
     * @param taskId Task ID
     * @return Number of Runs cleaned up
     */
    public static int cleanup(String taskId) {
        if (taskId == null) {
            return 0;
        }

        List<RunExecutionContext> removed = registry.remove(taskId);
        int count = removed != null ? removed.size() : 0;

        if (count > 0) {
            logger.info("Cleaned up {} runs for task: {}", count, taskId);
        }

        return count;
    }

    /**
     * Clear all data
     *
     * <p><b>Warning:</b> This operation deletes all Run data for all Tasks, use with caution!
     */
    public static void clearAll() {
        int taskCount = registry.size();
        int runCount = getTotalRunCount();
        registry.clear();
        logger.info("Cleared all data: {} tasks, {} runs", taskCount, runCount);
    }

    /**
     * Get statistical summary
     *
     * @return Statistical information
     */
    public static RegistryStats getStats() {
        return new RegistryStats(getTaskCount(), getTotalRunCount());
    }

    /**
     * Statistical information
     */
    public static class RegistryStats {
        private final int taskCount;
        private final int totalRunCount;

        private RegistryStats(int taskCount, int totalRunCount) {
            this.taskCount = taskCount;
            this.totalRunCount = totalRunCount;
        }

        public int getTaskCount() {
            return taskCount;
        }

        public int getTotalRunCount() {
            return totalRunCount;
        }

        public double getAverageRunsPerTask() {
            return taskCount > 0 ? (double) totalRunCount / taskCount : 0.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "RegistryStats{tasks=%d, runs=%d, avg=%.2f}",
                    taskCount, totalRunCount, getAverageRunsPerTask());
        }
    }
}
