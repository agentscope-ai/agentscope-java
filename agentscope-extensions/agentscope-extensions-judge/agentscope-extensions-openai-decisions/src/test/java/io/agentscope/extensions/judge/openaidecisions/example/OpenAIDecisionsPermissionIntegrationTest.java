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
package io.agentscope.extensions.judge.openaidecisions.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.openaidecisions.DecisionAnswer;
import io.agentscope.extensions.judge.openaidecisions.DecisionResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Exercises the real local ReAct pipeline with stub model and decision results. */
class OpenAIDecisionsPermissionIntegrationTest {
    @ParameterizedTest
    @ValueSource(doubles = {0.1, 0.9})
    void modelSafetyAssessmentCannotBypassCorePermissionDenial(double probability) {
        var permissions = new AtomicInteger();
        var executions = new AtomicInteger();
        var modelCalls = new AtomicInteger();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        "lookup",
                        "Fixture",
                        Map.of("type", "object"),
                        true,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState ctx) {
                        permissions.incrementAndGet();
                        return Mono.just(PermissionDecision.deny("Fixture core denial"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("must never execute"));
                    }
                });
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "fixture";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                modelCalls.getAndIncrement() == 0
                                                        ? List.of(
                                                                new ToolUseBlock(
                                                                        "lookup-1",
                                                                        "lookup",
                                                                        Map.of()))
                                                        : List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                        .build());
                    }
                };
        var guard =
                OpenAIDecisionsAutoModeMiddleware.builder(
                                r ->
                                        Mono.just(
                                                new DecisionResult(
                                                        "fixture",
                                                        Map.of(
                                                                "tool_0",
                                                                new DecisionAnswer.Predicate(
                                                                        probability)),
                                                        null)))
                        .enabled(true)
                        .guardedTool("lookup")
                        .build();
        var agent =
                ReActAgent.builder()
                        .name("fixture")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(guard)
                        .build();
        var events =
                agent.streamEvents(List.of(new UserMessage("Look up this order")))
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertEquals(0, executions.get());
        assertEquals(probability >= 0.8 ? 1 : 0, permissions.get());
        assertTrue(
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .anyMatch(e -> e.getState() == ToolResultState.DENIED));
    }
}
