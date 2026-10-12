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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/** Verifies middleware {@code activePoints()} declarations survive HarnessAgent composition. */
class HarnessMiddlewareActivePointsTest {

    @TempDir Path workspace;

    @Test
    void declarationTravelsWithTheMiddlewareInstanceThroughComposition() throws Exception {
        Files.createDirectories(workspace);
        List<String> trace = new ArrayList<>();
        MiddlewareBase selective =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return EnumSet.of(ExtensionPoint.ON_REASONING);
                    }

                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        trace.add("onReasoning");
                        return next.apply(input);
                    }

                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        trace.add("onAgent");
                        return next.apply(input);
                    }
                };

        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("composed")
                        .model(new FixedTextModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .middleware(selective)
                        .build()) {

            assertTrue(agent.getDelegate().getMiddlewares().contains(selective));
            agent.getDelegate().streamEvents(List.of()).collectList().block();

            assertEquals(
                    List.of("onReasoning"),
                    trace,
                    "undeclared points must stay inactive after harness composition");
        }
    }

    private static final class FixedTextModel implements Model {

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text("ok").build()))
                            .build());
        }

        @Override
        public String getModelName() {
            return "fixed-text";
        }
    }
}
