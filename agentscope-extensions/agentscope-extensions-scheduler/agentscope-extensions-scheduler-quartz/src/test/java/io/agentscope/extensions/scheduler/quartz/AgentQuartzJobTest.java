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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.config.ScheduleMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import reactor.core.publisher.Mono;

/** Unit tests for {@link AgentQuartzJob}. */
class AgentQuartzJobTest {

    private AgentQuartzJob agentQuartzJob;
    private QuartzAgentScheduler mockScheduler;
    private JobExecutionContext mockContext;
    private JobDetail mockJobDetail;
    private JobDataMap mockJobDataMap;
    private QuartzScheduleAgentTask mockTask;
    private final String schedulerId = "test-scheduler-id";
    private final String taskName = "test-task-name";

    @BeforeEach
    void setUp() {
        agentQuartzJob = new AgentQuartzJob();
        mockScheduler = mock(QuartzAgentScheduler.class);
        mockContext = mock(JobExecutionContext.class);
        mockJobDetail = mock(JobDetail.class);
        mockJobDataMap = mock(JobDataMap.class);
        mockTask = mock(QuartzScheduleAgentTask.class);

        // Setup common mock behavior
        when(mockContext.getJobDetail()).thenReturn(mockJobDetail);
        when(mockJobDetail.getJobDataMap()).thenReturn(mockJobDataMap);
        when(mockJobDataMap.getString("schedulerId")).thenReturn(schedulerId);
        when(mockJobDataMap.getString("taskName")).thenReturn(taskName);

        // Register mock scheduler
        QuartzAgentSchedulerRegistry.register(schedulerId, mockScheduler);
    }

    @AfterEach
    void tearDown() {
        QuartzAgentSchedulerRegistry.unregister(schedulerId);
    }

    @Test
    void testExecuteSuccess() throws JobExecutionException {
        // Setup task
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        // Setup ScheduleConfig (default NONE or CRON)
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);

        // Execute
        agentQuartzJob.execute(mockContext);

        // Verify
        verify(mockTask, times(1)).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }

    @Test
    void testExecuteWithMissingSchedulerIdFallback() throws JobExecutionException {
        // Setup missing schedulerId
        when(mockJobDataMap.getString("schedulerId")).thenReturn(null);

        // Register default scheduler
        QuartzAgentScheduler defaultScheduler = mock(QuartzAgentScheduler.class);
        QuartzAgentSchedulerRegistry.register("default-scheduler", defaultScheduler);

        // Setup task on default scheduler
        QuartzScheduleAgentTask defaultTask = mock(QuartzScheduleAgentTask.class);
        when(defaultScheduler.getScheduledAgent(taskName)).thenReturn(defaultTask);
        when(defaultTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.CRON);
        when(defaultTask.getScheduleConfig()).thenReturn(scheduleConfig);

        try {
            // Execute
            agentQuartzJob.execute(mockContext);

            // Verify
            verify(defaultTask, times(1)).run();
        } finally {
            QuartzAgentSchedulerRegistry.unregister("default-scheduler");
        }
    }

    @Test
    void testExecuteSchedulerNotFound() throws JobExecutionException {
        // Unregister scheduler to simulate not found
        QuartzAgentSchedulerRegistry.unregister(schedulerId);

        agentQuartzJob.execute(mockContext);

        // Verify no interaction with scheduler (since it's null) or task
        verify(mockScheduler, never()).getScheduledAgent(any());
    }

    @Test
    void testExecuteTaskNotFound() throws JobExecutionException {
        // Scheduler found, but task not found
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(null);

        agentQuartzJob.execute(mockContext);

        // Verify
        verify(mockTask, never()).run();
    }

    @Test
    void testExecuteWithException() {
        // Setup task to throw exception
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run()).thenThrow(new RuntimeException("Execution failed"));

        // Verify JobExecutionException is thrown
        assertThrows(JobExecutionException.class, () -> agentQuartzJob.execute(mockContext));
    }

    @Test
    void testExecuteFixedDelay() throws JobExecutionException {
        // Setup task
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        when(mockTask.run())
                .thenReturn(
                        Mono.just(
                                Msg.builder()
                                        .content(TextBlock.builder().text("test").build())
                                        .build()));

        // Setup ScheduleConfig for FIXED_DELAY
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);

        // Setup fixed delay value in JobDataMap
        long delay = 1000L;
        when(mockJobDataMap.getLongValue("fixedDelay")).thenReturn(delay);
        JobKey jobKey = new JobKey(taskName);
        when(mockJobDetail.getKey()).thenReturn(jobKey);

        // Execute
        agentQuartzJob.execute(mockContext);

        // Verify run called and reschedule called
        verify(mockTask, times(1)).run();
        verify(mockScheduler, times(1)).rescheduleNextFixedDelay(jobKey, delay);
    }

    @Test
    void testInterrupt() {
        assertDoesNotThrow(() -> agentQuartzJob.interrupt());
        // Since interrupted field is private and used internally (not exposed or used in logic much
        // yet),
        // we just verify it doesn't throw.
    }

    @Test
    void testExecuteInterruptedSkipsRun() throws JobExecutionException {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        agentQuartzJob.interrupt();
        agentQuartzJob.execute(mockContext);
        verify(mockTask, never()).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }

    @Test
    void testExecuteInterruptedSkipsRescheduleFixedDelay() throws JobExecutionException {
        when(mockScheduler.getScheduledAgent(taskName)).thenReturn(mockTask);
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        when(scheduleConfig.getScheduleMode()).thenReturn(ScheduleMode.FIXED_DELAY);
        when(mockTask.getScheduleConfig()).thenReturn(scheduleConfig);
        long delay = 1000L;
        when(mockJobDataMap.getLongValue("fixedDelay")).thenReturn(delay);
        JobKey jobKey = new JobKey(taskName);
        when(mockJobDetail.getKey()).thenReturn(jobKey);

        agentQuartzJob.interrupt();
        agentQuartzJob.execute(mockContext);

        verify(mockTask, never()).run();
        verify(mockScheduler, never()).rescheduleNextFixedDelay(any(), anyLong());
    }
}
