/*
 * Copyright 2024-2025 the original author or authors.
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

public class AgentQuartzJob implements InterruptableJob {

    private volatile boolean interrupted;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String schedulerId = context.getJobDetail().getJobDataMap().getString("schedulerId");
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
            t.run().block();
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
        ScheduleMode mode = task.getScheduleConfig().getScheduleMode();
        if (mode == ScheduleMode.FIXED_DELAY) {
            long delay = context.getJobDetail().getJobDataMap().getLongValue("fixedDelay");
            scheduler.rescheduleNextFixedDelay(context.getJobDetail().getKey(), delay);
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
    }
}
