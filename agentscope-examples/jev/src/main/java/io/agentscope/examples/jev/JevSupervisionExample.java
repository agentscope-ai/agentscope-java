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
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.Usage;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.SupervisionEvidence;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Offline real Agent/tool lifecycle with synthetic evaluation answers. Makes no HTTP requests. */
public final class JevSupervisionExample {
    private JevSupervisionExample() {}

    public record Run(
            String answer, int executedTools, JevSupervisionMiddleware.Observation observation) {}

    public static Run runOffline() {
        AtomicReference<JevSupervisionMiddleware.Observation> observed = new AtomicReference<>();
        AtomicInteger executed = new AtomicInteger();
        var supervisor =
                new JevTaskSupervisor(
                        JevSupervisionExample::syntheticAnswers,
                        new JevTaskSupervisor.Thresholds(.2, .8),
                        JevTaskSupervisor.Limits.defaults(),
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(3),
                                "offline-supervision-v1",
                                (ctx, record) -> {}));
        var middleware =
                new JevSupervisionMiddleware(
                        supervisor,
                        JevSupervisionMiddleware.Schedule.defaults(),
                        (ctx, report) -> observed.set(report));
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
                                        .put(
                                                JevSupervisionMiddleware.EvidenceSource.class,
                                                new JevSupervisionMiddleware.EvidenceSource(
                                                        request ->
                                                                Mono.just(
                                                                        new SupervisionEvidence(
                                                                                "order-42-v1",
                                                                                "read only",
                                                                                "",
                                                                                List.of(),
                                                                                "Report only the"
                                                                                    + " observed"
                                                                                    + " order"
                                                                                    + " status.",
                                                                                false,
                                                                                new SupervisionEvidence
                                                                                        .Verification(
                                                                                        request.scope()
                                                                                                .userId(),
                                                                                        request.scope()
                                                                                                .sessionId(),
                                                                                        request.scope()
                                                                                                .runId(),
                                                                                        "order-42-v1",
                                                                                        "offline-host-check",
                                                                                        !request
                                                                                                        .workerActive()
                                                                                                && executed
                                                                                                                .get()
                                                                                                        == 1,
                                                                                        "Host verified"
                                                                                            + " lookup"
                                                                                            + " executed"
                                                                                            + " exactly"
                                                                                            + " once"
                                                                                            + " and returned"
                                                                                            + " shipped.")))))
                                        .build())
                        .ofType(AgentResultEvent.class)
                        .single()
                        .block(Duration.ofSeconds(10));
        if (observed.get() == null
                || observed.get().advice() != JevTaskSupervisor.Advice.REVIEW_COMPLETION
                || executed.get() != 1)
            throw new IllegalStateException("offline trace integration failed: " + observed.get());
        return new Run(result.getResult().getTextContent(), executed.get(), observed.get());
    }

    public static void main(String[] args) {
        Run run = runOffline();
        System.out.println("Synthetic judgments only; no model accuracy claim.");
        System.out.println(
                "Tool executions: "
                        + run.executedTools()
                        + ", advice: "
                        + run.observation().advice()
                        + ", verified revision: "
                        + run.observation().decision().value().verifiedRevision());
    }

    /** Deterministic responses for integration tests; never a real semantic evaluator. */
    public static Mono<SystemOneResult> syntheticAnswers(SystemOneRequest request) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions()
                .keySet()
                .forEach(
                        key ->
                                answers.put(
                                        key,
                                        new NoulAnswer(
                                                key.endsWith("needs_human")
                                                                || key.endsWith("agents_md_drift")
                                                                || key.endsWith("worker_stuck")
                                                                || key.endsWith("work_off_track")
                                                                || key.endsWith(
                                                                        "needs_verification")
                                                        ? .01
                                                        : .99)));
        return Mono.just(new SystemOneResult("offline-synthetic", answers, new Usage(0, 0)));
    }
}
