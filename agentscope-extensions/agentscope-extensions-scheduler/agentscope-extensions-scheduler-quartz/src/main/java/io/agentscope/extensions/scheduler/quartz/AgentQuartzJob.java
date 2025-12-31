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

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

/**
 * Quartz Job implementation that executes an AgentScope agent task.
 *
 * <p>This job retrieves the corresponding {@link QuartzAgentScheduler} and
 * {@link QuartzScheduleAgentTask} using job data, and then executes the agent's run logic.
 * It also handles the rescheduling for {@code FIXED_DELAY} tasks.
 */
public class AgentQuartzJob implements InterruptableJob {

    private volatile boolean interrupted;

    /**
     * Executes the agent task.
     *
     * @param context The Quartz JobExecutionContext
     * @throws JobExecutionException if the job execution fails
     */
    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        if (interrupted) {
            return;
        }
        String schedulerId = context.getJobDetail().getJobDataMap().getString("schedulerId");
        if (schedulerId == null || schedulerId.trim().isEmpty()) {
            schedulerId = "default-scheduler";
        }
        String taskName = context.getJobDetail().getJobDataMap().getString("taskName");
        QuartzAgentScheduler scheduler = QuartzAgentSchedulerRegistry.get(schedulerId);
        if (scheduler == null) {
            return;
        }
        QuartzScheduleAgentTask task = scheduler.getScheduledAgent(taskName);
        if (task == null) {
            return;
        }
        try {
            ScheduleAgentTask<io.agentscope.core.message.Msg> t = task;
            // Blocking is acceptable here because Quartz jobs run in their own dedicated thread
            // pool
            // and are executed synchronously; we need to wait for the reactive pipeline to
            // complete.
            t.run().block();
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
        if (interrupted) {
            return;
        }
        ScheduleMode mode = task.getScheduleConfig().getScheduleMode();
        if (mode == ScheduleMode.FIXED_DELAY) {
            long delay = context.getJobDetail().getJobDataMap().getLongValue("fixedDelay");
            scheduler.rescheduleNextFixedDelay(context.getJobDetail().getKey(), delay);
        }
    }

    /**
     * Interrupts the currently running job.
     */
    @Override
    public void interrupt() {
        interrupted = true;
    }
}
