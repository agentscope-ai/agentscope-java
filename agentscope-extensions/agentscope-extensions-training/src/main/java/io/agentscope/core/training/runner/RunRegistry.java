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

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run ID Registry
 *
 * <p>Manages execution count (Run ID) for each Task ID.
 *
 * <p>Run ID is used to identify the Nth execution of the same Task, supports:
 * <ul>
 *   <li>Comparison experiments (same question, different models/parameters)</li>
 *   <li>Retry mechanism (re-execution after failure)</li>
 *   <li>A/B testing (same request, different strategies)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b>
 * <ul>
 *   <li>Uses {@link ConcurrentHashMap} to store counters</li>
 *   <li>Uses {@link AtomicInteger} to ensure concurrent safety</li>
 * </ul>
 *
 * <p><b>Memory management:</b>
 * <ul>
 *   <li>Supports manual cleanup of specific Task counters</li>
 *   <li>Supports periodic cleanup of all counters (avoid memory leaks)</li>
 * </ul>
 *
 * @see TaskIdGenerator
 * @see RunExecutionContext
 */
class RunRegistry {

    /**
     * Task ID → Run counter
     * Each Task ID corresponds to an incrementing counter
     */
    private static final ConcurrentHashMap<String, AtomicInteger> taskRunCounters =
            new ConcurrentHashMap<>();

    // Private constructor to prevent instantiation
    private RunRegistry() {}

    /**
     * Allocate a new Run ID for the specified Task
     *
     * <p>Run ID increments from 0 (0, 1, 2, ...)
     *
     * <p><b>Thread-safe:</b> Safe to call this method concurrently from multiple threads.
     *
     * @param taskId Task ID, cannot be null
     * @return Run ID (string format, e.g. "0", "1", "2")
     * @throws IllegalArgumentException if taskId is null
     */
    public static String allocateRunId(String taskId) {
        if (taskId == null) {
            throw new IllegalArgumentException("Task ID cannot be null");
        }

        AtomicInteger counter = taskRunCounters.computeIfAbsent(taskId, k -> new AtomicInteger(0));

        int runId = counter.getAndIncrement();
        return String.valueOf(runId);
    }

    /**
     * Get current Run count for the specified Task
     *
     * @param taskId Task ID
     * @return Current Run count (returns 0 if Task doesn't exist)
     */
    public static int getCurrentRunCount(String taskId) {
        if (taskId == null) {
            return 0;
        }
        AtomicInteger counter = taskRunCounters.get(taskId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Clean up counter for specified Task
     *
     * <p>Used to release memory and avoid memory leaks from long-running processes.
     *
     * <p><b>Use cases:</b>
     * <ul>
     *   <li>Manual cleanup after Task execution completes</li>
     *   <li>Cleanup counter after Feedback submission succeeds</li>
     * </ul>
     *
     * @param taskId Task ID
     */
    public static void cleanup(String taskId) {
        if (taskId != null) {
            taskRunCounters.remove(taskId);
        }
    }

    /**
     * Clean up all counters
     *
     * <p><b>Warning:</b> This operation clears all Task counters, use with caution!
     *
     * <p><b>Use cases:</b>
     * <ul>
     *   <li>When TrainingRunner stops</li>
     *   <li>During system reset</li>
     * </ul>
     */
    public static void clearAll() {
        taskRunCounters.clear();
    }

    /**
     * Get number of Tasks in current registry
     *
     * @return Number of Tasks
     */
    public static int size() {
        return taskRunCounters.size();
    }

    /**
     * Periodically clean up old Task counters (reserved interface)
     *
     * <p>Can be extended in the future to: clean up Tasks unused for specified time.
     *
     * <p><b>Implementation approach:</b>
     * <ul>
     *   <li>Record last access time for each Task</li>
     *   <li>Periodically scan and clean up expired Tasks</li>
     * </ul>
     *
     * @param ttl Expiration time
     */
    public static void cleanupOldTasks(Duration ttl) {
        // TODO: Can implement time-based automatic cleanup in the future
        // Current version: Manually call cleanup() for cleaning
    }
}
