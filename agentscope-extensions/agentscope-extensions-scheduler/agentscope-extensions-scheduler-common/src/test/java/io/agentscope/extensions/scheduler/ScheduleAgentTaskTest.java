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
import static org.mockito.Mockito.mock;

import io.agentscope.extensions.scheduler.config.DashScopeModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ScheduleAgentTask}. */
class ScheduleAgentTaskTest {

    @Test
    void testInterfaceImplementation() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig runtimeAgentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a test assistant")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
        AgentScheduler mockScheduler = mock(AgentScheduler.class);

        // BaseScheduleAgentTask implements ScheduleAgentTask interface
        ScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertNotNull(task);
    }

    @Test
    void testGetIdFromInterface() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig runtimeAgentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a test assistant")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
        AgentScheduler mockScheduler = mock(AgentScheduler.class);

        ScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        String id = task.getId();
        assertNotNull(id);
        assertFalse(id.isEmpty());
    }

    @Test
    void testGetNameFromInterface() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig runtimeAgentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a test assistant")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
        AgentScheduler mockScheduler = mock(AgentScheduler.class);

        ScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        assertEquals("TestAgent", task.getName());
    }

    @Test
    void testCancelFromInterface() {
        DashScopeModelConfig modelConfig =
                DashScopeModelConfig.builder().apiKey("test-key").modelName("qwen-max").build();

        RuntimeAgentConfig runtimeAgentConfig =
                RuntimeAgentConfig.builder()
                        .name("TestAgent")
                        .modelConfig(modelConfig)
                        .sysPrompt("You are a test assistant")
                        .build();

        ScheduleConfig scheduleConfig = ScheduleConfig.builder().build();
        AgentScheduler mockScheduler = mock(AgentScheduler.class);

        ScheduleAgentTask task =
                new BaseScheduleAgentTask(runtimeAgentConfig, scheduleConfig, mockScheduler);

        // Should not throw exception
        task.cancel();
    }
}
