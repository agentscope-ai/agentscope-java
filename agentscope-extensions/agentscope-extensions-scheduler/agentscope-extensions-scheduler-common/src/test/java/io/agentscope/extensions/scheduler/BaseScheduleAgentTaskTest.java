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
package io.agentscope.extensions.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BaseScheduleAgentTask}. */
class BaseScheduleAgentTaskTest {

    private RuntimeAgentConfig runtimeAgentConfig;
    private ScheduleConfig scheduleConfig;
    private AgentScheduler mockScheduler;

    @BeforeEach
    void setUp() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        runtimeAgentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a test assistant")
                        .build();

        scheduleConfig = ScheduleConfig.builder().build();

        mockScheduler = mock(AgentScheduler.class);
    }

    @Test
    void testConstructor() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertNotNull(task);
        assertNotNull(task.getId());
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetId() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        String id = task.getId();
        assertNotNull(id);
        assertFalse(id.isEmpty());
    }

    @Test
    void testGetName() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testIsCancelled() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertFalse(task.isCancelled());
    }

    @Test
    void testCancel() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        task.cancel();

        assertTrue(task.isCancelled());
        verify(mockScheduler).cancel("TestAgent");
    }

    @Test
    void testGetScheduleConfig() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals(scheduleConfig, task.getScheduleConfig());
    }

    @Test
    void testGetAgentConfig() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals(runtimeAgentConfig, task.getAgentConfig());
    }

    @Test
    void testGetExecutionCount() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals(0, task.getExecutionCount());
    }

    @Test
    void testIncrementExecutionCount() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals(0, task.getExecutionCount());

        task.incrementExecutionCount();
        assertEquals(1, task.getExecutionCount());

        task.incrementExecutionCount();
        assertEquals(2, task.getExecutionCount());
    }

    @Test
    void testRunWithCancelledTask() {
        BaseScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        task.cancel();

        assertThrows(IllegalStateException.class, () -> task.run());
    }

    @Test
    void testGetIdUniqueness() {
        BaseScheduleAgentTask task1 =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);
        BaseScheduleAgentTask task2 =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertFalse(task1.getId().equals(task2.getId()));
    }
}
