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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.CapabilitySurface;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolErrorKind;
import io.agentscope.core.message.ToolErrorMetadata;
import io.agentscope.core.message.ToolExecutionDetails;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.middleware.RepeatedFailureGuard;
import io.agentscope.harness.agent.testing.HarnessQuiescence;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * End-to-end check that tool failure metadata reaches the model request through {@link
 * io.agentscope.harness.agent.middleware.RepeatedFailureGuard}.
 */
@HarnessQuiescence
class RepeatedFailureGuardIntegrationTest {

    @TempDir Path workspace;

    @Test
    void repeatedReadFailuresAddOneReminderToTheNextModelRequest() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            model.script(toolUse("read-" + i, "read_file", Map.of("path", "missing.txt")));
        }
        model.script(text("done"));

        try (HarnessAgent agent = agent(model, false)) {
            agent.call(user("find the file"), RuntimeContext.builder().sessionId(session()).build())
                    .block();
        }

        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(1)), "one failure is not a loop");
        assertFalse(hasReminder(model.inputs().get(2)), "two failures are not a loop");
        assertTrue(hasReminder(model.inputs().get(3)), "next request carries the reminder");

        Msg reminder =
                model.inputs().get(3).stream()
                        .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                        .findFirst()
                        .orElseThrow();
        String text = reminder.getTextContent();
        assertTrue(text.contains("read_file"), text);
        assertTrue(text.contains("not_found"), text);
        assertTrue(text.contains("missing.txt"), text);
    }

    @Test
    void capabilityResolutionCopiesContextAfterStateReadyWithoutLosingTheFailureWindow()
            throws Exception {
        ScriptedModel model = failingModel(3);
        model.script(text("done"));
        RepeatedFailureGuard guard = new RepeatedFailureGuard();
        AtomicReference<RuntimeContext> ready = new AtomicReference<>();
        List<RuntimeContext> produced = new CopyOnWriteArrayList<>();
        MiddlewareBase observer =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        ready.set(ctx);
                    }

                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        if (event instanceof ToolResultEndEvent) produced.add(ctx);
                    }
                };
        RuntimeContext source =
                RuntimeContext.builder()
                        .sessionId("capability-copy")
                        .put(CapabilitySurface.class, CapabilitySurface.DENY_ALL)
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("capability-copy")
                        .model(model)
                        .capabilityResolver((ctx, a) -> CapabilitySurface.ALLOW_ALL)
                        .middleware(guard)
                        .middleware(observer)
                        .build()) {
            assertEquals(
                    "done",
                    agent.call(List.of(user("inspect")), source)
                            .block(Duration.ofSeconds(10))
                            .getTextContent());
            assertNotNull(ready.get());
            assertNotSame(source, ready.get());
            assertSame(CapabilitySurface.DENY_ALL, ready.get().get(CapabilitySurface.class));
            assertEquals(3, produced.size());
            produced.forEach(
                    ctx -> {
                        assertNotSame(ready.get(), ctx, "real post-state-ready copy was taken");
                        assertSame(CapabilitySurface.ALLOW_ALL, ctx.get(CapabilitySurface.class));
                    });
            assertSame(CapabilitySurface.DENY_ALL, source.get(CapabilitySurface.class));
            assertEquals(4, model.inputs().size());
            assertTrue(
                    model.inputs().subList(0, 3).stream()
                            .noneMatch(RepeatedFailureGuardIntegrationTest::hasReminder));
            Msg reminder =
                    model.inputs().get(3).stream()
                            .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                            .findFirst()
                            .orElseThrow();
            assertTrue(reminder.getTextContent().contains("3 times"));
            assertTrue(reminder.getTextContent().contains("not_found"));
            assertTrue(reminder.getTextContent().contains("missing"));
            assertGuardWindowEmpty(guard, produced.get(0));
            assertTrue(
                    agent.getAgentState(source).getContext().stream()
                            .noneMatch(RepeatedFailureGuardIntegrationTest::isReminder));
        }
    }

    @Test
    void forwardingChildObservesItsOwnProductionAndDoesNotPolluteParent() {
        ScriptedModel childModel = failingModel(3);
        childModel.script(text("child done"));
        ScriptedModel parentModel = new ScriptedModel();
        parentModel.script(toolUse("delegate", "delegate", Map.of()));
        parentModel.script(text("parent done"));
        RepeatedFailureGuard childGuard = new RepeatedFailureGuard();
        RepeatedFailureGuard parentGuard = new RepeatedFailureGuard();
        RuntimeContext childCtx = RuntimeContext.builder().sessionId("child").build();
        try (ReActAgent child =
                ReActAgent.builder()
                        .name("child")
                        .model(childModel)
                        .middleware(childGuard)
                        .build()) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerAgentTool(
                    new ToolBase(
                            ToolBase.builder()
                                    .name("delegate")
                                    .description("Calls a fake child agent")
                                    .inputSchema(Map.of("type", "object"))
                                    .readOnly(true)) {
                        @Override
                        public Mono<PermissionDecision> checkPermissions(
                                Map<String, Object> input, PermissionContextState context) {
                            return Mono.just(PermissionDecision.allow("fixture"));
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            return Mono.deferContextual(
                                    context -> {
                                        AgentEventEmitter parentEmitter =
                                                AgentEventEmitter.fromContext(context)
                                                        .orElseThrow();
                                        AgentEventEmitter forwarding =
                                                event ->
                                                        parentEmitter.emit(
                                                                event.withSource(
                                                                        "parent/child/child"));
                                        return child.call(List.of(user("inspect")), childCtx)
                                                .contextWrite(
                                                        c ->
                                                                c.put(
                                                                        AgentEventEmitter
                                                                                .FORWARDING_CONTEXT_KEY,
                                                                        forwarding))
                                                .map(
                                                        result ->
                                                                ToolResultBlock.success(
                                                                        result.getTextContent()));
                                    });
                        }
                    });
            try (ReActAgent parent =
                    ReActAgent.builder()
                            .name("parent")
                            .model(parentModel)
                            .toolkit(toolkit)
                            .middleware(parentGuard)
                            .build()) {
                List<AgentEvent> events =
                        parent.streamEvents(
                                        user("delegate"),
                                        RuntimeContext.builder().sessionId("parent").build())
                                .collectList()
                                .block(Duration.ofSeconds(10));
                assertEquals(4, childModel.inputs().size());
                assertFalse(hasReminder(childModel.inputs().get(1)));
                assertFalse(hasReminder(childModel.inputs().get(2)));
                assertTrue(
                        hasReminder(childModel.inputs().get(3)),
                        "child notification must run before its forwarding emitter tags source");
                assertEquals(2, parentModel.inputs().size());
                assertFalse(
                        hasReminder(parentModel.inputs().get(1)),
                        "parent must not count child failures");
                List<ToolResultEndEvent> forwarded =
                        events.stream()
                                .filter(ToolResultEndEvent.class::isInstance)
                                .map(ToolResultEndEvent.class::cast)
                                .filter(e -> e.getSource() != null)
                                .toList();
                assertEquals(
                        3,
                        forwarded.size(),
                        "real child producer events reached the parent emitter");
                forwarded.forEach(
                        event -> {
                            assertEquals("parent/child/child", event.getSource());
                            assertEquals(child.getName(), event.getExecution().agentId());
                            assertEquals(ToolResultState.ERROR, event.getState());
                        });
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameThreadQueuedCallInitializesOnlyAfterAdmissionAndCancellationPreservesActiveCall(
            boolean cancelQueued) throws Exception {
        ScriptedModel model = failingModel(2);
        CountDownLatch activeWaiting = new CountDownLatch(1);
        CountDownLatch queuedWaiting = new CountDownLatch(1);
        CountDownLatch activeDone = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);
        Sinks.One<Void> activeGate = Sinks.one();
        Sinks.One<Void> queuedGate = Sinks.one();
        model.script(
                Flux.defer(
                        () -> {
                            activeWaiting.countDown();
                            return activeGate
                                    .asMono()
                                    .thenMany(
                                            toolUse(
                                                    "active-3",
                                                    "missing",
                                                    Map.of("value", "same")));
                        }));
        model.script(text("A done"));
        model.script(toolUse("queued-1", "missing", Map.of("value", "same")));
        model.script(toolUse("queued-2", "missing", Map.of("value", "same")));
        model.script(
                Flux.defer(
                        () -> {
                            queuedWaiting.countDown();
                            return queuedGate
                                    .asMono()
                                    .thenMany(
                                            toolUse(
                                                    "queued-3",
                                                    "missing",
                                                    Map.of("value", "same")));
                        }));
        model.script(text("B done"));
        RepeatedFailureGuard guard = new RepeatedFailureGuard();
        List<RuntimeContext> subscribed = new CopyOnWriteArrayList<>();
        List<RuntimeContext> admitted = new CopyOnWriteArrayList<>();
        List<AgentEvent> queuedEvents = new CopyOnWriteArrayList<>();
        AtomicReference<Msg> activeResult = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        MiddlewareBase observer =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        return Flux.defer(
                                () -> {
                                    subscribed.add(ctx);
                                    return next.apply(input);
                                });
                    }

                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        admitted.add(ctx);
                    }
                };
        BaseSubscriber<AgentEvent> queuedSubscriber =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnNext(AgentEvent event) {
                        queuedEvents.add(event);
                    }

                    @Override
                    protected void hookOnComplete() {
                        queuedDone.countDown();
                    }

                    @Override
                    protected void hookOnError(Throwable failure) {
                        error.set(failure);
                        queuedDone.countDown();
                    }
                };
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u")
                        .sessionId("queued-guard")
                        .threadId("main")
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("queued-guard")
                        .model(model)
                        .middleware(guard)
                        .middleware(observer)
                        .build()) {
            Disposable activeSubscription =
                    agent.call(List.of(user("A")), ctx)
                            .subscribe(
                                    result -> {
                                        activeResult.set(result);
                                        activeDone.countDown();
                                    },
                                    failure -> {
                                        error.set(failure);
                                        activeDone.countDown();
                                    });
            try {
                assertTrue(activeWaiting.await(10, TimeUnit.SECONDS));
                assertEquals(3, model.inputs().size());
                assertEquals(1, admitted.size());
                assertSame(subscribed.get(0), admitted.get(0));
                agent.streamEvents(user("B"), ctx).subscribe(queuedSubscriber);
                assertEquals(2, subscribed.size(), "B's wrapper has been subscribed");
                assertNotSame(subscribed.get(0), subscribed.get(1));
                assertEquals(1, queuedEvents.size());
                assertTrue(queuedEvents.get(0) instanceof AgentStartEvent);
                assertEquals(1, admitted.size(), "B has not passed the real same-thread gate");
                assertEquals(3, model.inputs().size(), "B cannot reason while A is active");
                if (cancelQueued) {
                    queuedSubscriber.dispose();
                }
                assertEquals(Sinks.EmitResult.OK, activeGate.tryEmitEmpty());
                assertTrue(activeDone.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertEquals("A done", activeResult.get().getTextContent());
                assertFalse(hasReminder(model.inputs().get(1)));
                assertFalse(hasReminder(model.inputs().get(2)));
                Msg activeReminder =
                        model.inputs().get(3).stream()
                                .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                                .findFirst()
                                .orElseThrow();
                assertTrue(
                        activeReminder.getTextContent().contains("3 times"),
                        "queued subscription/cancellation cannot erase A's two failures");
                if (cancelQueued) {
                    assertEquals(1, admitted.size(), "cancelled B never initializes a window");
                    assertEquals(4, model.inputs().size());
                    assertEquals(1, queuedEvents.size(), "cancelled B never produces tool results");
                    assertGuardEmpty(guard, admitted.get(0));
                } else {
                    assertTrue(queuedWaiting.await(10, TimeUnit.SECONDS));
                    assertEquals(2, admitted.size());
                    assertSame(subscribed.get(1), admitted.get(1));
                    assertTrue(
                            model.inputs().subList(4, 7).stream()
                                    .noneMatch(RepeatedFailureGuardIntegrationTest::hasReminder),
                            "B must initialize its own fresh window after A finishes");
                    assertEquals(Sinks.EmitResult.OK, queuedGate.tryEmitEmpty());
                    assertTrue(queuedDone.await(10, TimeUnit.SECONDS));
                    assertNull(error.get());
                    assertEquals(8, model.inputs().size());
                    Msg queuedReminder =
                            model.inputs().get(7).stream()
                                    .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                                    .findFirst()
                                    .orElseThrow();
                    assertTrue(queuedReminder.getTextContent().contains("3 times"));
                    assertEquals(
                            3,
                            queuedEvents.stream()
                                    .filter(ToolResultEndEvent.class::isInstance)
                                    .count());
                    Msg queuedResult =
                            queuedEvents.stream()
                                    .filter(AgentResultEvent.class::isInstance)
                                    .map(AgentResultEvent.class::cast)
                                    .findFirst()
                                    .orElseThrow()
                                    .getResult();
                    assertEquals("B done", queuedResult.getTextContent());
                    assertGuardEmpty(guard, admitted.get(1));
                }
                assertTrue(
                        agent.getAgentState(ctx).getContext().stream()
                                .noneMatch(RepeatedFailureGuardIntegrationTest::isReminder));
            } finally {
                activeSubscription.dispose();
                queuedSubscriber.dispose();
                activeGate.tryEmitEmpty();
                queuedGate.tryEmitEmpty();
            }
        }
    }

    @Test
    void guardRunsBeforeBufferingAndDrainingAfterFinishCannotRefillTheWindow() throws Exception {
        ScriptedModel model = failingModel(3);
        CountDownLatch fourthReasoning = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        CountDownLatch oldStreamCleaned = new CountDownLatch(1);
        CountDownLatch secondWaiting = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        Sinks.One<Void> secondGate = Sinks.one();
        AtomicReference<Disposable> secondSubscription = new AtomicReference<>();
        Sinks.One<Void> finishGate = Sinks.one();
        model.script(
                Flux.defer(
                        () -> {
                            fourthReasoning.countDown();
                            return finishGate.asMono().thenMany(text("done"));
                        }));
        RepeatedFailureGuard guard = new RepeatedFailureGuard();
        AtomicInteger producedResults = new AtomicInteger();
        AtomicReference<RuntimeContext> liveCtx = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<AgentEvent> received = new CopyOnWriteArrayList<>();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("backpressure").build();
        MiddlewareBase finishObserver =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext context,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        return next.apply(input).doFinally(signal -> oldStreamCleaned.countDown());
                    }

                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext context,
                            AgentState state,
                            List<Msg> inputMessages) {
                        liveCtx.set(context);
                    }

                    @Override
                    public void onAgentEvent(
                            Agent agent, RuntimeContext context, AgentEvent event) {
                        if (event instanceof ToolResultEndEvent) producedResults.incrementAndGet();
                    }

                    @Override
                    public Mono<Void> onAgentFinish(
                            Agent agent, RuntimeContext context, Msg result) {
                        finished.countDown();
                        return Mono.empty();
                    }
                };
        BaseSubscriber<AgentEvent> subscriber =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnSubscribe(Subscription subscription) {
                        request(1);
                    }

                    @Override
                    protected void hookOnNext(AgentEvent event) {
                        received.add(event);
                    }

                    @Override
                    protected void hookOnComplete() {
                        drained.countDown();
                    }

                    @Override
                    protected void hookOnError(Throwable failure) {
                        error.set(failure);
                        drained.countDown();
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("buffered")
                        .model(model)
                        .middleware(guard)
                        .middleware(finishObserver)
                        .build()) {
            try {
                agent.streamEvents(user("inspect"), ctx).subscribe(subscriber);
                assertTrue(
                        fourthReasoning.await(10, TimeUnit.SECONDS),
                        "lifecycle continues with no downstream demand");
                assertEquals(1, received.size());
                assertTrue(received.get(0) instanceof AgentStartEvent);
                assertEquals(3, producedResults.get());
                assertFalse(hasReminder(model.inputs().get(1)));
                assertFalse(hasReminder(model.inputs().get(2)));
                assertTrue(
                        hasReminder(model.inputs().get(3)),
                        "fourth model request sees producer-side failures while the consumer is"
                                + " paused");
                assertEquals(Sinks.EmitResult.OK, finishGate.tryEmitEmpty());
                assertTrue(finished.await(10, TimeUnit.SECONDS));
                assertGuardEmpty(guard, liveCtx.get());
                // Start a warm call before draining the old stream. Two fresh failures must not
                // inherit a reminder; late old-stream cleanup must not erase the fresh window.
                model.script(toolUse("next-1", "missing", Map.of("value", "same")));
                model.script(toolUse("next-2", "missing", Map.of("value", "same")));
                model.script(
                        Flux.defer(
                                () -> {
                                    secondWaiting.countDown();
                                    return secondGate
                                            .asMono()
                                            .thenMany(
                                                    toolUse(
                                                            "next-3",
                                                            "missing",
                                                            Map.of("value", "same")));
                                }));
                model.script(text("next done"));
                secondSubscription.set(
                        agent.call(List.of(user("again")), ctx)
                                .subscribe(
                                        result -> secondDone.countDown(),
                                        failure -> {
                                            error.set(failure);
                                            secondDone.countDown();
                                        }));
                assertTrue(secondWaiting.await(10, TimeUnit.SECONDS));
                assertEquals(5, producedResults.get());
                assertTrue(
                        model.inputs().subList(4, 7).stream()
                                .noneMatch(RepeatedFailureGuardIntegrationTest::hasReminder));
                subscriber.request(Long.MAX_VALUE);
                assertTrue(drained.await(10, TimeUnit.SECONDS));
                assertTrue(oldStreamCleaned.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertEquals(5, producedResults.get(), "draining is not another observation");
                assertGuardEmpty(guard, liveCtx.get());
                assertEquals(
                        3, received.stream().filter(ToolResultEndEvent.class::isInstance).count());
                assertEquals(Sinks.EmitResult.OK, secondGate.tryEmitEmpty());
                assertTrue(secondDone.await(10, TimeUnit.SECONDS));
                assertNull(error.get());
                assertEquals(8, model.inputs().size());
                assertTrue(
                        hasReminder(model.inputs().get(7)),
                        "old-stream cleanup preserves the new call's two failures until its third"
                                + " failure");
                assertTrue(
                        agent.getAgentState(ctx).getContext().stream()
                                .noneMatch(RepeatedFailureGuardIntegrationTest::isReminder));
            } finally {
                subscriber.dispose();
                Disposable second = secondSubscription.get();
                if (second != null) second.dispose();
                finishGate.tryEmitEmpty();
                secondGate.tryEmitEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationAndErrorClearAnUnconsumedReminder(boolean failWithError) throws Exception {
        checkCancellationAndErrorCleanup(failWithError, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void journalContextCopyStillClearsAnUnconsumedReminderOnCancellationOrError(
            boolean failWithError) throws Exception {
        checkCancellationAndErrorCleanup(failWithError, true);
    }

    private void checkCancellationAndErrorCleanup(boolean failWithError, boolean journaled)
            throws Exception {
        ScriptedModel model = failingModel(3);
        RepeatedFailureGuard guard = new RepeatedFailureGuard();
        AtomicInteger rounds = new AtomicInteger();
        AtomicReference<RuntimeContext> subscribedCtx = new AtomicReference<>();
        AtomicReference<RuntimeContext> readyCtx = new AtomicReference<>();
        AtomicReference<RuntimeContext> liveCtx = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch errorCleaned = new CountDownLatch(1);
        Sinks.One<AgentEvent> gate = Sinks.one();
        MiddlewareBase innerTermination =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onAgent(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentInput input,
                            Function<AgentInput, Flux<AgentEvent>> next) {
                        // On error, this inner finally runs after the outer guard's finally,
                        // including asynchronous failure-state persistence in the real lifecycle.
                        subscribedCtx.set(ctx);
                        return next.apply(input).doFinally(signal -> errorCleaned.countDown());
                    }

                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        readyCtx.set(ctx);
                    }
                };
        MiddlewareBase pauseBeforeGuard =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        if (rounds.incrementAndGet() == 4) {
                            liveCtx.set(ctx);
                            waiting.countDown();
                            return gate.asMono().flux();
                        }
                        return next.apply(input);
                    }
                };
        AtomicReference<Throwable> error = new AtomicReference<>();
        BaseSubscriber<AgentEvent> subscriber =
                new BaseSubscriber<>() {
                    @Override
                    protected void hookOnError(Throwable failure) {
                        error.set(failure);
                    }
                };
        RuntimeContext ctx = RuntimeContext.builder().sessionId("cleanup").build();
        ReActAgent.Builder builder =
                ReActAgent.builder()
                        .name("cleanup")
                        .model(model)
                        .middleware(pauseBeforeGuard)
                        .middleware(guard)
                        .middleware(innerTermination);
        if (journaled) {
            builder.threadJournalStore(new InMemoryThreadJournalStore());
        }
        try (ReActAgent agent = builder.build()) {
            try {
                agent.streamEvents(user("inspect"), ctx).subscribe(subscriber);
                assertTrue(waiting.await(10, TimeUnit.SECONDS));
                assertEquals(
                        3,
                        model.inputs().size(),
                        "the next guard hook has not consumed the pending reminder");
                assertNotSame(ctx, subscribedCtx.get(), "Core copied the caller context");
                assertSame(readyCtx.get(), liveCtx.get());
                if (journaled) {
                    assertNotSame(
                            subscribedCtx.get(), readyCtx.get(), "real journal copy was taken");
                } else {
                    assertSame(subscribedCtx.get(), readyCtx.get());
                }
                Object guardState = guardStateFor(guard, liveCtx.get());
                assertNotNull(guardField(guardState, "pending"), "reminder is still unconsumed");
                assertEquals(3, ((Collection<?>) guardField(guardState, "window")).size());
                if (failWithError) {
                    IllegalStateException failure = new IllegalStateException("fixture error");
                    assertEquals(Sinks.EmitResult.OK, gate.tryEmitError(failure));
                    assertTrue(errorCleaned.await(10, TimeUnit.SECONDS));
                    assertEquals(failure, error.get());
                } else {
                    subscriber.dispose();
                }
                assertGuardEmpty(guard, liveCtx.get());
                assertGuardWindowEmpty(guard, liveCtx.get());
                model.script(text("recovered"));
                assertEquals(
                        "recovered",
                        agent.call(List.of(user("again")), ctx)
                                .block(Duration.ofSeconds(10))
                                .getTextContent());
                assertFalse(hasReminder(model.inputs().get(3)));
            } finally {
                subscriber.dispose();
                gate.tryEmitEmpty();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedResultsAreObservedOnceAndPermissionAskingDoesNotInventExecution(boolean asking) {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < (asking ? 1 : 3); i++) {
            List<ToolUseBlock> calls =
                    List.of(
                            new ToolUseBlock("denied-" + i, "denied", Map.of()),
                            new ToolUseBlock("other-" + i, "other", Map.of()));
            model.script(
                    Flux.just(
                            new ChatResponse(
                                    "scripted-model",
                                    new java.util.ArrayList<>(calls),
                                    null,
                                    Map.of(),
                                    "tool_use")));
        }
        model.script(text("done"));
        Toolkit toolkit = new Toolkit();
        AtomicInteger executions = new AtomicInteger();
        for (String name : List.of("denied", "other")) {
            toolkit.registerAgentTool(
                    new ToolBase(
                            ToolBase.builder()
                                    .name(name)
                                    .description("Permission fixture")
                                    .inputSchema(Map.of("type", "object"))
                                    .readOnly(true)) {
                        @Override
                        public Mono<PermissionDecision> checkPermissions(
                                Map<String, Object> input, PermissionContextState context) {
                            return Mono.just(
                                    "denied".equals(name)
                                            ? PermissionDecision.deny("fixture denial")
                                            : asking
                                                    ? PermissionDecision.ask("fixture approval")
                                                    : PermissionDecision.allow("fixture"));
                        }

                        @Override
                        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                            executions.incrementAndGet();
                            return Mono.just(
                                    ToolResultBlock.success(
                                            "executed-" + param.getToolUseBlock().getId()));
                        }
                    });
        }
        List<String> produced = new CopyOnWriteArrayList<>();
        RepeatedFailureGuard guard = new RepeatedFailureGuard();
        AtomicReference<RuntimeContext> liveCtx = new AtomicReference<>();
        MiddlewareBase observer =
                new MiddlewareBase() {
                    @Override
                    public void onAgentStateReady(
                            Agent agent,
                            RuntimeContext ctx,
                            AgentState state,
                            List<Msg> inputMessages) {
                        liveCtx.set(ctx);
                    }

                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        if (event instanceof ToolResultStartEvent start)
                            produced.add("start:" + start.getToolCallId());
                        if (event instanceof ToolResultEndEvent end)
                            produced.add("end:" + end.getToolCallId());
                    }
                };
        RuntimeContext ctx = RuntimeContext.builder().sessionId("permissions").build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("permissions")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(guard)
                        .middleware(observer)
                        .build()) {
            List<AgentEvent> events =
                    agent.streamEvents(user("inspect"), ctx)
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertEquals(asking ? 2 : 12, produced.size());
            assertEquals(
                    produced.size(),
                    Set.copyOf(produced).size(),
                    "every tool start/end is observed once");
            List<ToolResultEndEvent> denied =
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .filter(e -> "denied".equals(e.getToolCallName()))
                            .toList();
            assertEquals(asking ? 1 : 3, denied.size());
            assertTrue(denied.stream().allMatch(e -> e.getState() == ToolResultState.DENIED));
            assertEquals(asking ? 0 : 3, executions.get());
            if (asking) {
                assertEquals(
                        1,
                        events.stream().filter(RequireUserConfirmEvent.class::isInstance).count());
                assertEquals(1, model.inputs().size());
                List<ToolResultBlock> history =
                        agent.getAgentState(ctx).getContext().stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .toList();
                assertEquals(1, history.size());
                assertEquals(ToolResultState.DENIED, history.get(0).getState());
                assertTrue(
                        events.stream()
                                .filter(io.agentscope.core.event.AgentResultEvent.class::isInstance)
                                .map(io.agentscope.core.event.AgentResultEvent.class::cast)
                                .allMatch(
                                        e ->
                                                e.getResult().getGenerateReason()
                                                        == io.agentscope.core.message.GenerateReason
                                                                .PERMISSION_ASKING));
                assertGuardEmpty(guard, liveCtx.get());
            } else {
                assertFalse(hasReminder(model.inputs().get(1)));
                assertFalse(hasReminder(model.inputs().get(2)));
                assertTrue(hasReminder(model.inputs().get(3)));
            }
        }
    }

    private static ScriptedModel failingModel(int failures) {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < failures; i++) {
            model.script(toolUse("missing-" + i, "missing", Map.of("value", "same")));
        }
        return model;
    }

    private static void assertGuardEmpty(RepeatedFailureGuard guard, RuntimeContext ctx) {
        AtomicReference<ReasoningInput> seen = new AtomicReference<>();
        guard.onReasoning(
                        null,
                        ctx,
                        new ReasoningInput(List.of(), List.of(), null),
                        input -> {
                            seen.set(input);
                            return Flux.empty();
                        })
                .blockLast(Duration.ofSeconds(10));
        assertFalse(
                hasReminder(seen.get().messages()),
                "finished/cancelled/failed turns retain no pending reminder");
    }

    private static Object guardField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object guardStateFor(RepeatedFailureGuard guard, RuntimeContext ctx)
            throws ReflectiveOperationException {
        Map<?, ?> sessions = (Map<?, ?>) guardField(guard, "sessions");
        String key =
                (ctx.getUserId() == null ? "" : ctx.getUserId())
                        + '\u0000'
                        + (ctx.getSessionId() == null ? "" : ctx.getSessionId())
                        + '\u0000'
                        + (ctx.getThreadId() == null ? "" : ctx.getThreadId());
        Object state = sessions.get(key);
        assertNotNull(state, "the admitted invocation created a guard window");
        return state;
    }

    private static void assertGuardWindowEmpty(RepeatedFailureGuard guard, RuntimeContext ctx)
            throws ReflectiveOperationException {
        Object state = guardStateFor(guard, ctx);
        synchronized (state) {
            for (String name : List.of("owner", "input", "pending")) {
                assertNull(guardField(state, name), name);
            }
            for (String name : List.of("window", "repetitions")) {
                assertTrue(((Collection<?>) guardField(state, name)).isEmpty(), name);
            }
            for (String name :
                    List.of("calls", "failuresSinceReminder", "repetitionsSinceReminder")) {
                assertTrue(((Map<?, ?>) guardField(state, name)).isEmpty(), name);
            }
            assertEquals(0, guardField(state, "interventions"));
        }
    }

    @Test
    void missingAndUngrantedToolCallsProduceIdenticalEventsAndGuardReminders() {
        String previousReminder = null;
        List<ToolFact> previousFacts = null;
        for (boolean missing : List.of(false, true)) {
            ScriptedModel model = new ScriptedModel();
            for (int i = 0; i < 3; i++) {
                model.script(toolUse("hidden-" + i, "hidden_tool", Map.of()));
            }
            model.script(text("done"));
            Toolkit toolkit = new Toolkit();
            if (!missing) {
                toolkit.registerAgentTool(
                        new SchemaOnlyTool(
                                ToolSchema.builder()
                                        .name("hidden_tool")
                                        .description("Hidden tool")
                                        .parameters(
                                                Map.of("type", "object", "properties", Map.of()))
                                        .build()));
            }
            List<AgentEvent> produced = new CopyOnWriteArrayList<>();
            MiddlewareBase observer =
                    new MiddlewareBase() {
                        @Override
                        public void onAgentEvent(
                                Agent agent, RuntimeContext ctx, AgentEvent event) {
                            produced.add(event);
                        }
                    };
            List<AgentEvent> events;
            try (ReActAgent agent =
                    ReActAgent.builder()
                            .name("guard-classification")
                            .model(model)
                            .toolkit(toolkit)
                            .capabilityResolver(
                                    (ctx, a) -> new CapabilitySurface(null, Set.of(), Set.of()))
                            .middleware(new RepeatedFailureGuard())
                            .middleware(observer)
                            .build()) {
                events =
                        agent.streamEvents(
                                        user("use the tool"),
                                        RuntimeContext.builder().sessionId(session()).build())
                                .collectList()
                                .block();
            }
            Map<String, Object> expected =
                    ToolErrorMetadata.of(ToolErrorKind.NOT_FOUND, "hidden_tool");
            List<ToolResultTextDeltaEvent> deltas =
                    events.stream()
                            .filter(ToolResultTextDeltaEvent.class::isInstance)
                            .map(ToolResultTextDeltaEvent.class::cast)
                            .toList();
            assertEquals(3, deltas.size());
            for (ToolResultTextDeltaEvent delta : deltas) {
                assertEquals("Error: Tool not found: hidden_tool", delta.getDelta());
                assertEquals(expected, delta.getMetadata());
            }
            List<ToolResultEndEvent> ends =
                    events.stream()
                            .filter(ToolResultEndEvent.class::isInstance)
                            .map(ToolResultEndEvent.class::cast)
                            .toList();
            assertEquals(3, ends.size());
            ends.forEach(
                    end -> {
                        assertEquals(expected, end.getMetadata());
                        assertEquals(ToolResultState.ERROR, end.getState());
                    });
            assertTrue(
                    events.stream().noneMatch(ToolResultDataDeltaEvent.class::isInstance),
                    "not-found results remain text-only");
            assertEquals(4, model.inputs().size());
            assertFalse(hasReminder(model.inputs().get(1)));
            assertFalse(hasReminder(model.inputs().get(2)));
            String reminder =
                    model.inputs().get(3).stream()
                            .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                            .findFirst()
                            .orElseThrow()
                            .getTextContent();
            assertTrue(reminder.contains("not_found"), reminder);
            assertTrue(reminder.contains("hidden_tool"), reminder);
            if (previousReminder != null) {
                assertEquals(previousReminder, reminder);
            }
            previousReminder = reminder;
            List<ToolFact> facts = toolFacts(events);
            assertEquals(9, facts.size());
            assertEquals(
                    facts,
                    toolFacts(produced),
                    "production observers see the same tool facts exactly once");
            if (previousFacts != null) {
                assertEquals(
                        previousFacts,
                        facts,
                        "same-name missing and ungranted tools remain indistinguishable");
            }
            previousFacts = facts;
        }
    }

    private record ToolFact(
            String phase,
            String id,
            String name,
            String text,
            ToolResultState state,
            Map<String, Object> metadata,
            String source) {}

    private static List<ToolFact> toolFacts(List<AgentEvent> events) {
        return events.stream()
                .map(
                        event -> {
                            if (event instanceof ToolResultStartEvent start) {
                                return new ToolFact(
                                        "start",
                                        start.getToolCallId(),
                                        start.getToolCallName(),
                                        null,
                                        null,
                                        start.getMetadata(),
                                        start.getSource());
                            }
                            if (event instanceof ToolResultTextDeltaEvent text) {
                                return new ToolFact(
                                        "text",
                                        text.getToolCallId(),
                                        text.getToolCallName(),
                                        text.getDelta(),
                                        null,
                                        text.getMetadata(),
                                        text.getSource());
                            }
                            if (event instanceof ToolResultEndEvent end) {
                                return new ToolFact(
                                        "end",
                                        end.getToolCallId(),
                                        end.getToolCallName(),
                                        null,
                                        end.getState(),
                                        end.getMetadata(),
                                        end.getSource());
                            }
                            return null;
                        })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedArgumentsTriggerAfterThreeWithoutEnteringActingOrPermission(boolean streaming) {
        checkInvalidArguments(true, streaming);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void schemaInvalidArgumentsAreObservedExactlyOnce(boolean streaming) {
        checkInvalidArguments(false, streaming);
    }

    private void checkInvalidArguments(boolean malformed, boolean streaming) {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            ToolUseBlock call =
                    ToolUseBlock.builder()
                            .id("invalid-" + i)
                            .name("fixture")
                            .content(malformed ? "{\"value\":" : "{}")
                            .build();
            model.script(
                    Flux.just(
                            new ChatResponse(
                                    "scripted-model",
                                    List.of(call),
                                    null,
                                    Map.of(),
                                    malformed ? "length" : "tool_use")));
        }
        model.script(text("done"));
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger permissions = new AtomicInteger();
        AtomicInteger acting = new AtomicInteger();
        List<String> produced = new CopyOnWriteArrayList<>();
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(
                new ToolBase(
                        ToolBase.builder()
                                .name("fixture")
                                .description("A fake tool that must not execute invalid input")
                                .readOnly(true)
                                .inputSchema(
                                        Map.of(
                                                "type",
                                                "object",
                                                "properties",
                                                Map.of("value", Map.of("type", "string")),
                                                "required",
                                                List.of("value")))) {
                    @Override
                    public Mono<PermissionDecision> checkPermissions(
                            Map<String, Object> input, PermissionContextState context) {
                        permissions.incrementAndGet();
                        return Mono.just(PermissionDecision.allow("fixture"));
                    }

                    @Override
                    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                        executions.incrementAndGet();
                        return Mono.just(ToolResultBlock.success("should not execute"));
                    }
                });
        MiddlewareBase capture =
                new MiddlewareBase() {
                    @Override
                    public Set<ExtensionPoint> activePoints() {
                        return Set.of(ExtensionPoint.ON_ACTING, ExtensionPoint.ON_AGENT_EVENT);
                    }

                    @Override
                    public void onAgentEvent(Agent agent, RuntimeContext ctx, AgentEvent event) {
                        if (event instanceof ToolResultStartEvent start) {
                            produced.add("start:" + start.getToolCallId());
                        } else if (event instanceof ToolResultEndEvent end) {
                            produced.add("end:" + end.getToolCallId());
                        }
                    }

                    @Override
                    public Flux<AgentEvent> onActing(
                            io.agentscope.core.agent.Agent agent,
                            RuntimeContext ctx,
                            ActingInput input,
                            Function<ActingInput, Flux<AgentEvent>> next) {
                        acting.incrementAndGet();
                        return next.apply(input);
                    }
                };
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("invalid-guard")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(new RepeatedFailureGuard())
                        .middleware(capture)
                        .build()) {
            RuntimeContext ctx = RuntimeContext.builder().sessionId(session()).build();
            if (streaming) {
                agent.streamEvents(user("inspect"), ctx).blockLast();
            } else {
                agent.call(List.of(user("inspect")), ctx).block();
            }
            List<ToolResultBlock> results =
                    agent.getAgentState(ctx).getContext().stream()
                            .flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                            .toList();
            assertEquals(3, results.size());
            results.forEach(
                    result -> {
                        assertEquals(ToolResultState.ERROR, result.getState());
                        assertEquals(
                                "invalid_argument",
                                result.getMetadata().get(ToolErrorMetadata.METADATA_ERROR_KIND));
                    });
            assertTrue(
                    agent.getAgentState(ctx).getContext().stream()
                            .noneMatch(RepeatedFailureGuardIntegrationTest::isReminder),
                    "guard reminders must not be persisted");
        }
        assertEquals(0, executions.get());
        assertEquals(
                6, produced.size(), "each malformed/schema-invalid start and end is produced once");
        assertEquals(produced.size(), Set.copyOf(produced).size());
        assertEquals(malformed ? 0 : 3, acting.get());
        if (malformed) {
            assertEquals(0, permissions.get(), "malformed calls must never ask for permission");
        }
        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(1)));
        assertFalse(
                hasReminder(model.inputs().get(2)), "ordinary failures must not be double-counted");
        assertTrue(hasReminder(model.inputs().get(3)));
        String reminder =
                model.inputs().get(3).stream()
                        .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                        .findFirst()
                        .orElseThrow()
                        .getTextContent();
        assertTrue(reminder.contains("3 times"), reminder);
        assertTrue(reminder.contains("invalid_argument"), reminder);
    }

    @Test
    void exitZeroBusinessErrorsGetANeutralNonPersistedReminder() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            model.script(
                    toolUse("business-" + i, "execute", Map.of("command", "business unknown")));
        }
        model.script(text("done"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ExitZeroFixture());
        List<AgentEvent> events;
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("repetition-it")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(new RepeatedFailureGuard())
                        .build()) {
            events =
                    agent.streamEvents(
                                    user("inspect the result"),
                                    RuntimeContext.builder().sessionId(session()).build())
                            .collectList()
                            .block();
        }
        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(1)));
        assertFalse(hasReminder(model.inputs().get(2)));
        String reminder =
                model.inputs().get(3).stream()
                        .filter(RepeatedFailureGuardIntegrationTest::isReminder)
                        .findFirst()
                        .orElseThrow()
                        .getTextContent();
        assertTrue(reminder.contains("does not imply failure"), reminder);
        assertFalse(reminder.contains("has failed"), reminder);
        List<ToolResultEndEvent> ends =
                events.stream()
                        .filter(ToolResultEndEvent.class::isInstance)
                        .map(ToolResultEndEvent.class::cast)
                        .toList();
        assertEquals(3, ends.size());
        for (ToolResultEndEvent end : ends) {
            assertEquals(ToolResultState.SUCCESS, end.getState());
            assertTrue(
                    end.getMetadata() == null
                            || !end.getMetadata()
                                    .containsKey(ToolErrorMetadata.METADATA_ERROR_KIND));
        }
    }

    public static class ExitZeroFixture {
        @Tool(description = "Returns a deterministic exit-zero result without executing a shell")
        public ToolResultBlock execute(
                @ToolParam(name = "command", description = "Command") String command) {
            return ToolResultBlock.success("Unknown public tool: unknown")
                    .withExecutionDetails(
                            new ToolExecutionDetails(
                                    "shell", ToolExecutionDetails.Outcome.SUCCEEDED, 0, false));
        }
    }

    @Test
    void disablingTheGuardLeavesTheRequestUnchanged() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 3; i++) {
            model.script(toolUse("read-" + i, "read_file", Map.of("path", "missing.txt")));
        }
        model.script(text("done"));

        try (HarnessAgent agent = agent(model, true)) {
            agent.call(user("find the file"), RuntimeContext.builder().sessionId(session()).build())
                    .block();
        }

        assertEquals(4, model.inputs().size());
        assertFalse(hasReminder(model.inputs().get(3)));
    }

    private static String session() {
        return "guard-" + UUID.randomUUID();
    }

    private HarnessAgent agent(Model model, boolean disableGuard) {
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("guard-it-" + UUID.randomUUID())
                        .model(model)
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .stateStore(new InMemoryAgentStateStore())
                        .journalEnabled(false)
                        .disableSubagents();
        if (disableGuard) {
            builder.disableRepeatedFailureGuard();
        }
        return builder.build();
    }

    private static boolean hasReminder(List<Msg> messages) {
        return messages.stream().anyMatch(RepeatedFailureGuardIntegrationTest::isReminder);
    }

    private static boolean isReminder(Msg message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null
                && "repeated_failure".equals(metadata.get("reminderKind"))
                && Boolean.TRUE.equals(metadata.get("synthetic"));
    }

    private static Msg user(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static Flux<ChatResponse> text(String text) {
        return Flux.just(
                new ChatResponse(
                        "scripted-model",
                        List.of(TextBlock.builder().text(text).build()),
                        null,
                        Map.of(),
                        "stop"));
    }

    private static Flux<ChatResponse> toolUse(String id, String name, Map<String, Object> input) {
        ToolUseBlock use =
                ToolUseBlock.builder().id(id).name(name).input(input).content("{}").build();
        return Flux.just(
                new ChatResponse("scripted-model", List.of(use), null, Map.of(), "tool_use"));
    }

    private static final class ScriptedModel implements Model {
        private final Queue<Flux<ChatResponse>> script = new ConcurrentLinkedQueue<>();
        private final List<List<Msg>> inputs = new CopyOnWriteArrayList<>();

        void script(Flux<ChatResponse> response) {
            script.add(response);
        }

        List<List<Msg>> inputs() {
            return inputs;
        }

        @Override
        public String getModelName() {
            return "scripted-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            Flux<ChatResponse> scripted = script.poll();
            return scripted != null ? scripted : text("done");
        }
    }
}
