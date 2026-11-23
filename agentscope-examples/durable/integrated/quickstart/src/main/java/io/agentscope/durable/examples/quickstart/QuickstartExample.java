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
package io.agentscope.durable.examples.quickstart;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.durable.DurableRuntime;
import io.agentscope.durable.DurableTypes.SessionKey;
import io.agentscope.durable.DurableTypes.ThreadKey;
import io.agentscope.durable.DurableTypes.TurnStatus;
import io.agentscope.durable.EventProjection;
import io.agentscope.durable.EventStreamOptions;
import io.agentscope.durable.SessionStreamEvent;
import io.agentscope.durable.agentscope.AgentScopeAdapter;
import io.agentscope.extensions.durable.jdbc.LocalBackend;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** AgentScope quickstart with a local Backend and DurableRuntime in one Spring Boot process. */
@Component
public final class QuickstartExample implements ApplicationRunner {
    public record Result(
            String turnId,
            TurnStatus status,
            String cursor,
            int modelInvocations,
            int runs,
            int modelCalls) {}

    private final QuickstartExampleProperties properties;

    public QuickstartExample(QuickstartExampleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var result = execute();
        System.out.printf(
                "quickstart verified turn=%s status=%s cursor=%s modelInvocations=%d runs=%d"
                        + " modelCalls=%d%n",
                result.turnId(),
                result.status(),
                result.cursor(),
                result.modelInvocations(),
                result.runs(),
                result.modelCalls());
    }

    Result execute() throws Exception {
        Files.createDirectories(properties.getDataDirectory());
        var invocations = new AtomicInteger();
        Model model = offlineModel(invocations);
        var adapter =
                AgentScopeAdapter.forReAct(
                        binding ->
                                ReActAgent.builder()
                                        .name("quickstart-demo")
                                        .model(model)
                                        .stateStore(new InMemoryAgentStateStore()),
                        Map.of());
        try (var backend = LocalBackend.open(properties.getDataDirectory());
                var runtime =
                        DurableRuntime.builder()
                                .backend(backend)
                                .harness("quickstart-demo", "v1", adapter)
                                .build()) {
            var key = new SessionKey(properties.getNamespace(), properties.getSessionId());
            var session = runtime.createSession(key, "quickstart-demo");
            var projection = new EventProjection(key);
            var completed = new CountDownLatch(1);
            try (var observer =
                    session.subscribeEvents(
                            EventStreamOptions.defaults(),
                            event -> {
                                if (event instanceof SessionStreamEvent.SnapshotReset reset) {
                                    projection.reset(reset.snapshot());
                                    if (reset.snapshot().turns().stream()
                                            .anyMatch(
                                                    turn -> turn.status() == TurnStatus.SUCCEEDED))
                                        completed.countDown();
                                } else if (event
                                        instanceof SessionStreamEvent.Committed committed) {
                                    projection.accept(committed.event());
                                    if ("turn.succeeded".equals(committed.event().type()))
                                        completed.countDown();
                                }
                            })) {
                var turn = session.submit(properties.getRequestId(), "hello");
                runtime.start();
                if (!completed.await(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException(
                            "Quickstart Turn did not finish",
                            runtime.lastFailure(new ThreadKey(key, "main")));
                var view = turn.status();
                if (view.status() != TurnStatus.SUCCEEDED)
                    throw new IllegalStateException("Quickstart Turn did not succeed: " + view);
                var runs = turn.listRuns(null, 100).items();
                int calls = 0;
                for (var run : runs)
                    calls += turn.listModelCalls(run.runId(), null, null, 100).items().size();
                if (runs.isEmpty() || calls == 0)
                    throw new IllegalStateException("Missing Run/model-call evidence");
                return new Result(
                        turn.turnId(),
                        view.status(),
                        observer.cursor(),
                        invocations.get(),
                        runs.size(),
                        calls);
            }
        }
    }

    private static Model offlineModel(AtomicInteger invocations) {
        return new Model() {
            @Override
            public String getModelName() {
                return "offline-quickstart-demo";
            }

            @Override
            public Flux<ChatResponse> stream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                invocations.incrementAndGet();
                return Flux.just(
                        ChatResponse.builder()
                                .content(List.of(TextBlock.builder().text("Durable hello").build()))
                                .build());
            }
        };
    }
}
