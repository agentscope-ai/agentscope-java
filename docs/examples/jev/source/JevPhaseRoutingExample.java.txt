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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.application.JevStageRouter;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.routing.JevPhaseRouting;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Candidate;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Effort;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Quota;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Snapshot;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Source;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Runs a real Agent with scripted routing/model responses and one read tool per invocation. */
public final class JevPhaseRoutingExample {
    private JevPhaseRoutingExample() {}

    public record Run(
            int judgeRequests,
            int strongCalls,
            int fastCalls,
            int originalCalls,
            int toolCalls,
            List<String> answers,
            int usageRecords) {}

    public static final class ReadTool {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(
                name = "read_status",
                description = "Read the simulated verification receipt",
                readOnly = true)
        public String read() {
            calls.incrementAndGet();
            return "verification receipt: passed";
        }
    }

    public static List<Route> routes() {
        return List.of(
                new Route(
                        "plan",
                        "Design and investigate a complex change",
                        List.of("strong", "fast")),
                new Route(
                        "execute",
                        "Carry out a fully specified routine action",
                        List.of("fast", "strong")));
    }

    public static Mono<SystemOneResult> syntheticAnswers(SystemOneRequest request) {
        var answers = new LinkedHashMap<String, Answer>();
        request.questions()
                .forEach(
                        (id, q) -> {
                            var options = ((ChoiceQuestion) q).criteria();
                            String selected =
                                    id.equals("route")
                                            ? "plan"
                                            : options.containsKey("high")
                                                    ? "high"
                                                    : options.keySet().iterator().next();
                            var probabilities = new LinkedHashMap<String, Double>();
                            options.keySet()
                                    .forEach(
                                            k ->
                                                    probabilities.put(
                                                            k, k.equals(selected) ? 1d : 0d));
                            answers.put(id, new ChoiceAnswer(selected, probabilities, .99));
                        });
        return Mono.just(new SystemOneResult("offline-router", answers, new Usage(20, 4)));
    }

    public static Candidate candidate(String id, Model model) {
        return new Candidate(
                id,
                model,
                id.equals("strong") ? "Complex reasoning" : "Routine execution",
                64000L,
                4000,
                true,
                Set.of(Effort.LOW, Effort.HIGH),
                Quota.AVAILABLE,
                null);
    }

    public static Model scriptedModel(String name, AtomicInteger calls) {
        return new ChatModelBase() {
            public String getModelName() {
                return name;
            }

            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                calls.incrementAndGet();
                boolean receipt =
                        messages.stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .anyMatch(
                                        r ->
                                                r.getState() == ToolResultState.SUCCESS
                                                        && r.getName().equals("read_status"));
                ContentBlock block =
                        receipt
                                ? TextBlock.builder().text(name + ": verified once").build()
                                : new ToolUseBlock(
                                        "read-call", "read_status", Map.of(), "{}", null);
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(block))
                                .usage(new ChatUsage(40, 10, 0))
                                .build());
            }
        };
    }

    public static Run runOffline() {
        var judgeCalls = new AtomicInteger();
        var strongCalls = new AtomicInteger();
        var fastCalls = new AtomicInteger();
        var originalCalls = new AtomicInteger();
        var strong = scriptedModel("strong", strongCalls);
        var fast = scriptedModel("fast", fastCalls);
        var original = scriptedModel("original", originalCalls);
        var pool = List.of(candidate("strong", strong), candidate("fast", fast));
        var records = new ArrayList<JevPhaseRouting.CallRecord>();
        Function<SystemOneRequest, Mono<SystemOneResult>> caller =
                r -> {
                    judgeCalls.incrementAndGet();
                    return syntheticAnswers(r);
                };
        var options =
                new JevExecution.Options(
                        JevExecution.Mode.ENFORCE,
                        Duration.ofSeconds(2),
                        "offline-phase",
                        (c, r) -> {});
        var routing =
                new JevPhaseRouting(
                        caller,
                        routes(),
                        Set.of("strong", "fast"),
                        .8,
                        true,
                        options,
                        (c, r) -> records.add(r));
        var middleware = JevModelRouterMiddleware.builder(caller).phaseRouting(routing).build();
        var stages = new JevStageRouter(middleware);
        var read = new ReadTool();
        var toolkit = new Toolkit();
        toolkit.registerTool(read);
        var agent =
                ReActAgent.builder()
                        .name("phase-example")
                        .model(original)
                        .toolkit(toolkit)
                        .middleware(middleware)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .build();
        var answers = new ArrayList<String>();
        for (String phase : List.of("auto", "plan", "execute")) {
            var ctx =
                    RuntimeContext.builder()
                            .userId("offline-user")
                            .sessionId("phase-" + phase)
                            .put(
                                    Source.class,
                                    new Source(
                                            (c, i) ->
                                                    Mono.just(
                                                            new Snapshot(
                                                                    pool, 500, 1000, null, null,
                                                                    0))))
                            .build();
            if (!phase.equals("auto"))
                ctx = stages.begin(ctx, phase, "Verify the current task").block().context();
            var events =
                    agent.streamEvents(
                                    List.of(
                                            new UserMessage(
                                                    "Design and verify the transaction change.")),
                                    ctx)
                            .collectList()
                            .block(Duration.ofSeconds(10));
            var results =
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .toList();
            if (results.size() != 1 || results.get(0).getState() != ToolResultState.SUCCESS)
                throw new IllegalStateException("unpaired or repeated read tool");
            answers.add(
                    events.stream()
                            .filter(AgentResultEvent.class::isInstance)
                            .map(AgentResultEvent.class::cast)
                            .findFirst()
                            .orElseThrow()
                            .getResult()
                            .getTextContent());
        }
        if (judgeCalls.get() != 1
                || strongCalls.get() != 4
                || fastCalls.get() != 2
                || originalCalls.get() != 0
                || read.calls.get() != 3
                || records.size() != 6
                || records.stream().anyMatch(r -> r.usage() == null)
                || !answers.equals(
                        List.of(
                                "strong: verified once",
                                "strong: verified once",
                                "fast: verified once")))
            throw new IllegalStateException(
                    "phase integration failed: " + answers + ", records=" + records);
        return new Run(
                judgeCalls.get(),
                strongCalls.get(),
                fastCalls.get(),
                originalCalls.get(),
                read.calls.get(),
                answers,
                records.size());
    }

    public static void main(String[] args) {
        if (args.length != 0)
            throw new IllegalArgumentException("offline example accepts no arguments");
        System.out.println("Scripted models and judgments; no routing-quality or billing claim.");
        System.out.println(runOffline());
    }
}
