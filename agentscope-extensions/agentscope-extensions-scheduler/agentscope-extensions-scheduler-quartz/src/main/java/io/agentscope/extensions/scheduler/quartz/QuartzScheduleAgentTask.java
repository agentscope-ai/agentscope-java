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

import io.agentscope.extensions.scheduler.BaseScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/**
 * Implementation of {@link io.agentscope.extensions.scheduler.ScheduleAgentTask} for Quartz.
 *
 * <p>This class wraps the Quartz {@link JobKey} and provides access to the underlying
 * Quartz scheduler for operations like pausing and resuming.
 */
class QuartzScheduleAgentTask extends BaseScheduleAgentTask {

    private final JobKey jobKey;
    private final Scheduler quartzScheduler;

    /**
     * Constructs a new QuartzScheduleAgentTask.
     *
     * @param agentConfig    The agent configuration
     * @param scheduleConfig The schedule configuration
     * @param scheduler      The parent QuartzAgentScheduler
     * @param jobKey         The Quartz JobKey
     * @param quartzScheduler The Quartz Scheduler instance
     */
    QuartzScheduleAgentTask(
            RuntimeAgentConfig agentConfig,
            ScheduleConfig scheduleConfig,
            QuartzAgentScheduler scheduler,
            JobKey jobKey,
            Scheduler quartzScheduler) {
        super(agentConfig, scheduleConfig, scheduler);
        this.jobKey = jobKey;
        this.quartzScheduler = quartzScheduler;
    }

    /**
     * Get the Quartz JobKey associated with this task.
     *
     * @return The JobKey for this task
     */
    JobKey getJobKey() {
        return jobKey;
    }

    /**
     * Pause the Quartz job associated with this task.
     */
    void pause() {
        try {
            quartzScheduler.pauseJob(jobKey);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Resume the Quartz job associated with this task.
     */
    void resume() {
        try {
            quartzScheduler.resumeJob(jobKey);
        } catch (SchedulerException e) {
            throw new RuntimeException(e);
        }
    }
}
