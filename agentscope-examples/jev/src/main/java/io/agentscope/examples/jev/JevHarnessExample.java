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

package io.agentscope.examples.jev;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline executable example for all three Harness boundaries; no keys or external effects. */
public final class JevHarnessExample {
    private JevHarnessExample() {}

    public static void main(String[] args) {
        for (var mode : JevExecution.Mode.values()) {
            var options =
                    new JevExecution.Options(
                            mode,
                            Duration.ofSeconds(1),
                            "demo-v1",
                            (ctx, r) -> System.out.println(r));
            var ctx = RuntimeContext.empty();
            ctx.setAgentState(AgentState.builder().replyId("demo").build());
            var selection =
                    JevToolSelectionMiddleware.builder(
                                    r ->
                                            Mono.just(
                                                    new SystemOneResult(
                                                            "fixture",
                                                            Map.of(
                                                                    "tool_0",
                                                                    new NoulAnswer(0.95),
                                                                    "tool_1",
                                                                    new NoulAnswer(0.01)),
                                                            null)))
                            .execution(options)
                            .build();
            var tools =
                    List.of(
                            ToolSchema.builder().name("order").description("query order").build(),
                            ToolSchema.builder()
                                    .name("refund")
                                    .description("refund payment")
                                    .build());
            selection
                    .onReasoning(
                            null,
                            ctx,
                            new ReasoningInput(List.of(new UserMessage("查订单")), tools, null),
                            input -> {
                                System.out.println(
                                        mode
                                                + " tools="
                                                + input.tools().stream()
                                                        .map(ToolSchema::getName)
                                                        .toList());
                                return Flux.empty();
                            })
                    .blockLast();
            var guard =
                    JevAutoModeMiddleware.builder(
                                    r ->
                                            Mono.just(
                                                    new SystemOneResult(
                                                            "fixture",
                                                            Map.of("tool_0", new NoulAnswer(0.01)),
                                                            null)))
                            .execution(options)
                            .guardedTool("refund")
                            .build();
            guard.onActing(
                            null,
                            ctx,
                            new ActingInput(
                                    List.of(new ToolUseBlock("refund-1", "refund", Map.of()))),
                            input -> {
                                System.out.println(
                                        mode + " mock dispatch=" + input.toolCalls().size());
                                return Flux.empty();
                            })
                    .doOnNext(
                            event ->
                                    System.out.println(
                                            mode
                                                    + " guard event="
                                                    + event.getClass().getSimpleName()))
                    .blockLast();
            var fast = model("fast");
            var original = model("original");
            var router =
                    JevModelRouterMiddleware.builder(
                                    r ->
                                            Mono.just(
                                                    new SystemOneResult(
                                                            "fixture",
                                                            Map.of(
                                                                    "models_0",
                                                                    new ChoiceAnswer(
                                                                            "fast",
                                                                            Map.of("fast", 1.0),
                                                                            0.95)),
                                                            null)))
                            .execution(options)
                            .choice("fast", fast, "simple question")
                            .build();
            router.onAgent(
                            null,
                            ctx,
                            new AgentInput(List.of(new UserMessage("你好"))),
                            input -> Flux.empty())
                    .blockLast();
            router.onModelCall(
                            null,
                            ctx,
                            new ModelCallInput(List.of(), List.of(), null, original),
                            input -> {
                                System.out.println(mode + " model=" + input.model().getModelName());
                                return Flux.empty();
                            })
                    .blockLast();
        }
    }

    private static Model model(String name) {
        return new Model() {
            public String getModelName() {
                return name;
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }
        };
    }
}
