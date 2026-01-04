/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.scheduler.quartz;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.scheduler.AgentScheduler;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AgentScheduler implementation based on Quartz Scheduler.
 *
 * <p>This scheduler integrates with Quartz to provide robust task scheduling capabilities
 * for agents within the application. It supports various scheduling modes and lifecycle management.
 *
 * <p><b>Key Features:</b>
 * <ul>
 *   <li>In-memory or persistent scheduling (configurable via Quartz properties)</li>
 *   <li>Support for cron expressions, fixed rate, and fixed delay execution</li>
 *   <li>Full lifecycle management: schedule, pause, resume, cancel</li>
 *   <li>Thread-safe task management</li>
 * </ul>
 *
 * <p><b>Prerequisites:</b>
 * <ul>
 *   <li>Quartz dependencies must be on the classpath</li>
 *   <li>Proper configuration of Quartz properties if using persistence</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // 1. Create scheduler
 * AgentScheduler scheduler = QuartzAgentScheduler.builder()
 *     .autoStart(true)
 *     .build();
 *
 * // 2. Create agent configuration
 * AgentConfig agentConfig = AgentConfig.builder()
 *     .name("MyAgent")
 *     .description("My scheduled agent")
 *     .agentFactory(() -> ReActAgent.builder()
 *         .name("MyAgent")
 *         .model(model)
 *         .toolkit(toolkit)
 *         .build())
 *     .build();
 *
 * // 3. Configure schedule
 * ScheduleConfig scheduleConfig = ScheduleConfig.builder()
 *     .scheduleMode(ScheduleMode.CRON)
 *     .cronExpression("0 0 8 * * ?") // Run at 8:00 AM every day
 *     .build();
 *
 * // 4. Schedule the agent
 * ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
 *
 * // 5. Manage task
 * scheduler.pause("MyAgent");
 * scheduler.resume("MyAgent");
 * scheduler.cancel("MyAgent");
 * }</pre>
 *
 * <p><b>Important Notes:</b>
 * <ul>
 *   <li>Uses a {@code QuartzAgentSchedulerRegistry} to bridge Quartz Jobs with Scheduler instances</li>
 *   <li>Supports {@code FIXED_DELAY} mode by manually rescheduling triggers after job completion</li>
 *   <li>Each scheduler instance has a unique ID to support multiple schedulers in the same JVM</li>
 * </ul>
 *
 * @see AgentScheduler
 * @see ScheduleAgentTask
 */
public class QuartzAgentScheduler implements AgentScheduler {

    private static final Logger logger = LoggerFactory.getLogger(QuartzAgentScheduler.class);

    private final Scheduler scheduler;
    private final Map<String, QuartzScheduleAgentTask> tasks = new ConcurrentHashMap<>();
    private final String schedulerId;

    private QuartzAgentScheduler(Scheduler scheduler, String schedulerId) {
        if (scheduler == null) {
            throw new IllegalArgumentException("Scheduler must not be null");
        }
        this.scheduler = scheduler;
        this.schedulerId = schedulerId;
    }

    /**
     * Register this scheduler instance in the registry.
     */
    private void register() {
        QuartzAgentSchedulerRegistry.register(schedulerId, this);
    }

    /**
     * Create a new Builder instance for QuartzAgentScheduler.
     *
     * @return A new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Schedule an agent execution based on the provided configuration with default schedule.
     *
     * @param agentConfig The configuration for the agent to be scheduled
     * @return A task handle for the scheduled agent
     */
    public ScheduleAgentTask schedule(AgentConfig agentConfig) {
        return this.schedule(agentConfig, ScheduleConfig.builder().build());
    }

