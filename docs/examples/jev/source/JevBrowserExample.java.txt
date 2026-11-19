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
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Link;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Operation;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Outcome;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Receipt;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Snapshot;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Source;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Verification;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Actual Agent/tool execution with scripted browser and model by default. */
public final class JevBrowserExample {
    private JevBrowserExample() {}

    public static final String GOAL = "Find the standard warranty duration.";
    public static final String ANSWER = "The standard warranty lasts two years.";

    public record Run(
            String answer, int judgments, int modelCalls, int browserActions, int closedSessions) {}

    public static Scope scope(RuntimeContext ctx) {
        return new Scope(ctx.getUserId(), ctx.getSessionId(), UUID.randomUUID().toString());
    }

    public static Snapshot page(Scope scope, boolean found) {
        return new Snapshot(
                scope,
                found ? "v2" : "v1",
                found ? "https://fixture.test/policy" : "https://fixture.test/",
                "Warranty help",
                found ? ANSWER : "Choose a help topic.",
                found
                        ? List.of()
                        : List.of(
                                new Link(
                                        "warranty",
                                        "Warranty policy",
                                        "https://fixture.test/policy")),
                false,
                false,
                0);
    }

    public static Mono<Verification> verify(String goal, Snapshot page) {
        return Mono.just(
                new Verification(
                        JevBrowserSession.digest(goal),
                        page.version(),
                        goal.equals(GOAL)
                                && page.url().endsWith("/policy")
                                && page.text().contains(ANSWER),
                        List.of("/policy: " + ANSWER)));
    }

    public static Mono<SystemOneResult> syntheticAnswers(SystemOneRequest request) {
        var state = (Map<?, ?>) request.state();
        String operation =
                String.valueOf(state.get("visible_text")).contains(ANSWER) ? "DONE" : "CLICK";
        String target = "none";
        if (operation.equals("CLICK"))
            target =
                    ((ChoiceQuestion) request.questions().get("click_target"))
                            .criteria().keySet().stream()
                                    .filter(k -> !k.equals("none"))
                                    .findFirst()
                                    .orElseThrow();
        return Mono.just(reply(request, operation, target, .99));
    }

