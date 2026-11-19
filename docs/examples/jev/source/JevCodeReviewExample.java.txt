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
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
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
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import io.agentscope.extensions.judge.jev.ScoreAnswer;
import io.agentscope.extensions.judge.jev.ScoreQuestion;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewTool;
import io.agentscope.extensions.judge.jev.review.JevCodeReviewer;
import io.agentscope.extensions.judge.jev.review.JevReviewInput;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Default offline: real Agent and read-only review tool, synthetic typed judgments. */
public final class JevCodeReviewExample {
    private JevCodeReviewExample() {}

    public record Run(String answer, int agentModelCalls, int reviewRequests, int snapshotReads) {}

    public static JevReviewInput snapshot() {
        return new JevReviewInput(
                "demo-revision",
                JevReviewInput.Mode.CHANGES,
                List.of(
                        new JevReviewInput.File(
                                "src/auth.py",
                                "@@ -1,2 +1,1 @@\n"
                                        + "-if not authorized: raise Forbidden()\n"
                                        + "-return record\n"
                                        + "+return record",
                                List.of())),
                List.of());
    }

    public static Run runOffline() {
        AtomicInteger requests = new AtomicInteger(),
                reads = new AtomicInteger(),
                modelCalls = new AtomicInteger();
        AtomicReference<JevExecution.Record> observed = new AtomicReference<>();
        var reviewer =
                new JevCodeReviewer(
                        request -> {
                            requests.incrementAndGet();
                            return syntheticAnswers(request);
                        },
                        JevCodeReviewer.Config.referencePolicy(),
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(3),
                                "offline-review",
                                (ctx, record) -> observed.set(record)));
        var toolkit = new Toolkit();
        toolkit.registerTool(new JevCodeReviewTool(reviewer));
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline-script";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        modelCalls.incrementAndGet();
                        boolean result =
                                messages.stream()
                                        .anyMatch(
                                                m ->
                                                        !m.getContentBlocks(ToolResultBlock.class)
                                                                .isEmpty());
                        ContentBlock content =
                                result
                                        ? TextBlock.builder()
                                                .text(
                                                        "One security review prompt; no edits or"
                                                                + " review submission performed.")
                                                .build()
                                        : new ToolUseBlock(
                                                "review-call",
                                                "review_code_snapshot",
                                                Map.of("snapshotId", "demo"),
                                                "{\"snapshotId\":\"demo\"}",
                                                null);
                        return Flux.just(ChatResponse.builder().content(List.of(content)).build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("review-demo")
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .build();
        var context =
                RuntimeContext.builder()
                        .userId("offline-user")
                        .sessionId("offline-session")
                        .put(
                                JevCodeReviewTool.Source.class,
                                new JevCodeReviewTool.Source(
                                        (ctx, id) -> {
                                            if (!id.equals("demo"))
                                                return Mono.error(
                                                        new SecurityException("unknown snapshot"));
                                            reads.incrementAndGet();
                                            return Mono.just(snapshot());
                                        }))
                        .build();
        var events =
                agent.streamEvents(
                                List.of(new UserMessage("Review the authorized demo snapshot.")),
                                context)
                        .collectList()
                        .block(Duration.ofSeconds(10));
        var results =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .toList();
        if (reads.get() != 1
                || requests.get() != 6
                || modelCalls.get() != 2
                || results.size() != 1
                || results.get(0).getState() != ToolResultState.SUCCESS
                || observed.get() == null
                || observed.get().status() != JevExecution.Status.DECIDED
                || !"1".equals(observed.get().recommendation().get("findings")))
            throw new IllegalStateException(
                    "offline code-review integration failed: reads="
                            + reads.get()
                            + ", requests="
                            + requests.get()
                            + ", modelCalls="
                            + modelCalls.get()
                            + ", toolStates="
                            + results.stream().map(ToolResultEndEvent::getState).toList()
                            + ", toolOutput="
                            + events.stream()
                                    .filter(ToolResultTextDeltaEvent.class::isInstance)
                                    .map(ToolResultTextDeltaEvent.class::cast)
                                    .map(ToolResultTextDeltaEvent::getDelta)
                                    .toList()
                            + ", decision="
                            + observed.get());
        String answer =
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getResult()
                        .getTextContent();
        return new Run(answer, modelCalls.get(), requests.get(), reads.get());
    }

    public static void main(String[] args) {
        if (args.length != 0)
            throw new IllegalArgumentException("offline example accepts no arguments");
        System.out.println("Synthetic judgments only; no model accuracy claim.");
        System.out.println(runOffline());
    }

    /** Deterministic pipeline fixture, not a semantic implementation. */
    public static Mono<SystemOneResult> syntheticAnswers(SystemOneRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .forEach(
                        (id, question) -> {
                            if (question instanceof NoulQuestion)
                                answers.put(id, new NoulAnswer(id.equals("security") ? .99 : .01));
                            else if (question instanceof ChoiceQuestion choice) {
                                String selected =
                                        switch (id) {
                                            case "category" ->
                                                    choice.criteria().containsKey("behavior")
                                                            ? "behavior"
                                                            : "domain";
                                            case "mechanism" -> "authorization";
                                            case "owner" -> "security";
                                            default ->
                                                    choice.criteria().keySet().stream()
                                                            .filter(k -> !k.equals("noMatch"))
                                                            .sorted()
                                                            .findFirst()
                                                            .orElseThrow();
                                        };
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                choice.criteria()
                                        .keySet()
                                        .forEach(
                                                k ->
                                                        probabilities.put(
                                                                k, k.equals(selected) ? 1d : 0d));
                                answers.put(id, new ChoiceAnswer(selected, probabilities, .99));
                            } else if (question instanceof ScoreQuestion score) {
                                Map<String, Double> probabilities = new LinkedHashMap<>();
                                Map<String, String> legend = new LinkedHashMap<>();
                                for (int i = 0; i < score.criteria().size(); i++) {
                                    probabilities.put(Integer.toString(i), i == 2 ? 1d : 0d);
                                    legend.put(
                                            Integer.toString(i),
                                            score.criteria().get(i).toString());
                                }
                                answers.put(id, new ScoreAnswer(2d, legend, probabilities, .99));
                            }
                        });
        return Mono.just(new SystemOneResult("offline-synthetic", answers, new Usage(0, 0)));
    }
}
