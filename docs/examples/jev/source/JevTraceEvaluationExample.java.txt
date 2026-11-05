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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.ScoreAnswer;
import io.agentscope.extensions.judge.jev.ScoreQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluationMiddleware;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceEvaluator;
import io.agentscope.extensions.judge.jev.evaluation.JevTraceMetrics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline real Agent/tool lifecycle with synthetic evaluation answers. Makes no HTTP requests. */
public final class JevTraceEvaluationExample {
    private JevTraceEvaluationExample() {}

    public record Run(String answer, int executedTools, JevTraceEvaluator.Report report) {}

    public static Run runOffline() {
        var evaluator =
                new JevTraceEvaluator(
                        JevTraceEvaluationExample::syntheticAnswers,
                        JevTraceMetrics.agentMetrics(new JevTraceMetrics.Thresholds(.2, .8)),
                        JevTraceEvaluator.Limits.defaults());
        AtomicReference<JevTraceEvaluator.Report> observed = new AtomicReference<>();
        var middleware =
                new JevTraceEvaluationMiddleware(
                        evaluator,
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(5),
                                "offline-v1",
                                (ctx, record) -> {}),
                        100000,
                        (ctx, report) -> observed.set(report));
        AtomicInteger executed = new AtomicInteger();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        "lookup_order",
                        "Read order status",
                        Map.of("type", "object", "properties", Map.of()),
                        false,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executed.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("Order 42 shipped yesterday."));
                    }
                });
        ChatModelBase model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline-script";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        boolean hasResult =
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        !m.getContentBlocks(ToolResultBlock.class)
                                                                .isEmpty());
                        ContentBlock content =
                                hasResult
                                        ? TextBlock.builder()
                                                .text("Order 42 shipped yesterday.")
                                                .build()
                                        : new ToolUseBlock("lookup-42", "lookup_order", Map.of());
                        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("trace-demo")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .middleware(middleware)
                        .build();
        var result =
                agent.streamEvents(
                                List.of(new UserMessage("Where is order 42?")),
                                RuntimeContext.builder()
                                        .sessionId("offline-session")
                                        .userId("offline-user")
                                        .build())
                        .ofType(AgentResultEvent.class)
                        .single()
                        .block(Duration.ofSeconds(10));
        if (observed.get() == null || !observed.get().passed() || executed.get() != 1)
            throw new IllegalStateException("offline trace integration failed: " + observed.get());
        return new Run(result.getResult().getTextContent(), executed.get(), observed.get());
    }

    public static void main(String[] args) {
        Run run = runOffline();
        System.out.println("Synthetic judgments only; no model accuracy claim.");
        System.out.println(
                "Tool executions: "
                        + run.executedTools()
                        + ", evaluation requests: "
                        + run.report().requests());
        run.report()
                .results()
                .forEach(
                        e ->
                                System.out.println(
                                        e.id()
                                                + ": "
                                                + e.result().status()
                                                + ", passed="
                                                + e.result().passed()));
    }

    /** Deterministic test fixture, not a semantic evaluator. */
    public static Mono<SystemOneResult> syntheticAnswers(SystemOneRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .forEach(
                        (id, question) -> {
                            if (question instanceof ChoiceQuestion choice) {
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                choice.criteria()
                                        .keySet()
                                        .forEach(
                                                k ->
                                                        probabilities.put(
                                                                k, k.equals("correct") ? 1d : 0d));
                                answers.put(id, new ChoiceAnswer("correct", probabilities, 1d));
                            } else if (question instanceof ScoreQuestion score) {
                                Map<String, String> legend = new LinkedHashMap<>();
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                for (int i = 0; i < score.criteria().size(); i++) {
                                    legend.put(
                                            Integer.toString(i),
                                            score.criteria().get(i).toString());
                                    probabilities.put(
                                            Integer.toString(i),
                                            i == score.criteria().size() - 1 ? 1d : 0d);
                                }
                                answers.put(
                                        id,
                                        new ScoreAnswer(
                                                (double) score.criteria().size() - 1,
                                                legend,
                                                probabilities,
                                                1d));
                            } else
                                answers.put(
                                        id,
                                        new NoulAnswer(
                                                id.startsWith("indirect_injection.")
                                                                || id.endsWith(".noncommittal")
                                                        ? .01
                                                        : .99));
                        });
        return Mono.just(new SystemOneResult("offline-synthetic", answers, new Usage(0, 0)));
    }
}
