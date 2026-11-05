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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class QuartzFixedDelayIntegrationTest {

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
    void testFixedDelayReschedulesAndRunsMultipleTimes() throws InterruptedException {
        RuntimeAgentConfig agentConfig =
                RuntimeAgentConfig.builder()
                        .name("FDIntegrationAgent")
                        .modelConfig(new StubModelConfig())
                        .sysPrompt("test")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().fixedDelay(100L).initialDelay(10L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        QuartzScheduleAgentTask qt = (QuartzScheduleAgentTask) task;

        long start = System.currentTimeMillis();
        long timeoutMs = 5000;
        long count = 0;
        while (System.currentTimeMillis() - start < timeoutMs) {
            count = qt.getExecutionCount();
            if (count >= 2) {
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(count >= 2);
    }
}
