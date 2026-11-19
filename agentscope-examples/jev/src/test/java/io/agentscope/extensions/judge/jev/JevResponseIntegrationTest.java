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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.integration.JevResponseMiddleware;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevResponseIntegrationTest {
    static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(mode, Duration.ofSeconds(2), "test", (c, r) -> {});
    }

    static JevJudge.Definition definition() {
        return new JevJudge.Definition(
                "v1",
                List.of(
                        new JevJudge.Criterion(
                                "correct",
                                new NoulQuestion("Is answer correct?", null),
                                true,
                                .2,
                                .8)));
    }

    static JevJudge judge(Function<SystemOneRequest, Mono<SystemOneResult>> caller) {
        return new JevJudge(caller, Duration.ofSeconds(1));
    }

    static SystemOneResult scored(double p) {
        return new SystemOneResult("fake", Map.of("correct", new NoulAnswer(p)), null);
    }

    static SystemOneResult safe(double p, double severity) {
        return new SystemOneResult(
                "fake",
                Map.of(
                        "jailbreak",
                        new NoulAnswer(p),
                        "harm",
                        new NoulAnswer(0d),
                        "self_harm",
                        new NoulAnswer(0d),
                        "severity",
                        new ScoreAnswer(severity, null, null, null)),
                null);
    }

    static Model model(AtomicInteger calls, Function<Integer, List<ContentBlock>> blocks) {
        return new ChatModelBase() {
            @Override
            public String getModelName() {
                return "offline";
            }

            @Override
            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.defer(
                        () ->
                                Flux.just(
                                        ChatResponse.builder()
                                                .content(blocks.apply(calls.incrementAndGet()))
                                                .build()));
            }
        };
    }

    static List<ContentBlock> text(String text) {
        return List.of(TextBlock.builder().text(text).build());
    }

    @Test
    void qualityStateSeparatesQuestionFromPairedToolEvidence() {
        var call = new ToolUseBlock("c1", "lookup", Map.of("id", "p1"), "{\"id\":\"p1\"}", null);
        var receipt =
                ToolResultBlock.text("private-evidence-marker")
                        .withIdAndName("c1", "lookup")
                        .withState(io.agentscope.core.message.ToolResultState.SUCCESS);
        var input =
                new ModelCallInput(
                        List.of(
                                new UserMessage("What is the policy?"),
                                Msg.builder()
                                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                                        .content(call)
                                        .build(),
                                Msg.builder()
                                        .role(io.agentscope.core.message.MsgRole.TOOL)
                                        .content(receipt)
                                        .build()),
                        null,
                        null,
                        null);
        var state = JevResponseMiddleware.qualityState(input, "draft");
        assertEquals("USER: What is the policy?", state.get("user_question"));
        assertTrue(state.get("prompt").toString().contains("private-evidence-marker"));
        assertEquals(List.of("private-evidence-marker"), state.get("supporting_context"));
        var paired = (Map<?, ?>) ((List<?>) state.get("tool_calls")).get(0);
        assertEquals("c1", paired.get("id"));
        assertEquals(Map.of("id", "p1"), paired.get("arguments"));
        assertEquals("private-evidence-marker", paired.get("result"));
        assertEquals(List.of(), state.get("trace_issues"));
    }

    @Test
    void defaultOffDoesNotJudgeOrBuffer() {
        var m = JevResponseMiddleware.builder().build();
        AtomicInteger calls = new AtomicInteger();
        var events =
                m.onModelCall(
                                null,
                                RuntimeContext.builder().build(),
                                new ModelCallInput(List.of(), null, null, null),
                                i -> {
                                    calls.incrementAndGet();
                                    return Flux.just(new TextBlockDeltaEvent("r", "b", "ok"));
                                })
                        .collectList()
                        .block();
        assertEquals(1, calls.get());
        assertEquals(1, events.size());
    }

    @Test
    void inputBlockNeverCallsModel() {
        AtomicInteger calls = new AtomicInteger();
        var policy = JevGuardrail.defaults(r -> Mono.just(safe(.99, 3)));
        var mw =
                JevResponseMiddleware.builder()
                        .guardrails(policy, policy, options(JevExecution.Mode.ENFORCE))
                        .build();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .model(model(calls, n -> text("bad")))
                        .middleware(mw)
                        .build();
        StepVerifier.create(agent.streamEvents(List.of(new UserMessage("unsafe"))))
                .thenConsumeWhile(e -> !(e instanceof TextBlockDeltaEvent))
                .expectError()
                .verify();
        assertEquals(0, calls.get());
    }

    @Test
    void rejectedOutputNeverPublishesOrExecutesAlreadyProposedTool() {
        AtomicInteger calls = new AtomicInteger(),
                executed = new AtomicInteger(),
                screens = new AtomicInteger();
        var toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        "write",
                        "write",
                        Map.of("type", "object", "properties", Map.of()),
                        false,
                        true,
                        false,
                        null,
                        false,
                        false) {
                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam p) {
                        executed.incrementAndGet();
                        return Mono.just(ToolResultBlock.text("written"));
                    }
                });
        var policy =
                JevGuardrail.defaults(
                        r -> Mono.just(safe(screens.incrementAndGet() == 1 ? 0 : .99, 3)));
        var mw =
                JevResponseMiddleware.builder()
                        .guardrails(policy, policy, options(JevExecution.Mode.ENFORCE))
                        .build();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .toolkit(toolkit)
                        .model(
                                model(
                                        calls,
                                        n ->
                                                List.of(
                                                        TextBlock.builder().text("unsafe").build(),
                                                        new ToolUseBlock("t", "write", Map.of()))))
                        .middleware(mw)
                        .build();
        List<AgentEvent> seen = new ArrayList<>();
        StepVerifier.create(
                        agent.streamEvents(List.of(new UserMessage("hello"))).doOnNext(seen::add))
                .thenConsumeWhile(e -> true)
                .expectError()
                .verify();
        assertEquals(0, executed.get());
        assertTrue(
                seen.stream()
                        .noneMatch(
                                e ->
                                        e instanceof TextBlockDeltaEvent
                                                || e instanceof ToolCallStartEvent));
    }

    @Test
    void revisedTextMatchesAgentResultAndNoAgentReplay() {
        AtomicInteger calls = new AtomicInteger(), revisions = new AtomicInteger();
        var judge =
                judge(
                        r ->
                                Mono.just(
                                        scored(
                                                ((Map<?, ?>) r.state()).get("answer").equals("good")
                                                        ? .99
                                                        : 0)));
        var mw =
                JevResponseMiddleware.builder()
                        .quality(judge, definition(), options(JevExecution.Mode.ENFORCE))
                        .reviser(
                                r -> {
                                    revisions.incrementAndGet();
                                    return Flux.just("good");
                                })
                        .build();
        var agent =
                ReActAgent.builder()
                        .name("test")
                        .model(model(calls, n -> text("bad")))
                        .middleware(mw)
                        .build();
        var events = agent.streamEvents(List.of(new UserMessage("answer"))).collectList().block();
        String streamed =
                events.stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(TextBlockDeltaEvent.class::cast)
                        .map(TextBlockDeltaEvent::getDelta)
                        .reduce("", String::concat);
        var result =
                events.stream()
                        .filter(AgentResultEvent.class::isInstance)
                        .map(AgentResultEvent.class::cast)
                        .findFirst()
                        .orElseThrow()
                        .getResult();
        assertEquals("good", streamed);
        assertEquals("good", result.getTextContent());
        assertEquals(1, calls.get());
        assertEquals(1, revisions.get());
    }

    @Test
    void unsafeDraftIsNotRefinedAndReviewIsConfigurable() {
        var policy = JevGuardrail.defaults(r -> Mono.just(safe(.5, 1)));
        AtomicInteger revised = new AtomicInteger();
        for (boolean block : List.of(false, true)) {
            var mw =
                    JevResponseMiddleware.builder()
                            .guardrails(null, policy, options(JevExecution.Mode.ENFORCE))
                            .blockOnReview(block)
                            .build();
            var flow =
                    mw.onModelCall(
                            null,
                            RuntimeContext.builder().build(),
                            new ModelCallInput(List.of(), null, null, null),
                            i -> Flux.just(new TextBlockDeltaEvent("r", "b", "draft")));
            if (block)
                StepVerifier.create(flow)
                        .expectError(JevResponseMiddleware.Rejected.class)
                        .verify();
            else assertEquals(1, flow.collectList().block().size());
        }
        var mw =
                JevResponseMiddleware.builder()
                        .guardrails(
                                null,
                                JevGuardrail.defaults(r -> Mono.just(safe(1, 3))),
                                options(JevExecution.Mode.ENFORCE))
                        .quality(
                                judge(r -> Mono.just(scored(0))),
                                definition(),
                                options(JevExecution.Mode.ENFORCE))
                        .reviser(
                                r -> {
                                    revised.incrementAndGet();
                                    return Flux.just("new");
                                })
                        .build();
        StepVerifier.create(
                        mw.onModelCall(
                                null,
                                RuntimeContext.builder().build(),
                                new ModelCallInput(List.of(), null, null, null),
                                i -> Flux.just(new TextBlockDeltaEvent("r", "b", "bad"))))
                .expectError()
                .verify();
        assertEquals(0, revised.get());
    }

    @Test
    void shadowKeepsEventsAndNeverRevises() {
        AtomicInteger revised = new AtomicInteger();
        var event = new TextBlockDeltaEvent("r", "b", "bad");
        var mw =
                JevResponseMiddleware.builder()
                        .guardrails(
                                null,
                                JevGuardrail.defaults(r -> Mono.error(new RuntimeException())),
                                options(JevExecution.Mode.SHADOW))
                        .quality(
                                judge(r -> Mono.just(scored(0))),
                                definition(),
                                options(JevExecution.Mode.SHADOW))
                        .reviser(
                                r -> {
                                    revised.incrementAndGet();
                                    return Flux.just("new");
                                })
                        .build();
        assertSame(
                event,
                mw.onModelCall(
                                null,
                                RuntimeContext.builder().build(),
                                new ModelCallInput(List.of(), null, null, null),
                                i -> Flux.just(event))
                        .blockLast());
        assertEquals(0, revised.get());
    }

    @Test
    void boundedBufferTimeoutAndCancellationNeverReleaseDraft() {
        var mw =
                JevResponseMiddleware.builder()
                        .quality(
                                judge(r -> Mono.never()),
                                definition(),
                                options(JevExecution.Mode.ENFORCE))
                        .maxChars(3)
                        .build();
        var input = new ModelCallInput(List.of(), null, null, null);
        var ctx = RuntimeContext.builder().build();
        StepVerifier.create(
                        mw.onModelCall(
                                null,
                                ctx,
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("r", "b", "long"))))
                .expectError()
                .verify();
        StepVerifier.withVirtualTime(
                        () ->
                                mw.onModelCall(
                                        null,
                                        ctx,
                                        input,
                                        i -> Flux.just(new TextBlockDeltaEvent("r", "b", "ok"))))
                .thenAwait(Duration.ofSeconds(3))
                .expectError()
                .verify();
        StepVerifier.create(
                        mw.onModelCall(
                                null,
                                ctx,
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("r", "b", "ok"))))
                .thenCancel()
                .verify();
    }

    @Test
    void revisionRejectsToolCallsEvenWithoutToolSchemas() {
        var input =
                new ModelCallInput(
                        List.of(),
                        List.of(),
                        null,
                        model(
                                new AtomicInteger(),
                                n -> List.of(new ToolUseBlock("t", "write", Map.of()))));
        var review = judge(r -> Mono.just(scored(0))).judge(Map.of(), definition()).block();
        StepVerifier.create(
                        JevResponseMiddleware.reviseWithModel(
                                new JevResponseMiddleware.Revision(input, "bad", review)))
                .expectError(JevResponseMiddleware.Rejected.class)
                .verify();
    }

    @Test
    void policySupportsSupportSeverityAndInvalidResponse() {
        var policy = JevGuardrail.defaults(r -> Mono.empty());
        assertEquals(JevGuardrail.Outcome.BLOCK, policy.evaluate(safe(.5, 2)).outcome());
        assertThrows(IllegalArgumentException.class, () -> policy.evaluate(safe(Double.NaN, 0)));
        assertEquals(
                JevGuardrail.Outcome.SUPPORT,
                policy.evaluate(
                                new SystemOneResult(
                                        "fake",
                                        Map.of(
                                                "jailbreak",
                                                new NoulAnswer(0d),
                                                "harm",
                                                new NoulAnswer(0d),
                                                "self_harm",
                                                new NoulAnswer(.99),
                                                "severity",
                                                new ScoreAnswer(3d, null, null, null)),
                                        null))
                        .outcome());
    }

    @Test
    void sameMiddlewareKeepsConcurrentDraftsSeparate() {
        var mw =
                JevResponseMiddleware.builder()
                        .quality(
                                judge(
                                        r ->
                                                Mono.just(
                                                        scored(
                                                                String.valueOf(
                                                                                        ((Map<?, ?>)
                                                                                                        r
                                                                                                                .state())
                                                                                                .get(
                                                                                                        "answer"))
                                                                                .startsWith("fixed")
                                                                        ? .99
                                                                        : 0))),
                                definition(),
                                options(JevExecution.Mode.ENFORCE))
                        .reviser(
                                r ->
                                        Flux.just("fixed:" + r.draft())
                                                .delayElements(Duration.ofMillis(5)))
                        .build();
        var input = new ModelCallInput(List.of(), null, null, null);
        var a =
                mw.onModelCall(
                                null,
                                RuntimeContext.builder().sessionId("a").build(),
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("a", "b", "alpha")))
                        .ofType(TextBlockDeltaEvent.class)
                        .single();
        var b =
                mw.onModelCall(
                                null,
                                RuntimeContext.builder().sessionId("b").build(),
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("b", "b", "beta")))
                        .ofType(TextBlockDeltaEvent.class)
                        .single();
        var both = Mono.zip(a, b).block();
        assertEquals("fixed:alpha", both.getT1().getDelta());
        assertEquals("fixed:beta", both.getT2().getDelta());
    }

    @Test
    void unsafeRevisionIsRejectedAndCancellationStopsBeforeModelDispatch() {
        var policy =
                JevGuardrail.defaults(
                        r ->
                                Mono.just(
                                        safe(
                                                String.valueOf(r.state()).contains("unsafe")
                                                        ? 1
                                                        : 0,
                                                3)));
        var mw =
                JevResponseMiddleware.builder()
                        .guardrails(null, policy, options(JevExecution.Mode.ENFORCE))
                        .quality(
                                judge(r -> Mono.just(scored(0))),
                                definition(),
                                options(JevExecution.Mode.ENFORCE))
                        .reviser(r -> Flux.just("unsafe revision"))
                        .build();
        var input = new ModelCallInput(List.of(new UserMessage("hello")), null, null, null);
        var ctx = RuntimeContext.builder().build();
        StepVerifier.create(
                        mw.onModelCall(
                                null,
                                ctx,
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("r", "b", "draft"))))
                .expectError()
                .verify();
        AtomicInteger dispatched = new AtomicInteger(), cancelled = new AtomicInteger();
        var pending =
                JevResponseMiddleware.builder()
                        .guardrails(
                                JevGuardrail.defaults(
                                        r ->
                                                Mono.<SystemOneResult>never()
                                                        .doOnCancel(cancelled::incrementAndGet)),
                                null,
                                options(JevExecution.Mode.ENFORCE))
                        .build();
        StepVerifier.create(
                        pending.onModelCall(
                                null,
                                ctx,
                                input,
                                i -> {
                                    dispatched.incrementAndGet();
                                    return Flux.empty();
                                }))
                .thenAwait(Duration.ofMillis(10))
                .thenCancel()
                .verify();
        assertEquals(0, dispatched.get());
        assertEquals(1, cancelled.get());
    }

    @Test
    void offlineAgentRetrievalAndRevisionExampleRuns() {
        io.agentscope.examples.jev.JevIntegrationExample.main(new String[0]);
    }

    @Test
    void unpairedToolEvidencePreventsEnforcedQualityApprovalButDoesNotAlterShadowOutput() {
        var orphan =
                Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.TOOL)
                        .name("lookup")
                        .content(
                                ToolResultBlock.text("unsupported receipt")
                                        .withIdAndName("missing-call", "lookup")
                                        .withState(
                                                io.agentscope.core.message.ToolResultState.SUCCESS))
                        .build();
        var input =
                new ModelCallInput(
                        List.of(new UserMessage("question"), orphan), List.of(), null, null);
        for (var mode : List.of(JevExecution.Mode.SHADOW, JevExecution.Mode.ENFORCE)) {
            var middleware =
                    JevResponseMiddleware.builder()
                            .quality(
                                    judge(
                                            r -> {
                                                throw new AssertionError(
                                                        "unpaired evidence must not reach judge");
                                            }),
                                    definition(),
                                    options(mode))
                            .build();
            var flow =
                    middleware.onModelCall(
                            null,
                            null,
                            input,
                            i -> Flux.just(new TextBlockDeltaEvent("reply", "text", "draft")));
            if (mode == JevExecution.Mode.ENFORCE)
                StepVerifier.create(flow)
                        .expectErrorMessage("JEV response withheld: INCOMPLETE_JUDGING_EVIDENCE")
                        .verify();
            else assertEquals("draft", ((TextBlockDeltaEvent) flow.single().block()).getDelta());
        }
    }

    @Test
    void oversizedStructuredJudgeStateIsAnExplicitAbstentionBeforeAnyRequest() {
        var middleware =
                JevResponseMiddleware.builder()
                        .maxChars(64)
                        .quality(
                                judge(
                                        r -> {
                                            throw new AssertionError("oversized evidence");
                                        }),
                                definition(),
                                options(JevExecution.Mode.ENFORCE))
                        .build();
        var input =
                new ModelCallInput(
                        List.of(new UserMessage("x".repeat(100))), List.of(), null, null);
        StepVerifier.create(
                        middleware.onModelCall(
                                null,
                                null,
                                input,
                                i -> Flux.just(new TextBlockDeltaEvent("reply", "text", "draft"))))
                .expectErrorMessage("JEV response withheld: JUDGE_STATE_LIMIT")
                .verify();
    }
}
