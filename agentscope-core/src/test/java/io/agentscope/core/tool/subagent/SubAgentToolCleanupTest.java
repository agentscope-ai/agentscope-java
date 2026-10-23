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
package io.agentscope.core.tool.subagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class SubAgentToolCleanupTest {
    @Test
    void rejectedSchedulingStillClosesOwnedAgent() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        Agent agent = closeable(closed::countDown);
        SubAgentTool tool = tool(agent);
        Schedulers.onScheduleHook(
                "subagent-reject-test",
                task -> {
                    throw new RejectedExecutionException("rejected for test");
                });
        try {
            tool.callAsync(ToolCallParam.builder().input(Map.of("message", "hello")).build())
                    .block();
        } finally {
            Schedulers.resetOnScheduleHook("subagent-reject-test");
        }
        assertTrue(closed.await(2, TimeUnit.SECONDS), "Rejected cleanup must run inline");
    }

    @Test
    void shutdownWaitsForOwnedCleanupAfterAgentResult() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Agent agent =
                closeable(
                        () -> {
                            started.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
        try {
            tool(agent)
                    .callAsync(ToolCallParam.builder().input(Map.of("message", "hello")).build())
                    .block();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            manager.performGracefulShutdown();
            assertFalse(
                    manager.awaitTermination(Duration.ofMillis(100)), "cleanup is still active");
            release.countDown();
            assertTrue(manager.awaitTermination(Duration.ofSeconds(5)));
        } finally {
            release.countDown();
            manager.resetForTesting();
        }
    }

    @Test
    void rejectedStateSaveStillPersistsBeforeClosing() {
        var store = new InMemoryAgentStateStore();
        SubAgentTool tool =
                new SubAgentTool(
                        () ->
                                ReActAgent.builder()
                                        .name("factory")
                                        .model(new MockModel("OK"))
                                        .build(),
                        SubAgentConfig.builder()
                                .toolName("factory")
                                .description("factory")
                                .forwardEvents(false)
                                .stateStore(store)
                                .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.PER_CALL)
                                .build());
        Schedulers.onScheduleHook(
                "subagent-save-reject",
                task -> {
                    throw new RejectedExecutionException("save rejected for test");
                });
        try {
            tool.callAsync(
                            ToolCallParam.builder()
                                    .input(Map.of("message", "remember", "session_id", "saved"))
                                    .build())
                    .block();
        } finally {
            Schedulers.resetOnScheduleHook("subagent-save-reject");
        }
        assertTrue(
                store
                        .get(null, "saved", "agent_state", AgentState.class)
                        .orElseThrow()
                        .getContext()
                        .stream()
                        .anyMatch(m -> "remember".equals(m.getTextContent())));
    }

    @Test
    void shutdownWaitsForProviderAlreadyCreatingAnOwnedAgent() throws Exception {
        GracefulShutdownManager manager = GracefulShutdownManager.getInstance();
        manager.resetForTesting();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        Agent agent = closeable(closed::countDown);
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            creating.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("provider not released");
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                            return agent;
                        },
                        SubAgentConfig.builder()
                                .toolName("slow-provider")
                                .description("slow provider")
                                .forwardEvents(false)
                                .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.PER_CALL)
                                .build());
        var call =
                tool.callAsync(ToolCallParam.builder().input(Map.of("message", "hello")).build())
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();
        try {
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            manager.performGracefulShutdown();
            assertFalse(manager.awaitTermination(Duration.ofMillis(100)));
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(manager.awaitTermination(Duration.ofSeconds(5)));
        } finally {
            release.countDown();
            call.dispose();
            manager.resetForTesting();
        }
    }

    private static Agent closeable(Runnable close) throws Exception {
        Agent agent = mock(Agent.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(agent.call(anyList(), any(RuntimeContext.class)))
                .thenReturn(Mono.just(Msg.builder().textContent("OK").build()));
        doAnswer(
                        invocation -> {
                            close.run();
                            return null;
                        })
                .when((AutoCloseable) agent)
                .close();
        return agent;
    }

    private static SubAgentTool tool(Agent agent) {
        return new SubAgentTool(
                () -> agent,
                SubAgentConfig.builder()
                        .toolName("test")
                        .description("test cleanup")
                        .forwardEvents(false)
                        .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.PER_CALL)
                        .build());
    }
}
