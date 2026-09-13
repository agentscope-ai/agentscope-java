/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.redis.agui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.processor.AguiRequestProcessor;
import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

/** Run with -Dagui.redis.integration=true against a local, disposable Redis on port 16389. */
@EnabledIfSystemProperty(named = "agui.redis.integration", matches = "true")
class RedisAguiResumeLifecycleTest {
    private JedisPooled first;
    private JedisPooled second;
    private RedisAguiResumeStateStore store;
    private RedisAguiResumeStateStore replica;
    private String key;
    private static final Map<String, AguiEvent.Interrupt> PENDING =
            Map.of(
                    "I",
                    new AguiEvent.Interrupt(
                            "I", "tool_call", "approve", "tool-1", null, null, null));

    @BeforeEach
    void connect() {
        int port = Integer.getInteger("agui.redis.port", 16389);
        first = new JedisPooled("127.0.0.1", port);
        second = new JedisPooled("127.0.0.1", port);
        String prefix = "agui-recovery-test:" + UUID.randomUUID() + ":";
        key = prefix + "T";
        store = spy(new RedisAguiResumeStateStore(first, prefix));
        replica = new RedisAguiResumeStateStore(second, prefix);
        assertTrue(replica.claimRun("T", "seed").claimed());
        assertTrue(replica.replacePendingInterrupts("T", "seed", PENDING));
        replica.releaseRun("T", "seed");
    }

    @AfterEach
    void close() {
        if (first != null) {
            if (key != null) {
                first.del(key);
            }
            first.close();
        }
        if (second != null) {
            second.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releaseRetryRecoversBeforeOrAfterRedisExecutedTheFirstAttempt(boolean lostReply) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(
                        call -> {
                            assertFalse(Schedulers.isInNonBlockingThread());
                            if (attempts.incrementAndGet() == 1) {
                                if (lostReply) {
                                    call.callRealMethod();
                                }
                                throw new JedisConnectionException("temporary release failure");
                            }
                            return call.callRealMethod();
                        })
                .when(store)
                .releaseRun("T", "A");
        AguiEvent.RunFinished interrupted =
                new AguiEvent.RunFinished(
                        "T",
                        "A",
                        null,
                        new AguiEvent.RunFinishedInterruptOutcome(List.copyOf(PENDING.values())));
        List<AguiEvent> events = run(store, "A", Flux.just(interrupted));
        assertInstanceOf(AguiEvent.RunFinished.class, events.get(0));
        assertEquals(2, attempts.get());
        assertNull(first.hget(key, "activeRunId"));
        assertEquals(PENDING, replica.getPendingInterrupts("T"));
        run(replica, "B", Flux.just(new AguiEvent.RunFinished("T", "B")));
        assertFalse(first.exists(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "read", "write"})
    void redisFailuresProduceProtocolErrorsAndPreservePending(String phase) {
        JedisConnectionException failure = new JedisConnectionException("Redis unavailable");
        switch (phase) {
            case "claim" -> doThrow(failure).when(store).claimRun("T", "A");
            case "read" -> doThrow(failure).when(store).getPendingInterrupts("T");
            case "write" ->
                    doThrow(failure).when(store).replacePendingInterrupts("T", "A", Map.of());
            default -> throw new AssertionError(phase);
        }
        List<AguiEvent> events =
                run(
                        store,
                        "A",
                        Flux.just(
                                new AguiEvent.RunStarted("T", "A"),
                                new AguiEvent.RunFinished("T", "A")));
        assertEquals(2, events.size());
        assertInstanceOf(AguiEvent.RunStarted.class, events.get(0));
        AguiEvent.RunError error = assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        assertEquals("INTERNAL_ERROR", error.code());
        assertEquals("Redis unavailable", error.message());
        assertEquals(PENDING, replica.getPendingInterrupts("T"));
        assertNull(second.hget(key, "activeRunId"));
        if (phase.equals("claim")) {
            verify(store, never()).releaseRun("T", "A");
        }
        run(replica, "B", Flux.just(new AguiEvent.RunFinished("T", "B")));
        assertFalse(first.exists(key));
    }

    @Test
    void exhaustedReleaseLeavesPendingAvailableForOwnerCheckedManualRecovery() {
        doThrow(new JedisConnectionException("release unavailable"))
                .when(store)
                .releaseRun("T", "A");
        run(store, "A", Flux.empty());
        verify(store, org.mockito.Mockito.times(3)).releaseRun("T", "A");
        assertEquals("A", second.hget(key, "activeRunId"));
        assertEquals(PENDING, replica.getPendingInterrupts("T"));
        replica.releaseRun("T", "wrong-owner");
        assertEquals("A", second.hget(key, "activeRunId"));
        // In this test the old agent has completed and the finite cleanup loop has ended.
        replica.releaseRun("T", "A");
        assertEquals(PENDING, replica.getPendingInterrupts("T"));
        run(replica, "B", Flux.just(new AguiEvent.RunFinished("T", "B")));
        assertFalse(first.exists(key));
    }

    @Test
    void indeterminateClaimIsReportedWithoutBlindRelease() {
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw new JedisConnectionException("claim reply lost");
                        })
                .when(store)
                .claimRun("T", "A");
        List<AguiEvent> events = run(store, "A", Flux.never());
        assertInstanceOf(AguiEvent.RunError.class, events.get(1));
        verify(store, never()).releaseRun("T", "A");
        assertEquals("A", second.hget(key, "activeRunId"));
        assertEquals(PENDING, replica.getPendingInterrupts("T"));
    }

    private static List<AguiEvent> run(
            AguiResumeStateStore store, String runId, Flux<AguiEvent> upstream) {
        AgentResolver resolver = mock(AgentResolver.class);
        Agent agent = mock(Agent.class);
        when(resolver.resolveAgent(anyString(), anyString(), nullable(String.class)))
                .thenReturn(agent);
        AguiRequestProcessor processor =
                AguiRequestProcessor.builder()
                        .agentResolver(resolver)
                        .resumeStateStore(store)
                        .adapterFactory(
                                (a, c) ->
                                        new AguiAgentAdapter(a, c) {
                                            @Override
                                            public Flux<AguiEvent> run(
                                                    RunAgentInput input, RuntimeContext context) {
                                                return upstream.publishOn(Schedulers.parallel());
                                            }
                                        })
                        .build();
        RunAgentInput input =
                RunAgentInput.builder()
                        .threadId("T")
                        .runId(runId)
                        .resume(List.of(new AguiResume("I", AguiResume.STATUS_RESOLVED, true)))
                        .build();
        return processor
                .process(AguiRuntimeContextRequest.builder().input(input).build())
                .events()
                .subscribeOn(Schedulers.parallel())
                .collectList()
                .block(Duration.ofSeconds(5));
    }
}
