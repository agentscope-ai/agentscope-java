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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.scheduler.ScheduleAgentTask;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import io.agentscope.extensions.scheduler.config.RuntimeAgentConfig;
import io.agentscope.extensions.scheduler.config.ScheduleConfig;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class QuartzFixedDelayIntegrationTest {

    private QuartzAgentScheduler scheduler;

    static class TestModelConfig implements ModelConfig {
        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public Model createModel() {
            return new Model() {
                @Override
                public Flux<ChatResponse> stream(
                        List<io.agentscope.core.message.Msg> messages,
                        List<ToolSchema> tools,
                        GenerateOptions options) {
                    ContentBlock block = TextBlock.builder().text("ok").build();
                    ChatResponse resp =
                            ChatResponse.builder().id("r1").content(List.of(block)).build();
                    return Flux.just(resp);
                }

                @Override
                public String getModelName() {
                    return "test-model";
                }
            };
        }
    }

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
                        .modelConfig(new TestModelConfig())
                        .sysPrompt("test")
                        .build();

        ScheduleConfig scheduleConfig =
                ScheduleConfig.builder().fixedDelay(100L).initialDelay(10L).build();

        ScheduleAgentTask task = scheduler.schedule(agentConfig, scheduleConfig);

        QuartzScheduleAgentTask qt = (QuartzScheduleAgentTask) task;

        long start = System.currentTimeMillis();
        long timeoutMs = 2000;
        long count = 0;
        while (System.currentTimeMillis() - start < timeoutMs) {
            count = qt.getExecutionCount();
            if (count >= 2) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(count >= 2);
    }
}
