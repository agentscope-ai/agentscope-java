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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventEmitter;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.JournalProjection;
import io.agentscope.core.journal.ThreadJournal;
import io.agentscope.core.journal.ThreadJournalStore;
import io.agentscope.core.journal.ThreadRecovery;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import reactor.core.publisher.Sinks;

/** Observe actual public SPI writer release, without polling or assuming cancellation cleanup timing. */
class ReActNativeToolCancellationTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void nativeRecoveryRetainsCompletedResultAfterHardCancel(boolean stream, boolean mutateState)
            throws Exception {
        var rawStore = new InMemoryThreadJournalStore();
        var released = new CountDownLatch(1);
        ThreadJournalStore observed =
                (key, ctx) -> {
                    var delegate = rawStore.open(key, ctx);
                    return (ThreadJournal)
                            Proxy.newProxyInstance(
                                    ThreadJournal.class.getClassLoader(),
                                    new Class<?>[] {ThreadJournal.class},
                                    (proxy, method, arguments) -> {
                                        try {
                                            Object value = method.invoke(delegate, arguments);
                                            if (method.getName().equals("release"))
                                                released.countDown();
                                            return value;
                                        } catch (InvocationTargetException ex) {
                                            throw ex.getCause();
                                        }
                                    });
                };
        var gate = Sinks.<ToolResultBlock>one();
        var slowStarted = new CountDownLatch(1);
        var slowCancelled = new CountDownLatch(1);
        var fastDone = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var toolkit = new io.agentscope.core.tool.Toolkit();
        toolkit.registerTool(
                ProgressiveToolResultTest.tool(
                        "slow",
                        p ->
                                gate.asMono()
                                        .doOnSubscribe(s -> slowStarted.countDown())
                                        .doOnCancel(slowCancelled::countDown)));
        toolkit.registerTool(
                ProgressiveToolResultTest.tool(
                        "fast",
                        p ->
                                reactor.core.publisher.Mono.fromSupplier(
                                        () -> {
                                            executions.incrementAndGet();
                                            if (mutateState)
                                                p.getRuntimeContext()
                                                        .getAgentState()
                                                        .getToolContext()
                                                        .addDiscoveredTools(
                                                                List.of("discovered-by-tool"));
                                            return ToolResultBlock.success("committed-receipt");
                                        })));
        java.util.function.Consumer<AgentEvent> collect =
                event -> {
                    if (event instanceof ToolResultEndEvent end
                            && "fast-call".equals(end.getToolCallId())) fastDone.countDown();
                };
        var ctx = RuntimeContext.builder().userId("u").sessionId("s").build();
        try (var agent =
                ReActAgent.builder()
                        .name("audit-cancel")
                        .agentId("audit-cancel")
                        .model(ProgressiveToolResultTest.model())
                        .toolkit(toolkit)
                        .threadJournalStore(observed)
                        .build()) {
            CompletableFuture<?> running =
                    stream
                            ? agent.streamEvents(List.of(), ctx)
                                    .doOnNext(collect)
                                    .collectList()
                                    .toFuture()
                            : agent.call(List.of(), ctx)
                                    .contextWrite(
                                            c ->
                                                    c.put(
                                                            AgentEventEmitter
                                                                    .FORWARDING_CONTEXT_KEY,
                                                            (AgentEventEmitter) collect::accept))
                                    .toFuture();
            try {
                assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
                assertTrue(fastDone.await(5, TimeUnit.SECONDS));
                assertTrue(running.cancel(true));
                assertTrue(slowCancelled.await(5, TimeUnit.SECONDS));
                assertTrue(
                        released.await(5, TimeUnit.SECONDS),
                        "await actual native cancellation commit/release");
                assertEquals(1, executions.get(), "fast execution count after cancellation");
                var log = rawStore.open(new ThreadKey("u", "audit-cancel", "s", "main"), ctx);
                var projection = JournalProjection.read(log);
                var restored = projection.restore();
                assertEquals(1, executions.get(), "fast execution count after projection");
                var fast =
                        restored.getContext().stream()
                                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                                .filter(r -> "fast-call".equals(r.getId()))
                                .findFirst();
                assertFalse(projection.uncertainToolCalls().contains("fast-call"));
                assertEquals(java.util.Set.of("slow-call"), projection.uncertainToolCalls());
                assertTrue(fast.isPresent(), "the checkpoint must not hide a completed result");
                if (mutateState)
                    assertEquals(
                            List.of("discovered-by-tool"),
                            restored.getToolContext().getDiscoveredTools());
                // Resolve only the actually unknown slow invocation; never authorize replay of
                // fast.
                ThreadRecovery.reconcile(
                        log,
                        Map.of(
                                "slow-call",
                                ToolResultBlock.error("slow-call", "operator verified cancellation")
                                        .withIdAndName("slow-call", "slow")),
                        "operator verified the pending slow invocation");
                assertEquals(1, executions.get(), "fast execution count after reconciliation");
                try (var resumed =
                        ReActAgent.builder()
                                .name("audit-cancel")
                                .agentId("audit-cancel")
                                .model(new MockModel("continued"))
                                .toolkit(toolkit)
                                .threadJournalStore(rawStore)
                                .build()) {
                    resumed.call(
                                    List.of(),
                                    RuntimeContext.builder().userId("u").sessionId("s").build())
                            .block(Duration.ofSeconds(5));
                }
                assertEquals(
                        1,
                        executions.get(),
                        "reconciling slow must not replay a durably completed fast tool");
                assertEquals(ToolResultState.SUCCESS, fast.orElseThrow().getState());
                assertTrue(fast.orElseThrow().getOutput().toString().contains("committed-receipt"));
            } finally {
                running.cancel(true);
                gate.tryEmitEmpty();
            }
        }
    }
}