    /**
     * Schedule an agent execution based on the provided configuration.
     *
     * <p>This method creates a Quartz Job and Trigger based on the schedule mode:
     * <ul>
     *   <li>{@code CRON}: Uses CronTrigger</li>
     *   <li>{@code FIXED_RATE}: Uses SimpleTrigger with repeat forever</li>
     *   <li>{@code FIXED_DELAY}: Uses a one-time trigger and reschedules upon completion</li>
     * </ul>
     *
     * @param agentConfig The configuration for the agent to be scheduled
     * @param scheduleConfig The scheduling configuration (time, mode, etc.)
     * @return A task handle for the scheduled agent
     * @throws RuntimeException if scheduling fails
     */
    @Override
    public ScheduleAgentTask schedule(AgentConfig agentConfig, ScheduleConfig scheduleConfig) {
        if (agentConfig == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (scheduleConfig == null) {
            throw new IllegalArgumentException("ScheduleConfig must not be null");
        }

        RuntimeAgentConfig runtimeConfig =
                agentConfig instanceof RuntimeAgentConfig rc
                        ? rc
                        : RuntimeAgentConfig.builder()
                                .name(agentConfig.getName())
                                .modelConfig(agentConfig.getModelConfig())
                                .sysPrompt(agentConfig.getSysPrompt())
                                .build();

        String jobName = runtimeConfig.getName();
        if (jobName == null || jobName.trim().isEmpty()) {
            throw new IllegalArgumentException("Agent name must not be null or empty");
        }

        // Check if already scheduled
        if (tasks.containsKey(jobName)) {
            logger.warn("Task '{}' is already scheduled, returning existing task", jobName);
            return tasks.get(jobName);
        }

        JobKey jobKey = new JobKey(jobName, "agentscope-quartz");
        JobDetail jobDetail =
                JobBuilder.newJob(AgentQuartzJob.class)
                        .withIdentity(jobKey)
                        .storeDurably(true)
                        .usingJobData("schedulerId", schedulerId)
                        .usingJobData("taskName", jobName)
                        .build();

        QuartzScheduleAgentTask task =
                new QuartzScheduleAgentTask(runtimeConfig, scheduleConfig, this, jobKey, scheduler);

        ScheduleMode mode = scheduleConfig.getScheduleMode();
        if (mode == ScheduleMode.FIXED_RATE) {
            if (scheduleConfig.getFixedRate() == null || scheduleConfig.getFixedRate() <= 0) {
                throw new IllegalArgumentException(
                        "Fixed rate must be a positive value for FIXED_RATE mode");
            }
        } else if (mode == ScheduleMode.FIXED_DELAY) {
            if (scheduleConfig.getFixedDelay() == null || scheduleConfig.getFixedDelay() <= 0) {
                throw new IllegalArgumentException(
                        "Fixed delay must be a positive value for FIXED_DELAY mode");
            }
        } else if (mode == ScheduleMode.CRON) {
            if (scheduleConfig.getCronExpression() == null
                    || scheduleConfig.getCronExpression().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "Cron expression must not be null or blank for CRON mode");
            }
        }

        Long initialDelay = scheduleConfig.getInitialDelay();
        Date startAt =
                initialDelay != null
                        ? new Date(System.currentTimeMillis() + initialDelay)
                        : new Date();

        try {
            if (mode == ScheduleMode.CRON) {
                CronScheduleBuilder csb =
                        CronScheduleBuilder.cronSchedule(scheduleConfig.getCronExpression());
                if (scheduleConfig.getZoneId() != null) {
                    csb = csb.inTimeZone(TimeZone.getTimeZone(scheduleConfig.getZoneId()));
                }
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .withSchedule(csb)
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
            } else if (mode == ScheduleMode.FIXED_RATE) {
                SimpleScheduleBuilder ssb =
                        SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInMilliseconds(scheduleConfig.getFixedRate())
                                .repeatForever();
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .withSchedule(ssb)
                                .build();
                scheduler.scheduleJob(jobDetail, trigger);
            } else if (mode == ScheduleMode.FIXED_DELAY) {
                Trigger trigger =
                        TriggerBuilder.newTrigger()
                                .withIdentity(jobKey.getName(), jobKey.getGroup())
                                .startAt(startAt)
                                .build();
                jobDetail.getJobDataMap().put("fixedDelay", scheduleConfig.getFixedDelay());
                scheduler.scheduleJob(jobDetail, trigger);
            }

            tasks.put(jobName, task);
            logger.info("Successfully scheduled task '{}' with mode {}", jobName, mode);

        } catch (SchedulerException e) {
            logger.error("Failed to schedule task '{}'", jobName, e);
            throw new RuntimeException("Failed to schedule task: " + jobName, e);
        }

        return task;
    }

