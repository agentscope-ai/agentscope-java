/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.jev;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.example.JevToolSelectionMiddleware;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Prescribed judgments exercise the actual middleware boundaries without calling any model. */
public final class JevHarnessScenarios {
    private JevHarnessScenarios() {}

    public static void main(String[] args) {
        if (args.length != 1 || !Set.of("selection", "routing").contains(args[0]))
            throw new IllegalArgumentException("selection or routing required; offline only");
        System.out.println(
                "OFFLINE: prescribed probabilities; no inference quality or latency claims");
        Map<String, ?> results = args[0].equals("selection") ? selection() : routing();
        results.forEach((id, result) -> System.out.println(id + " " + result));
    }

    private static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(
                mode, Duration.ofSeconds(2), "harness-guide-v1", (ctx, record) -> {});
    }

    public static Map<String, List<String>> selection() {
        var results = new LinkedHashMap<String, List<String>>();
        results.put("query", select("查一下订单 A1001", .95, .05, false, JevExecution.Mode.ENFORCE));
        results.put(
                "conditional-refund",
                select("先查 A1001，没发货就退款", .95, .90, false, JevExecution.Mode.ENFORCE));
        results.put("small-talk", select("你好", .05, .05, false, JevExecution.Mode.ENFORCE));
        results.put("uncertain", select("处理一下我的订单", .95, .50, false, JevExecution.Mode.ENFORCE));
        results.put("backend-error", select("查一下订单", .95, .05, true, JevExecution.Mode.ENFORCE));
        results.put("shadow", select("你好", .05, .05, false, JevExecution.Mode.SHADOW));
        return results;
    }

    private static List<String> select(
            String request, double query, double refund, boolean error, JevExecution.Mode mode) {
        var selector =
                JevToolSelectionMiddleware.builder(
                                r ->
                                        error
                                                ? Mono.error(
                                                        new IllegalStateException(
                                                                "offline backend failure"))
                                                : Mono.just(
                                                        new SystemOneResult(
                                                                "fixture",
                                                                Map.of(
                                                                        "tool_0",
                                                                        new NoulAnswer(query),
                                                                        "tool_1",
                                                                        new NoulAnswer(refund)),
                                                                null)))
                        .execution(options(mode))
                        .maxTools(2)
                        .confidenceThreshold(.8)
                        .rejectionThreshold(.2)
                        .alwaysIncludeTools(Set.of("generate_response"))
                        .build();
        var tools =
                List.of(
                        ToolSchema.builder().name("query_order").description("查订单").build(),
                        ToolSchema.builder().name("refund").description("申请退款").build(),
                        ToolSchema.builder()
                                .name("generate_response")
                                .description("结构化回答")
                                .build());
        var captured = new AtomicReference<List<String>>();
        selector.onReasoning(
                        null,
                        RuntimeContext.empty(),
                        new ReasoningInput(List.of(new UserMessage(request)), tools, null),
                        input -> {
                            captured.set(input.tools().stream().map(ToolSchema::getName).toList());
                            return Flux.empty();
                        })
                .blockLast();
        return captured.get();
    }

    public static Map<String, RouteRun> routing() {
        var results = new LinkedHashMap<String, RouteRun>();
        results.put("simple", route("fast", false, false, JevExecution.Mode.ENFORCE));
        results.put("complex", route("strong", false, false, JevExecution.Mode.ENFORCE));
        results.put("lost-availability", route("strong", true, false, JevExecution.Mode.ENFORCE));
        results.put("incompatible-tools", route("fast", false, true, JevExecution.Mode.ENFORCE));
        results.put("shadow", route("strong", false, false, JevExecution.Mode.SHADOW));
        return results;
    }

    public record RouteRun(List<String> dispatchedModels, int judgments) {}

    private static RouteRun route(
            String chosen,
            boolean unavailableAfterFirst,
            boolean needsTools,
            JevExecution.Mode mode) {
        Model fast = model("fast"), strong = model("strong"), original = model("original");
        Set<Model> available = new HashSet<>(Set.of(fast, strong));
        Set<Model> toolCapable = Set.of(strong);
        var judgments = new AtomicInteger();
        var router =
                JevModelRouterMiddleware.builder(
                                r -> {
                                    judgments.incrementAndGet();
                                    return Mono.just(
                                            new SystemOneResult(
                                                    "fixture",
                                                    Map.of(
                                                            "models_0",
                                                            new ChoiceAnswer(
                                                                    chosen,
                                                                    Map.of(
                                                                            "fast",
                                                                            chosen.equals("fast")
                                                                                    ? .95
                                                                                    : .05,
                                                                            "strong",
                                                                            chosen.equals("strong")
                                                                                    ? .95
                                                                                    : .05),
                                                                    .9)),
                                                    null));
                                })
                        .execution(options(mode))
                        .choice("fast", fast, "简单问答")
                        .choice("strong", strong, "复杂分析或带工具的请求")
                        .eligible((ctx, candidate) -> available.contains(candidate.model()))
                        .compatible(
                                (input, candidate) ->
                                        input.tools().isEmpty()
                                                || toolCapable.contains(candidate.model()))
                        .build();
        var ctx = RuntimeContext.empty();
        router.onAgent(
                        null,
                        ctx,
                        new AgentInput(
                                List.of(
                                        new UserMessage(
                                                chosen.equals("fast")
                                                        ? "用一句话解释退款规则"
                                                        : "分析多笔退款异常并给出核查步骤"))),
                        i -> Flux.empty())
                .blockLast();
        var tools =
                needsTools
                        ? List.of(
                                ToolSchema.builder().name("query_order").description("查订单").build())
                        : List.<ToolSchema>of();
        var actual = new ArrayList<String>();
        for (int turn = 0; turn < 3; turn++) {
            if (unavailableAfterFirst && turn == 1) available.clear();
            if (unavailableAfterFirst && turn == 2) available.addAll(Set.of(fast, strong));
            router.onModelCall(
                            null,
                            ctx,
                            new ModelCallInput(List.of(), tools, null, original),
                            input -> {
                                actual.add(input.model().getModelName());
                                return Flux.empty();
                            })
                    .blockLast();
        }
        return new RouteRun(List.copyOf(actual), judgments.get());
    }

    private static Model model(String name) {
        return new Model() {
            public String getModelName() {
                return name;
            }

            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(
                        new AssertionError("the route example must not call a generation model"));
            }
        };
    }
}