    public static SystemOneResult reply(
            SystemOneRequest request, String operation, String target, double probability) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .forEach(
                        (id, q) -> {
                            String choice = id.equals("operation") ? operation : target;
                            var criteria = ((ChoiceQuestion) q).criteria();
                            Map<String, Double> probabilities = new LinkedHashMap<>();
                            criteria.keySet()
                                    .forEach(
                                            k ->
                                                    probabilities.put(
                                                            k,
                                                            k.equals(choice)
                                                                    ? probability
                                                                    : (1 - probability)
                                                                            / (criteria.size()
                                                                                    - 1)));
                            answers.put(id, new ChoiceAnswer(choice, probabilities, probability));
                        });
        return new SystemOneResult("offline-synthetic", answers, new Usage(0, 0));
    }

    public static final class ScriptedSession implements JevBrowserSession {
        private final Scope scope;
        private final AtomicInteger actions, closures;
        private boolean found, closed;

        public ScriptedSession(Scope scope, AtomicInteger actions, AtomicInteger closures) {
            this.scope = scope;
            this.actions = actions;
            this.closures = closures;
        }

        public Scope scope() {
            return scope;
        }

        public Mono<Snapshot> observe() {
            return Mono.fromSupplier(
                    () -> {
                        if (closed) throw new IllegalStateException("closed");
                        return page(scope, found);
                    });
        }

        public Mono<Receipt> execute(Permit permit, Snapshot expected, Action action) {
            return Mono.fromSupplier(
                    () -> {
                        if (!permit.consume() || closed)
                            return new Receipt(Outcome.REJECTED, "CLOSED_OR_USED");
                        if (!expected.equals(page(scope, found)))
                            return new Receipt(Outcome.STALE, "PAGE_CHANGED");
                        if (action.operation() != Operation.CLICK
                                || !"warranty".equals(action.target()))
                            return new Receipt(Outcome.REJECTED, "INVALID_ACTION");
                        actions.incrementAndGet();
                        found = true;
                        return new Receipt(Outcome.APPLIED, "NAVIGATED");
                    });
        }

        public Mono<Void> close() {
            if (!closed) {
                closed = true;
                closures.incrementAndGet();
            }
            return Mono.empty();
        }
    }

    public static Run runOffline() {
        var actions = new AtomicInteger();
        var closures = new AtomicInteger();
        return run(
                new Source(
                        req -> new ScriptedSession(scope(req.context()), actions, closures),
                        JevBrowserExample::verify),
                JevBrowserExample::syntheticAnswers,
                actions,
                closures);
    }

    public static Run run(
            Source source,
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            AtomicInteger actions,
            AtomicInteger closures) {
        var calls = new AtomicInteger();
        var models = new AtomicInteger();
        var navigator =
                new JevBrowserNavigator(
                        r -> {
                            calls.incrementAndGet();
                            return caller.apply(r);
                        },
                        .8,
                        JevBrowserNavigator.Limits.defaults(),
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(30),
                                "browser-example-v1",
                                (c, r) -> {}));
        var toolkit = new Toolkit();
        toolkit.registerTool(new JevBrowserReadTool(navigator));
        var model =
                new ChatModelBase() {
                    @Override
                    public String getModelName() {
                        return "offline-browser-script";
                    }

                    @Override
                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        models.incrementAndGet();
                        var results =
                                messages.stream()
                                        .flatMap(
                                                m ->
                                                        m
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .toList();
                        ContentBlock block;
                        if (results.isEmpty())
                            block =
                                    new ToolUseBlock(
                                            "browser-call",
                                            "read_browser",
                                            Map.of("goal", GOAL),
                                            "{\"goal\":\"Find the standard warranty duration.\"}",
                                            null);
                        else {
                            var result = results.get(0);
                            if (result.getState() != ToolResultState.SUCCESS
                                    || result.getOutput().stream()
                                            .filter(TextBlock.class::isInstance)
                                            .map(TextBlock.class::cast)
                                            .noneMatch(
                                                    t -> {
                                                        try {
                                                            return new com.fasterxml.jackson
                                                                            .databind.ObjectMapper()
                                                                    .readTree(t.getText())
                                                                    .path("status")
                                                                    .asText()
                                                                    .equals("VERIFIED");
                                                        } catch (
                                                                com.fasterxml.jackson.core
                                                                                .JsonProcessingException
                                                                        e) {
                                                            return false;
                                                        }
                                                    }))
                                throw new IllegalStateException(
                                        "browser result unverified: " + result);
                            block = TextBlock.builder().text(ANSWER).build();
                        }
                        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                    }
                };
        var agent =
                ReActAgent.builder()
                        .name("browser-example")
                        .model(model)
                        .toolkit(toolkit)
                        .stateStore(new InMemoryAgentStateStore())
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .build();
        var context =
                RuntimeContext.builder()
                        .userId("offline-user")
                        .sessionId(UUID.randomUUID().toString())
                        .put(Source.class, source)
                        .build();
        var events =
                agent.streamEvents(List.of(new UserMessage(GOAL)), context)
                        .collectList()
                        .block(Duration.ofSeconds(40));
        String answer =
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getResult()
                        .getTextContent();
        if (!answer.equals(ANSWER)
                || events.stream().filter(ToolResultEndEvent.class::isInstance).count() != 1
                || models.get() != 2)
            throw new IllegalStateException("browser Agent integration failed");
        return new Run(answer, calls.get(), models.get(), actions.get(), closures.get());
    }

    public static void main(String[] args) {
        if (args.length != 0)
            throw new IllegalArgumentException(
                    "default example is offline; see JevBrowserLocalExample");
        System.out.println(runOffline());
    }
}