    /**
     * Cancel a scheduled agent task.
     *
     * <p>This removes the Job and Trigger from the Quartz scheduler.
     *
     * @param name The name of the agent/task to cancel
     * @return {@code true} if the task was found and cancelled, {@code false} otherwise
     */
    @Override
    public boolean cancel(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to cancel non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.deleteJob(task.getJobKey());
            tasks.remove(name);
            logger.info("Successfully cancelled task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to cancel task '{}'", name, e);
            return false;
        }
    }

    /**
     * Retrieve a scheduled agent task by name.
     *
     * @param name The name of the agent
     * @return The task handle, or {@code null} if not found
     */
    @Override
    public QuartzScheduleAgentTask getScheduledAgent(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        QuartzScheduleAgentTask task = tasks.get(name);
        if (task != null) {
            return task;
        }
        // If it is not in the local cache,
        // try to load the task information from the Quartz scheduler (this may happen in cluster
        // mode)
        return loadTaskFromQuartz(name);
    }

    /**
     * Retrieve all scheduled agent tasks.
     *
     * <p>This method retrieves all tasks from both the local cache and the Quartz scheduler.
     * Even if a task is not in the local memory (e.g., created by another node in a cluster),
     * it will be retrieved from Quartz.
     *
     * @return A list of all tasks managed by this scheduler
     */
    @Override
    public List<ScheduleAgentTask> getAllScheduleAgentTasks() {
        List<ScheduleAgentTask> allTasks = new ArrayList<>();
        try {
            // Get all job keys in the "agentscope-quartz" group
            Set<JobKey> jobKeys =
                    scheduler.getJobKeys(GroupMatcher.jobGroupEquals("agentscope-quartz"));

            for (JobKey jobKey : jobKeys) {
                String name = jobKey.getName();
                // Try to get from local cache first to preserve full config
                QuartzScheduleAgentTask task = tasks.get(name);
                if (task == null) {
                    // Fallback to loading from Quartz
                    task = loadTaskFromQuartz(name);
                }
                if (task != null) {
                    allTasks.add(task);
                }
            }
        } catch (SchedulerException e) {
            logger.error("Failed to retrieve all scheduled tasks from Quartz", e);
        }
        return allTasks;
    }

    /**
     * Shutdown the scheduler.
     *
     * <p>This stops the Quartz scheduler and releases resources.
     *
     * @see Scheduler#shutdown(boolean)
     */
    @Override
    public void shutdown() {
        logger.info("Shutting down QuartzAgentScheduler...");
        try {
            scheduler.shutdown(true);
            logger.info("QuartzAgentScheduler shutdown completed");
        } catch (SchedulerException e) {
            logger.error("Error occurred while shutting down QuartzAgentScheduler", e);
        } finally {
            QuartzAgentSchedulerRegistry.unregister(schedulerId);
        }
    }

    @Override
    public String getSchedulerType() {
        return "quartz";
    }

    /**
     * Pause a scheduled task.
     *
     * @param name The name of the agent/task to pause
     * @return {@code true} if successfully paused, {@code false} otherwise
     */
    public boolean pause(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to pause non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.pauseJob(task.getJobKey());
            logger.info("Successfully paused task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to pause task '{}'", name, e);
            return false;
        }
    }

    /**
     * Resume a paused task.
     *
     * @param name The name of the agent/task to resume
     * @return {@code true} if successfully resumed, {@code false} otherwise
     */
    public boolean resume(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to resume non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.resumeJob(task.getJobKey());
            logger.info("Successfully resumed task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to resume task '{}'", name, e);
            return false;
        }
    }

    /**
     * Interrupt a running task.
     *
     * @param name The name of the agent/task to interrupt
     * @return {@code true} if the interrupt signal was sent, {@code false} otherwise
     */
    public boolean interrupt(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            logger.warn("Attempt to interrupt non-existent task '{}'", name);
            return false;
        }
        try {
            scheduler.interrupt(task.getJobKey());
            logger.info("Interrupt signal sent for task '{}'", name);
            return true;
        } catch (SchedulerException e) {
            logger.error("Failed to interrupt task '{}'", name, e);
            return false;
        }
    }

    /**
     * Reschedules the task for the next execution based on the fixed delay.
     *
     * @param jobKey The JobKey of the task
     * @param delay  The delay in milliseconds
     */
    void rescheduleNextFixedDelay(JobKey jobKey, long delay) {
        Date next = new Date(System.currentTimeMillis() + delay);
        TriggerKey triggerKey = new TriggerKey(jobKey.getName(), jobKey.getGroup());
        Trigger trigger =
                TriggerBuilder.newTrigger()
                        .withIdentity(triggerKey)
                        .forJob(jobKey)
                        .startAt(next)
                        .build();
        try {
            if (scheduler.rescheduleJob(triggerKey, trigger) == null) {
                scheduler.scheduleJob(trigger);
            }
            logger.debug("Rescheduled fixed delay task '{}' at {}", jobKey.getName(), next);
        } catch (SchedulerException e) {
            logger.error("Failed to reschedule fixed delay task '{}'", jobKey.getName(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the current status of a scheduled task.
     *
     * @param name The name of the agent/task
     * @return The Quartz TriggerState, or {@code null} if not found
     */
    public TriggerState getStatus(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        QuartzScheduleAgentTask task = getScheduledAgent(name);
        if (task == null) {
            return null;
        }
        TriggerKey triggerKey =
                new TriggerKey(task.getJobKey().getName(), task.getJobKey().getGroup());
        try {
            return scheduler.getTriggerState(triggerKey);
        } catch (SchedulerException e) {
            logger.error("Failed to get status for task '{}'", name, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Load task information from the Quartz scheduler and reconstruct the QuartzScheduleAgentTask object.
     * This is very useful in a distributed environment or after an application restart.
     * Even if there is no task information in the local memory,
     * tasks can still be managed.
     */
    private QuartzScheduleAgentTask loadTaskFromQuartz(String name) {
        JobKey jobKey = new JobKey(name, "agentscope-quartz");
        try {
            if (!scheduler.checkExists(jobKey)) {
                return null;
            }

            JobDetail jobDetail = scheduler.getJobDetail(jobKey);
            if (jobDetail == null) {
                return null;
            }

            // Reconstruct ScheduleConfig from Trigger
            List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
            ScheduleConfig scheduleConfig = null;
            if (triggers != null && !triggers.isEmpty()) {
                Trigger trigger = triggers.get(0);
                if (trigger instanceof org.quartz.CronTrigger ct) {
                    scheduleConfig =
                            ScheduleConfig.builder()
                                    .cron(ct.getCronExpression())
                                    .zoneId(
                                            ct.getTimeZone() != null
                                                    ? ct.getTimeZone().getID()
                                                    : null)
                                    .build();
                } else if (trigger instanceof org.quartz.SimpleTrigger st) {
                    scheduleConfig =
                            ScheduleConfig.builder().fixedRate(st.getRepeatInterval()).build();
                }
            }

            if (scheduleConfig == null) {
                scheduleConfig = ScheduleConfig.builder().build();
            }

            // Construct a minimal RuntimeAgentConfig,
            // which is only used to support task control operations (such as pause, resume, cancel)
            RuntimeAgentConfig agentConfig =
                    RuntimeAgentConfig.builder()
                            .name(name)
                            .modelConfig(
                                    new ModelConfig() {
                                        @Override
                                        public String getModelName() {
                                            return "unknown";
                                        }

                                        @Override
                                        public Model createModel() {
                                            return null;
                                        }
                                    })
                            .build();

            return new QuartzScheduleAgentTask(
                    agentConfig, scheduleConfig, this, jobKey, scheduler);

        } catch (SchedulerException e) {
            logger.error("Failed to load task '{}' from Quartz", name, e);
            return null;
        }
    }

    /**
     * Builder for {@link QuartzAgentScheduler}.
     */
    public static class Builder {
        private boolean autoStart = true;
        private org.quartz.Scheduler scheduler;
        private String schedulerId = "default-scheduler";

        /**
         * Set the scheduler ID.
         *
         * @param schedulerId The scheduler ID
         * @return This builder
         */
        public Builder schedulerId(String schedulerId) {
            this.schedulerId = schedulerId;
            return this;
        }

        /**
         * Set a custom Quartz Scheduler.
         *
         * @param scheduler The Scheduler to use
         * @return This builder
         */
        public Builder scheduler(org.quartz.Scheduler scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        /**
         * Set whether to automatically start the scheduler upon build.
         * Default is {@code true}.
         *
         * @param autoStart {@code true} to start automatically
         * @return This builder
         */
        public Builder autoStart(boolean autoStart) {
            this.autoStart = autoStart;
            return this;
        }

        /**
         * Build the QuartzAgentScheduler instance.
         *
         * @return A new QuartzAgentScheduler
         * @throws RuntimeException if initialization fails
         */
        public QuartzAgentScheduler build() {
            Scheduler scheduler = this.scheduler;
            boolean internal = false;
            try {
                if (scheduler == null) {
                    scheduler = new StdSchedulerFactory().getScheduler();
                    internal = true;
                }

                if (scheduler == null) {
                    throw new IllegalArgumentException("Scheduler must not be null");
                }

                if (autoStart) {
                    scheduler.start();
                    logger.info("Quartz scheduler started successfully");
                }
                QuartzAgentScheduler agentScheduler =
                        new QuartzAgentScheduler(scheduler, schedulerId);
                agentScheduler.register();
                return agentScheduler;
            } catch (SchedulerException e) {
                if (internal && scheduler != null) {
                    try {
                        scheduler.shutdown();
                    } catch (SchedulerException ex) {
                        logger.warn("Failed to shutdown scheduler on error", ex);
                    }
                }
                logger.error("Failed to build QuartzAgentScheduler", e);
                throw new RuntimeException(e);
            }
        }
    }
}
