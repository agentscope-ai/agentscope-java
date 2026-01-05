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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

/** Unit tests for {@link QuartzScheduleAgentTask}. */
class QuartzScheduleAgentTaskTest {

    private QuartzScheduleAgentTask task;
    private Scheduler mockQuartzScheduler;
    private JobKey jobKey;

    @BeforeEach
    void setUp() {
        RuntimeAgentConfig agentConfig = mock(RuntimeAgentConfig.class);
        ScheduleConfig scheduleConfig = mock(ScheduleConfig.class);
        QuartzAgentScheduler scheduler = mock(QuartzAgentScheduler.class);
        mockQuartzScheduler = mock(Scheduler.class);
        jobKey = new JobKey("testJob", "testGroup");

        task =
                new QuartzScheduleAgentTask(
                        agentConfig, scheduleConfig, scheduler, jobKey, mockQuartzScheduler);
    }

    @Test
    void testGetJobKey() {
        assertEquals(jobKey, task.getJobKey());
    }

    @Test
    void testPause() throws SchedulerException {
        task.pause();
        verify(mockQuartzScheduler).pauseJob(jobKey);
    }

    @Test
    void testPauseWithException() throws SchedulerException {
        doThrow(new SchedulerException("Pause failed")).when(mockQuartzScheduler).pauseJob(jobKey);
        assertThrows(RuntimeException.class, () -> task.pause());
    }

    @Test
    void testResume() throws SchedulerException {
        task.resume();
        verify(mockQuartzScheduler).resumeJob(jobKey);
    }

    @Test
    void testResumeWithException() throws SchedulerException {
        doThrow(new SchedulerException("Resume failed"))
                .when(mockQuartzScheduler)
                .resumeJob(jobKey);
        assertThrows(RuntimeException.class, () -> task.resume());
    }
}
