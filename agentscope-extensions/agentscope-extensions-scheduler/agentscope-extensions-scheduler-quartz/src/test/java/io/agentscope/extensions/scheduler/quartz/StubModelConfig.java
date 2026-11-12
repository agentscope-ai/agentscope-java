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

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.scheduler.config.ModelConfig;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * Hermetic {@link ModelConfig} for scheduler unit tests.
 *
 * <p>Scheduler tests that previously built a real {@code DashScopeChatModel} with a placeholder
 * API key would hit the production DashScope endpoint whenever a trigger fired during the test.
 * On CI that race can go the wrong way, and {@code AgentQuartzJob} blocks on the model call while
 * {@code tearDown}'s {@code scheduler.shutdown(true)} waits for it — the transport defaults allow
 * minutes of stall per attempt. This stub answers immediately, so a mis-scheduled trigger is
 * harmless by construction instead of by winning a timing race.
 */
final class StubModelConfig implements ModelConfig {

    @Override
    public String getModelName() {
        return "stub-model";
    }

    @Override
    public Model createModel() {
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                ContentBlock block = TextBlock.builder().text("ok").build();
                ChatResponse resp =
                        ChatResponse.builder().id("stub-1").content(List.of(block)).build();
                return Flux.just(resp);
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }
        };
    }
}
