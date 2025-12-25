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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.AgentConfig;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.Trigger;
import org.quartz.Trigger.TriggerState;

/** Unit tests for {@link QuartzAgentScheduler}. */
class QuartzAgentSchedulerTest {

    private QuartzAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = QuartzAgentScheduler.builder().autoStart(true).build();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void testConstructorWithValidScheduler() {
        assertNotNull(scheduler);
    }

    @Test
    void testScheduleWithDefaultScheduleConfig() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        // Use CRON as default for testing to avoid immediate execution issues if any
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testScheduleWithNullAgentConfig() {
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();

        assertThrows(
                IllegalArgumentException.class, () -> scheduler.schedule(null, scheduleConfig));
    }

    @Test
    void testScheduleWithNullScheduleConfig() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        assertThrows(IllegalArgumentException.class, () -> scheduler.schedule(agentConfig, null));
    }

    @Test
    void testScheduleWithEmptyAgentName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuntimeAgentConfig.builder()
                                .name("")
                                .modelConfig(modelConfig)
                                .sysPrompt("Test prompt")
                                .build());
    }

    @Test
    void testCancel() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        boolean result = scheduler.cancel("TestAgent");
        assertTrue(result);

        assertNull(scheduler.getScheduledAgent("TestAgent"));
    }

    @Test
    void testCancelNonExistent() {
        boolean result = scheduler.cancel("NonExistentAgent");
        assertFalse(result);
    }

    @Test
    void testGetScheduledAgent() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        ScheduleAgentTask task = scheduler.getScheduledAgent("TestAgent");
        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetScheduledAgentWithNullName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent(null);
        assertNull(task);
    }

    @Test
    void testGetScheduledAgentWithEmptyName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("");
        assertNull(task);
    }

    @Test
    void testGetScheduledAgentNotFound() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("NonExistentAgent");
        assertNull(task);
    }

    @Test
    void testGetAllScheduleAgentTasks() {
        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(0, tasks.size());
    }

    @Test
    void testGetAllScheduleAgentTasksAfterScheduling() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig1, scheduleConfig);
        scheduler.schedule(agentConfig2, scheduleConfig);

        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(2, tasks.size());
    }

    @Test
    void testScheduleAlreadyScheduledTask() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        // Schedule the first time
        ScheduleAgentTask task1 = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task1);

        // Schedule the same agent again
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task2);

        // Should return the same instance (based on implementation logic)
        assertEquals(task1, task2);
    }

    @Test
    void testScheduleWithAgentConfigConversion() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        // Create a non-RuntimeAgentConfig instance using builder
        AgentConfig agentConfig =
                AgentConfig.builder()
                        .name("TestConversionAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test conversion")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals("TestConversionAgent", task.getName());
    }

    @Test
    void testScheduleWithWhitespaceOnlyName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        // Should throw exception for whitespace-only names
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        RuntimeAgentConfig.builder()
                                .name("   ")
                                .modelConfig(modelConfig)
                                .sysPrompt("Test prompt")
                                .build());
    }

    @Test
    void testPauseAndResume() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        scheduler.schedule(agentConfig, scheduleConfig);

        boolean pauseResult = scheduler.pause("TestAgent");
        assertTrue(pauseResult);

        TriggerState statePaused = scheduler.getStatus("TestAgent");
        assertEquals(TriggerState.PAUSED, statePaused);

        boolean resumeResult = scheduler.resume("TestAgent");
        assertTrue(resumeResult);

        TriggerState stateNormal = scheduler.getStatus("TestAgent");
        // Depending on timing, it could be NORMAL or other states, but usually NORMAL for cron
        assertEquals(TriggerState.NORMAL, stateNormal);
    }

    @Test
    void testPauseNonExistent() {
        assertFalse(scheduler.pause("NonExistent"));
    }

    @Test
    void testResumeNonExistent() {
        assertFalse(scheduler.resume("NonExistent"));
    }

    @Test
    void testGetStatusNonExistent() {
        assertNull(scheduler.getStatus("NonExistent"));
    }

    @Test
    void testScheduleWithNoneScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build(); // Default is NONE

        // NONE mode should work (task registered but not scheduled)
        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertNotNull(scheduler.getScheduledAgent("TestAgent"));
    }

    @Test
    void testScheduleWithSpecialCharactersInName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        String specialName = "Test-Agent_123!@#";

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(specialName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals(specialName, task.getName());
    }

    @Test
    void testScheduleWithLongAgentName() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        String longName = "A".repeat(200); // Very long name

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name(longName)
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals(longName, task.getName());
    }

    @Test
    void testScheduleWithDifferentModelConfigs() {
        DashScopeModelConfig modelConfig1 =
                DashScopeModelConfig.builder().apiKey("test-key-1").modelName("qwen-max").build();

        DashScopeModelConfig modelConfig2 =
                DashScopeModelConfig.builder().apiKey("test-key-2").modelName("qwen-plus").build();

        RuntimeAgentConfig agentConfig1 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent1")
                        .modelConfig(modelConfig1)
                        .sysPrompt("Test prompt 1")
                        .build();

        RuntimeAgentConfig agentConfig2 =
                RuntimeAgentConfig.builder()
                        .name("TestAgent2")
                        .modelConfig(modelConfig2)
                        .sysPrompt("Test prompt 2")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        ScheduleAgentTask task1 = scheduler.schedule(agentConfig1, scheduleConfig);
        ScheduleAgentTask task2 = scheduler.schedule(agentConfig2, scheduleConfig);

        assertNotNull(task1);
        assertNotNull(task2);
        assertEquals("TestAgent1", task1.getName());
        assertEquals("TestAgent2", task2.getName());
    }

    @Test
    void testGetSchedulerType() {
        assertEquals("quartz", scheduler.getSchedulerType());
    }

    @Test
    void testShutdown() {
        // Should not throw exception
        scheduler.shutdown();
        // Calling it again should be safe (idempotent or handled gracefully)
        scheduler.shutdown();
    }

    @Test
    void testBuilderWithAutoStartFalse() {
        QuartzAgentScheduler manualScheduler =
                QuartzAgentScheduler.builder().autoStart(false).build();
        assertNotNull(manualScheduler);
        manualScheduler.shutdown();
    }

    @Test
    void testBuilderWithCustomFactory() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler customScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();
        assertNotNull(customScheduler);

        // Verify start() was called (default autoStart is true)
        verify(mockScheduler).start();

        customScheduler.shutdown();
    }

    @Test
    void testBuilderWithCustomFactoryThrowingException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        when(mockFactory.getScheduler()).thenThrow(new SchedulerException("Factory failed"));

        QuartzAgentScheduler.Builder builder = QuartzAgentScheduler.builder().factory(mockFactory);
        assertThrows(RuntimeException.class, builder::build);
    }

    @Test
    void testScheduleFixedRate() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FixedRateAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().fixedRate(1000L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertEquals("FixedRateAgent", task.getName());
    }

    @Test
    void testScheduleFixedDelay() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FixedDelayAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().fixedDelay(1000L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
        assertEquals("FixedDelayAgent", task.getName());
    }

    @Test
    void testScheduleWithInitialDelay() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("InitialDelayAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().cron("0 0 8 * * ?").initialDelay(1000L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
    }

    @Test
    void testScheduleWithZoneId() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("ZoneIdAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().cron("0 0 8 * * ?").zoneId("UTC").build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);
        assertNotNull(task);
    }

    @Test
    void testScheduleThrowingSchedulerException() throws SchedulerException {
        // Mock infrastructure
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        // Setup exception
        doThrow(new SchedulerException("Schedule failed"))
                .when(mockScheduler)
                .scheduleJob(any(JobDetail.class), any(Trigger.class));

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertThrows(
                RuntimeException.class,
                () -> mockAgentScheduler.schedule(agentConfig, scheduleConfig));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testCancelThrowingSchedulerException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        // We need to put a task in the map to reach the deleteJob call.
        // But the map is private. However, schedule() puts it in.
        // So we schedule successfully, then mock deleteJob failure.

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("CancelFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Cancel failed")).when(mockScheduler).deleteJob(any());

        boolean result = mockAgentScheduler.cancel("CancelFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testPauseThrowingSchedulerException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("PauseFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Pause failed")).when(mockScheduler).pauseJob(any());

        boolean result = mockAgentScheduler.pause("PauseFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testResumeThrowingSchedulerException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("ResumeFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Resume failed")).when(mockScheduler).resumeJob(any());

        boolean result = mockAgentScheduler.resume("ResumeFailAgent");
        assertFalse(result);

        mockAgentScheduler.shutdown();
    }

    @Test
    void testGetStatusThrowingSchedulerException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("StatusFailAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();
        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        mockAgentScheduler.schedule(agentConfig, scheduleConfig);

        doThrow(new SchedulerException("Status failed")).when(mockScheduler).getTriggerState(any());

        assertThrows(RuntimeException.class, () -> mockAgentScheduler.getStatus("StatusFailAgent"));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testShutdownThrowingSchedulerException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        doThrow(new SchedulerException("Shutdown failed")).when(mockScheduler).shutdown(true);

        // Should catch and log error, not throw
        mockAgentScheduler.shutdown();

        verify(mockScheduler).shutdown(true);
    }

    @Test
    void testRescheduleNextFixedDelay() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        JobKey jobKey = new JobKey("testJob", "testGroup");
        long delay = 1000L;

        mockAgentScheduler.rescheduleNextFixedDelay(jobKey, delay);

        verify(mockScheduler).scheduleJob(any(Trigger.class));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testRescheduleNextFixedDelayThrowingException() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        Scheduler mockScheduler = mock(Scheduler.class);
        when(mockFactory.getScheduler()).thenReturn(mockScheduler);

        QuartzAgentScheduler mockAgentScheduler =
                QuartzAgentScheduler.builder().factory(mockFactory).build();

        JobKey jobKey = new JobKey("testJob", "testGroup");

        doThrow(new SchedulerException("Reschedule failed"))
                .when(mockScheduler)
                .scheduleJob(any(Trigger.class));

        assertThrows(
                RuntimeException.class,
                () -> mockAgentScheduler.rescheduleNextFixedDelay(jobKey, 1000L));

        mockAgentScheduler.shutdown();
    }

    @Test
    void testBuilderWithFactoryReturningNullScheduler() throws SchedulerException {
        SchedulerFactory mockFactory = mock(SchedulerFactory.class);
        when(mockFactory.getScheduler()).thenReturn(null);

        QuartzAgentScheduler.Builder builder = QuartzAgentScheduler.builder().factory(mockFactory);

        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
