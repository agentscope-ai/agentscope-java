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
package io.agentscope.extensions.scheduler.config;

/**
 * Enumeration of scheduling modes supported by the scheduler.
 *
 * <p>This enum defines the different timing strategies that can be used to schedule
 * agent execution.
 *
 * @see ScheduleConfig
 */
public enum ScheduleMode {

    /**
     * No scheduling - the agent will not be automatically scheduled.
     *
     * <p>This is the default mode. When set to NONE, the agent will not be executed
     * automatically by the scheduler. The agent can still be called manually through
     * its normal call methods.
     *
     * <p>Use case: Agents that should only be executed on-demand or manually triggered.
     */
    NONE,

    /**
     * Cron-based scheduling using cron expressions.
     *
     * <p>Allows flexible scheduling based on cron syntax, supporting complex patterns
     * like "execute every Monday at 9 AM" or "execute on the first day of each month".
     *
     * <p>Example expressions:
     * <ul>
     *   <li>"0 0 8 * * ?" - Every day at 8:00 AM</li>
     *   <li>"0 0/30 * * * ?" - Every 30 minutes</li>
     *   <li>"0 0 9 * * MON-FRI" - Every weekday at 9:00 AM</li>
     *   <li>"0 0 0 1 * ?" - First day of every month at midnight</li>
     * </ul>
     */
    CRON,

    /**
     * Fixed-rate scheduling with constant intervals between execution starts.
     *
     * <p>The task executes at fixed intervals regardless of execution duration.
     * If an execution takes longer than the interval, the next execution may
     * start immediately after the previous one completes (depending on concurrency policy).
     *
     * <p>Use case: Regular polling, periodic health checks, time-based triggers.
     *
     * <p>Example: With a 5-minute rate, executions start at T, T+5min, T+10min, etc.
     */
    FIXED_RATE,

    /**
     * Fixed-delay scheduling with constant delays between execution completion and next start.
     *
     * <p>The task waits for a fixed duration after completion before starting the next execution.
     * This ensures a minimum idle time between executions.
     *
     * <p>Use case: Sequential processing, rate-limited operations, preventing system overload.
     *
     * <p>Example: With a 5-minute delay, if execution takes 2 minutes, the next execution
     * starts 7 minutes after the previous one started (2min execution + 5min delay).
     */
    FIXED_DELAY
}
