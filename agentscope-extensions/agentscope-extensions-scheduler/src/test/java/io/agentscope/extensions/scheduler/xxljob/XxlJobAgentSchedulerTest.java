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
package io.agentscope.extensions.scheduler.xxljob;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.xxl.job.core.executor.XxlJobExecutor;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link XxlJobAgentScheduler}. */
class XxlJobAgentSchedulerTest {

    private XxlJobExecutor mockExecutor;
    private XxlJobAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        mockExecutor = mock(XxlJobExecutor.class);
        scheduler = new XxlJobAgentScheduler(mockExecutor);
    }

    @Test
    void testConstructorWithValidExecutor() {
        XxlJobAgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);
        assertNotNull(scheduler);
    }

    @Test
    void testConstructorWithNullExecutor() {
        assertThrows(IllegalArgumentException.class, () -> new XxlJobAgentScheduler(null));
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

        ScheduleAgentTask task = scheduler.schedule(agentConfig);

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

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
    }

    @Test
    void testScheduleWithUnsupportedScheduleMode() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().cron("0 0 8 * * ?").build();

        assertThrows(
                UnsupportedOperationException.class,
                () -> scheduler.schedule(agentConfig, scheduleConfig));
    }

    @Test
    void testCancelThrowsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class, () -> scheduler.cancel("TestAgent"));
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

        scheduler.schedule(agentConfig);

        ScheduleAgentTask task = scheduler.getScheduledAgent("TestAgent");
        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetScheduledAgentWithNullName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent(null);
        assertTrue(task == null);
    }

    @Test
    void testGetScheduledAgentWithEmptyName() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("");
        assertTrue(task == null);
    }

    @Test
    void testGetScheduledAgentNotFound() {
        ScheduleAgentTask task = scheduler.getScheduledAgent("NonExistentAgent");
        assertTrue(task == null);
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

        scheduler.schedule(agentConfig1);
        scheduler.schedule(agentConfig2);

        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
        assertEquals(2, tasks.size());
    }

    @Test
    void testGetSchedulerType() {
        assertEquals("xxl-job", scheduler.getSchedulerType());
    }

    @Test
    void testShutdown() {
        // Should not throw exception
        scheduler.shutdown();
    }
}
