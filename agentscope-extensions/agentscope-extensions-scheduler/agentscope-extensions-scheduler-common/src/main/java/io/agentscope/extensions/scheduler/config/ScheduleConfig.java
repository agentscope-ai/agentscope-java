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

import io.agentscope.extensions.scheduler.AgentScheduler;
import java.util.Objects;

/**
 * Configuration for scheduled agent tasks.
 *
 * <p>This class defines the scheduling parameters for an agent, including the scheduling
 * type (cron, fixed rate, or fixed delay) and associated timing settings.
 *
 * <p><b>Supported Scheduling Modes:</b>
 * <ul>
 *   <li><b>NONE</b> - No automatic scheduling (default mode, manual execution only)</li>
 *   <li><b>CRON</b> - Schedule using a cron expression (e.g., "0 0 8 * * ?" for daily at 8 AM)</li>
 *   <li><b>FIXED_RATE</b> - Execute at a fixed rate (e.g., every 5 minutes, regardless of execution time)</li>
 *   <li><b>FIXED_DELAY</b> - Execute with a fixed delay between completion and next start</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b>
 * <pre>{@code
 * // Cron-based scheduling
 * ScheduleConfig config1 = ScheduleConfig.builder()
 *     .cron("0 0 8 * * ?")
 *     .zoneId("Asia/Shanghai")
 *     .build();
 *
 * // Fixed rate scheduling (every 5 minutes)
 * ScheduleConfig config2 = ScheduleConfig.builder()
 *     .fixedRate(5L)
 *     .initialDelay(10L)
 *     .build();
 *
 * // Fixed delay scheduling (5 minutes after completion)
 * ScheduleConfig config3 = ScheduleConfig.builder()
 *     .fixedDelay(5L)
 *     .build();
 * }</pre>
 *
 * @see ScheduleMode
 * @see AgentScheduler
 */
public class ScheduleConfig {

    private final ScheduleMode scheduleMode;
    private final String cronExpression;
    private final Long fixedRate;
    private final Long fixedDelay;
    private final Long initialDelay;
    private final String zoneId;

    private ScheduleConfig(Builder builder) {
        this.scheduleMode = builder.scheduleMode;
        this.cronExpression = builder.cronExpression;
        this.fixedRate = builder.fixedRate;
        this.fixedDelay = builder.fixedDelay;
        this.initialDelay = builder.initialDelay;
        this.zoneId = builder.zoneId;
        validate();
    }

    /**
     * Validate the configuration based on schedule mode.
     */
    private void validate() {
        Objects.requireNonNull(scheduleMode, "Schedule mode must not be null");
        switch (scheduleMode) {
            case NONE:
                // No validation needed for NONE mode
                break;
            case CRON:
                if (cronExpression == null || cronExpression.trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Cron expression must not be null or empty for CRON mode");
                }
                break;
            case FIXED_RATE:
                if (fixedRate == null || fixedRate <= 0) {
                    throw new IllegalArgumentException(
                            "Fixed rate must be a positive value for FIXED_RATE mode");
                }
                break;
            case FIXED_DELAY:
                if (fixedDelay == null || fixedDelay <= 0) {
                    throw new IllegalArgumentException(
                            "Fixed delay must be a positive value for FIXED_DELAY mode");
                }
                break;
        }
    }

    /**
     * Create a new builder instance.
     *
     * @return A new ScheduleConfig.Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get the schedule mode.
     *
     * @return The schedule mode
     */
    public ScheduleMode getScheduleMode() {
        return scheduleMode;
    }

    /**
     * Get the cron expression (only applicable for CRON type).
     *
     * @return The cron expression, or null if not a CRON schedule
     */
    public String getCronExpression() {
        return cronExpression;
    }

    /**
     * Get the fixed rate duration in milliseconds (only applicable for FIXED_RATE type).
     *
     * @return The fixed rate duration in milliseconds, or null if not a FIXED_RATE schedule
     */
    public Long getFixedRate() {
        return fixedRate;
    }

    /**
     * Get the fixed delay duration in milliseconds (only applicable for FIXED_DELAY type).
     *
     * @return The fixed delay duration in milliseconds, or null if not a FIXED_DELAY schedule
     */
    public Long getFixedDelay() {
        return fixedDelay;
    }

