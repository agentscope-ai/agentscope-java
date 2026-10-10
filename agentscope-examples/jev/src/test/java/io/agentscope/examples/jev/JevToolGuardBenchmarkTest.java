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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.NoulAnswer;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class JevToolGuardBenchmarkTest {
    private static final Path DATA = Path.of("benchmarks/tool-guard/data/refund-v1.jsonl");
    private static final Duration BUDGET = Duration.ofSeconds(2);

    private JevToolGuardBenchmark.Fixture fixture(String id) throws Exception {
        return JevToolGuardBenchmark.load(DATA).stream()
                .filter(f -> f.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static SystemOneResult answer(SystemOneRequest request, double probability) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        request.questions().keySet().forEach(key -> answers.put(key, new NoulAnswer(probability)));
        return new SystemOneResult("stub", answers, null);
    }

    private JsonNode run(
            JevToolGuardBenchmark.Fixture fixture,
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            JevExecution.Mode mode,
            Duration budget,
            boolean deny) {
        return JevToolGuardBenchmark.JSON.valueToTree(
                JevToolGuardBenchmark.run(fixture, caller, mode, budget, .8, deny));
    }

    @Test
    void datasetHasDisjointFamiliesAndNoGoldInCapturedRequest() throws Exception {
        var fixtures = JevToolGuardBenchmark.load(DATA);
        assertEquals(160, fixtures.size());
        assertEquals(120, fixtures.stream().filter(f -> f.split().equals("test")).count());
        assertEquals(40, fixtures.stream().filter(f -> f.split().equals("calibration")).count());
        Set<String> hashes = new HashSet<>();
        for (var f : fixtures) {
            AtomicReference<SystemOneRequest> captured = new AtomicReference<>();
            var row =
                    run(
                            f,
                            r -> {
                                captured.set(r);
                                return JevToolGuardBenchmark.oracle(f).apply(r);
                            },
                            JevExecution.Mode.ENFORCE,
                            BUDGET,
                            false);
            JsonNode request = JevToolGuardBenchmark.JSON.valueToTree(captured.get());
            assertEquals(Set.of("messages", "tool_calls"), fieldNames(request.path("state")));
            assertFalse(request.toString().contains("expectedAllow"));
            assertFalse(request.toString().contains(f.reason()));
            assertTrue(hashes.add(row.path("requestSha256").asText()), f.id());
            var repeated =
                    run(
                            f,
                            JevToolGuardBenchmark.oracle(f),
                            JevExecution.Mode.ENFORCE,
                            BUDGET,
                            false);
            assertEquals(row.path("requestSha256"), repeated.path("requestSha256"));
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void allFixturesHaveCorrectDispatchPairingAndShadowLeavesCallsAlone() throws Exception {
        for (var f : JevToolGuardBenchmark.load(DATA)) {
            for (var mode : JevExecution.Mode.values()) {
                var row =
                        run(
                                f,
                                mode == JevExecution.Mode.OFF
                                        ? r -> Mono.error(new AssertionError("OFF must not call"))
                                        : JevToolGuardBenchmark.oracle(f),
                                mode,
                                BUDGET,
                                false);
                assertEquals(f.input().path("calls").size(), row.path("resultByCallId").size());
                for (var judgment : row.path("judgments")) {
                    boolean expectedExecution =
                            mode != JevExecution.Mode.ENFORCE
                                    || judgment.path("expectedAllow").asBoolean();
                    assertEquals(
                            expectedExecution, judgment.path("dispatched").asBoolean(), f.id());
                    assertEquals(
                            expectedExecution ? 1 : 0,
                            judgment.path("executionCount").asInt(),
                            f.id());
                    assertEquals(
                            expectedExecution ? "SIMULATED_SUCCESS" : "DENIED",
                            judgment.path("result").asText());
                }
                if (f.family().equals("same_batch"))
                    assertTrue(row.path("executedIds").toString().contains("lookup-next"));
            }
        }
    }

    @Test
    void errorsAndTimeoutsAreNotSemanticDenialsAndPreserveReadTools() throws Exception {
        var f = fixture("same_batch-1-hold");
        List<Function<SystemOneRequest, Mono<SystemOneResult>>> failures =
                List.of(
                        r -> Mono.error(new IllegalStateException("backend down")),
                        r -> Mono.empty(),
                        r -> Mono.never(),
                        r -> Mono.just(new SystemOneResult("stub", Map.of(), null)),
                        r -> Mono.just(answer(r, Double.NaN)),
                        r -> Mono.just(answer(r, 1.1)));
        for (var caller : failures)
            for (var mode : List.of(JevExecution.Mode.ENFORCE, JevExecution.Mode.SHADOW)) {
                var row = run(f, caller, mode, Duration.ofMillis(20), false);
                assertEquals("ERROR", row.path("status").asText());
                assertTrue(row.path("judgments").get(0).path("modelAllow").isNull());
                assertEquals(
                        mode == JevExecution.Mode.SHADOW ? 1 : 0,
                        row.path("judgments").get(0).path("executionCount").asInt());
                assertTrue(row.path("executedIds").toString().contains("lookup-next"));
            }
    }

    @Test
    void thresholdBoundaryAndPermissionAreIndependent() throws Exception {
        var f = fixture("shipment-1-allow");
        var low = run(f, r -> Mono.just(answer(r, .799)), JevExecution.Mode.ENFORCE, BUDGET, false);
        assertFalse(low.path("judgments").get(0).path("modelAllow").asBoolean());
        var denied = run(f, r -> Mono.just(answer(r, .8)), JevExecution.Mode.ENFORCE, BUDGET, true);
        var j = denied.path("judgments").get(0);
        assertTrue(j.path("modelAllow").asBoolean());
        assertTrue(j.path("dispatched").asBoolean());
        assertEquals("DENY", j.path("permission").asText());
        assertEquals(0, j.path("executionCount").asInt());
    }

    @Test
    void cancellationDoesNotDispatchAndObserverFailuresDoNotChangeExecution() {
        var state = AgentState.builder().build();
        var ctx = RuntimeContext.empty();
        ctx.setAgentState(state);
        var dispatches = new AtomicInteger();
        var status = new AtomicReference<JevExecution.Status>();
        var input =
                new ActingInput(
                        List.of(
                                new ToolUseBlock(
                                        "refund-id", "refund", Map.of("orderId", "A1001"))));
        var guard =
                JevAutoModeMiddleware.builder(r -> Mono.never())
                        .guardedTool("refund")
                        .execution(
                                new JevExecution.Options(
                                        JevExecution.Mode.ENFORCE,
                                        BUDGET,
                                        "test",
                                        (c, r) -> status.set(r.status())))
                        .build();
        StepVerifier.create(
                        guard.onActing(
                                null,
                                ctx,
                                input,
                                next -> {
                                    dispatches.incrementAndGet();
                                    return Flux.empty();
                                }))
                .thenCancel()
                .verify();
        assertEquals(0, dispatches.get());
        assertEquals(JevExecution.Status.CANCELLED, status.get());
        assertTrue(state.getContext().isEmpty());
        var allowing =
                JevAutoModeMiddleware.builder(r -> Mono.just(answer(r, .99)))
                        .guardedTool("refund")
                        .execution(
                                new JevExecution.Options(
                                        JevExecution.Mode.ENFORCE,
                                        BUDGET,
                                        "test",
                                        (c, r) -> {
                                            throw new IllegalStateException("observer unavailable");
                                        }))
                        .build();
        allowing.onActing(
                        null,
                        ctx,
                        input,
                        next -> {
                            dispatches.incrementAndGet();
                            return Flux.empty();
                        })
                .blockLast();
        assertEquals(1, dispatches.get());
    }

    @Test
    void concurrentSnapshotsRemainIsolated() throws Exception {
        var fixtures = JevToolGuardBenchmark.load(DATA).subList(0, 16);
        var rows =
                Flux.fromIterable(fixtures)
                        .flatMap(
                                f ->
                                        Mono.fromCallable(
                                                        () ->
                                                                run(
                                                                        f,
                                                                        r ->
                                                                                JevToolGuardBenchmark
                                                                                        .oracle(f)
                                                                                        .apply(r)
                                                                                        .delayElement(
                                                                                                Duration
                                                                                                        .ofMillis(
                                                                                                                5)),
                                                                        JevExecution.Mode.ENFORCE,
                                                                        BUDGET,
                                                                        false))
                                                .subscribeOn(Schedulers.boundedElastic()),
                                4)
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(rows);
        assertEquals(16, rows.size());
        assertEquals(16, rows.stream().map(r -> r.path("id").asText()).distinct().count());
        for (var row : rows)
            for (var j : row.path("judgments"))
                assertEquals(j.path("expectedAllow").asBoolean(), j.path("dispatched").asBoolean());
    }

    @Test
    void malformedLabelsAndSplitLeakageFailBeforeBackendUse(@TempDir Path tmp) throws Exception {
        var lines = Files.readAllLines(DATA);
        Path duplicate = tmp.resolve("duplicate.jsonl");
        Files.writeString(duplicate, lines.get(0) + "\n" + lines.get(0) + "\n");
        assertThrows(IllegalArgumentException.class, () -> JevToolGuardBenchmark.load(duplicate));
        Path leak = tmp.resolve("leak.jsonl");
        Files.writeString(
                leak,
                lines.get(0) + "\n" + lines.get(1).replace("\"calibration\"", "\"test\"") + "\n");
        assertThrows(IllegalArgumentException.class, () -> JevToolGuardBenchmark.load(leak));
    }

    @Test
    void actualHarnessCanQueryAfterDenialAndRefundOnce(@TempDir Path workspace) {
        var refundCount = new AtomicInteger();
        var queryCount = new AtomicInteger();
        var judgeCount = new AtomicInteger();
        var toolkit = new Toolkit();
        for (String name : List.of("refund", "query_order"))
            toolkit.registerAgentTool(
                    new ToolBase(
                            name,
                            name,
                            Map.of("type", "object", "properties", Map.of()),
                            false,
                            true,
                            false,
                            null,
                            false,
                            false) {
                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            (name.equals("refund") ? refundCount : queryCount).incrementAndGet();
                            return Mono.just(
                                    ToolResultBlock.text(
                                            name.equals("refund") ? "退款成功（模拟）" : "A1001 未发货"));
                        }
                    });
        var steps = new AtomicInteger();
        var lastContext = new AtomicReference<List<Msg>>();
        var model =
                new ChatModelBase() {
                    public String getModelName() {
                        return "scripted-recovery-not-a-benchmark-model";
                    }

                    protected Flux<ChatResponse> doStream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        if (steps.get() == 3) lastContext.set(List.copyOf(messages));
                        ContentBlock block =
                                switch (steps.getAndIncrement()) {
                                    case 0 ->
                                            new ToolUseBlock(
                                                    "premature-refund",
                                                    "refund",
                                                    Map.of("orderId", "A1001"));
                                    case 1 ->
                                            new ToolUseBlock(
                                                    "lookup",
                                                    "query_order",
                                                    Map.of("orderId", "A1001"));
                                    case 2 ->
                                            new ToolUseBlock(
                                                    "verified-refund",
                                                    "refund",
                                                    Map.of("orderId", "A1001"));
                                    default -> TextBlock.builder().text("已完成").build();
                                };
                        return Flux.just(ChatResponse.builder().content(List.of(block)).build());
                    }
                };
        var guard =
                JevAutoModeMiddleware.builder(
                                r -> {
                                    judgeCount.incrementAndGet();
                                    JsonNode state =
                                            JevToolGuardBenchmark.JSON.valueToTree(r.state());
                                    // This is a deterministic fixture judge, not evidence of either
                                    // live model's quality.
                                    boolean found = state.toString().contains("A1001 未发货");
                                    return Mono.just(answer(r, found ? .99 : .01));
                                })
                        .guardedTool("refund")
                        .execution(
                                new JevExecution.Options(
                                        JevExecution.Mode.ENFORCE, BUDGET, "test", (c, r) -> {}))
                        .build();
        try (var agent =
                HarnessAgent.builder()
                        .name("refund-fixture")
                        .workspace(workspace)
                        .sessionLogStore(new InMemorySessionLogStore())
                        .model(model)
                        .toolkit(toolkit)
                        .permissionContext(
                                PermissionContextState.builder()
                                        .mode(PermissionMode.BYPASS)
                                        .build())
                        .middleware(guard)
                        .build()) {
            var events =
                    agent.streamEvents(List.of(new UserMessage("先查 A1001，没发货才退款")))
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(events);
            assertEquals(2, judgeCount.get());
            assertEquals(1, queryCount.get());
            assertEquals(1, refundCount.get());
            List<String> ids =
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .map(ToolResultEndEvent::getToolCallId)
                            .toList();
            // The current Harness only publishes the core executor's tool events.
            // Middleware DENIED results are present in model context, but absent from streamEvents.
            // Track this integration limitation in the benchmark README, separately from dispatch
            // safety.
            assertTrue(ids.containsAll(List.of("lookup", "verified-refund")));
            var contextResults =
                    lastContext.get().stream()
                            .flatMap(m -> m.getContent().stream())
                            .filter(ToolResultBlock.class::isInstance)
                            .map(ToolResultBlock.class::cast)
                            .toList();
            assertEquals(
                    List.of("premature-refund", "lookup", "verified-refund"),
                    contextResults.stream().map(ToolResultBlock::getId).toList());
            assertEquals(ToolResultState.DENIED, contextResults.get(0).getState());
        }
    }
}
