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
package io.agentscope.extensions.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.xxl.job.core.executor.XxlJobExecutor;
import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import io.agentscope.extensions.scheduler.xxljob.XxlJobAgentScheduler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link AgentScheduler}. */
class AgentSchedulerTest {

    @Test
    void testScheduleMethodWithXxlJobImpl() {
        XxlJobExecutor mockExecutor = mock(XxlJobExecutor.class);
        AgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);

        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("Test prompt")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        assertNotNull(task);
        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testGetScheduledAgentWithXxlJobImpl() {
        XxlJobExecutor mockExecutor = mock(XxlJobExecutor.class);
        XxlJobAgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);

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
    }

    @Test
    void testGetAllScheduleAgentTasksWithXxlJobImpl() {
        XxlJobExecutor mockExecutor = mock(XxlJobExecutor.class);
        AgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);

        List<ScheduleAgentTask> tasks = scheduler.getAllScheduleAgentTasks();
        assertNotNull(tasks);
    }

    @Test
    void testShutdownWithXxlJobImpl() {
        XxlJobExecutor mockExecutor = mock(XxlJobExecutor.class);
        AgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);

        // Should not throw exception
        scheduler.shutdown();
    }

    @Test
    void testGetSchedulerTypeWithXxlJobImpl() {
        XxlJobExecutor mockExecutor = mock(XxlJobExecutor.class);
        AgentScheduler scheduler = new XxlJobAgentScheduler(mockExecutor);

        assertEquals("xxl-job", scheduler.getSchedulerType());
    }
}