    /**
     * Get the initial delay in milliseconds before the first execution.
     *
     * @return The initial delay in milliseconds, or null if no initial delay is configured
     */
    public Long getInitialDelay() {
        return initialDelay;
    }

    /**
     * Get the time zone ID for cron expression evaluation.
     *
     * <p>Examples: "Asia/Shanghai", "America/New_York", "UTC"
     *
     * @return The time zone ID string (e.g., "Asia/Shanghai"), or null to use system default
     */
    public String getZoneId() {
        return zoneId;
    }

    /**
     * Builder for creating ScheduleConfig instances.
     */
    public static class Builder {
        private ScheduleMode scheduleMode = ScheduleMode.NONE; // Default to NONE
        private String cronExpression;
        private Long fixedRate;
        private Long fixedDelay;
        private Long initialDelay;
        private String zoneId;

        private Builder() {}

        /**
         * Configure a cron-based schedule.
         *
         * <p>The cron expression follows the standard format:
         * <pre>
         * ┌───────────── second (0-59)
         * │ ┌───────────── minute (0-59)
         * │ │ ┌───────────── hour (0-23)
         * │ │ │ ┌───────────── day of month (1-31)
         * │ │ │ │ ┌───────────── month (1-12 or JAN-DEC)
         * │ │ │ │ │ ┌───────────── day of week (0-6 or SUN-SAT)
         * │ │ │ │ │ │
         * * * * * * *
         * </pre>
         *
         * <p>Examples:
         * <ul>
         *   <li>"0 0 8 * * ?" - Every day at 8:00 AM</li>
         *   <li>"0 0/30 * * * ?" - Every 30 minutes</li>
         *   <li>"0 0 9 * * MON-FRI" - Every weekday at 9:00 AM</li>
         * </ul>
         *
         * @param expression The cron expression
         * @return This builder
         */
        public Builder cron(String expression) {
            this.scheduleMode = ScheduleMode.CRON;
            this.cronExpression = expression;
            return this;
        }

        /**
         * Configure a fixed-rate schedule.
         *
         * <p>The task will be executed at a fixed rate. If a previous execution is still running,
         * the next execution may start immediately after the previous one completes (depending on
         * the scheduler implementation's concurrency policy).
         *
         * <p>Example: If set to 5 minutes, the task will execute at T, T+5min, T+10min, etc.,
         * regardless of how long each execution takes.
         *
         * @param rate The fixed rate duration between executions in milliseconds
         * @return This builder
         */
        public Builder fixedRate(Long rate) {
            this.scheduleMode = ScheduleMode.FIXED_RATE;
            this.fixedRate = rate;
            return this;
        }

        /**
         * Configure a fixed-delay schedule.
         *
         * <p>The task will be executed with a fixed delay between the completion of one execution
         * and the start of the next.
         *
         * <p>Example: If set to 5 minutes and an execution takes 2 minutes, the next execution
         * will start 5 minutes after completion (i.e., 7 minutes after the previous execution started).
         *
         * @param delay The fixed delay duration between completion and next execution in milliseconds
         * @return This builder
         */
        public Builder fixedDelay(Long delay) {
            this.scheduleMode = ScheduleMode.FIXED_DELAY;
            this.fixedDelay = delay;
            return this;
        }

        /**
         * Set the initial delay before the first execution.
         *
         * <p>This is applicable to all schedule types. The scheduler will wait for this duration
         * before executing the task for the first time.
         *
         * @param delay The initial delay in milliseconds
         * @return This builder
         */
        public Builder initialDelay(Long delay) {
            this.initialDelay = delay;
            return this;
        }

        /**
         * Set the time zone ID for cron expression evaluation.
         *
         * <p>This is only applicable for CRON schedules. If not specified, the system default
         * time zone will be used.
         *
         * <p>Examples: "Asia/Shanghai", "America/New_York", "UTC", "Europe/London"
         *
         * @param zoneIdStr The time zone ID string (e.g., "Asia/Shanghai")
         * @return This builder
         */
        public Builder zoneId(String zoneIdStr) {
            this.zoneId = zoneIdStr;
            return this;
        }

        /**
         * Build the ScheduleConfig instance.
         *
         * @return A new ScheduleConfig instance
         * @throws IllegalArgumentException if the configuration is invalid
         */
        public ScheduleConfig build() {
            return new ScheduleConfig(this);
        }
    }
}
