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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.shutdown.GracefulShutdownManager;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.ToolCallParam;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SubAgentToolOwnershipTest {
    @Test
    void registrationDoesNotClearSingletonStateOrShutdownRegistration() throws Exception {
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("shared")
                        .model(new MockModel("OK"))
                        .stateStore(new InMemoryAgentStateStore())
                        .build()) {
            agent.getAgentState()
                    .contextMutable()
                    .add(Msg.builder().textContent("keep me").build());
            new SubAgentTool(() -> agent, null);
            assertEquals(
                    List.of("keep me"),
                    agent.getAgentState().getContext().stream().map(Msg::getTextContent).toList());
            Field field = GracefulShutdownManager.class.getDeclaredField("stateSavers");
            field.setAccessible(true);
            assertTrue(
                    ((Map<?, ?>) field.get(GracefulShutdownManager.getInstance()))
                            .containsKey(agent.getAgentId()));
        }
    }

    @Test
    void explicitMetadataDoesNotInvokeProviderDuringRegistration() {
        AtomicInteger provisions = new AtomicInteger();
        new SubAgentTool(
                () -> {
                    provisions.incrementAndGet();
                    throw new AssertionError("Registration must not invoke the provider");
                },
                SubAgentConfig.builder().toolName("expert").description("An expert").build());
        assertEquals(0, provisions.get());
    }

    @Test
    void repeatedDelegationsDoNotCloseSingleton() {
        try (ReActAgent agent =
                ReActAgent.builder().name("shared").model(new MockModel("OK")).build()) {
            agent.getAgentState()
                    .contextMutable()
                    .add(Msg.builder().textContent("keep me").build());
            SubAgentTool tool =
                    new SubAgentTool(
                            () -> agent, SubAgentConfig.builder().forwardEvents(false).build());
            tool.callAsync(param("first")).block(Duration.ofSeconds(5));
            tool.callAsync(param("second")).block(Duration.ofSeconds(5));
            assertFalse(agent.getAgentState().getContext().isEmpty());
        }
    }

    @Test
    void explicitSharedPoolKeepsAllInstancesOpen() throws Exception {
        try (ReActAgent a = ReActAgent.builder().name("pool-a").model(new MockModel("A")).build();
                ReActAgent b =
                        ReActAgent.builder().name("pool-b").model(new MockModel("B")).build()) {
            a.getAgentState().contextMutable().add(Msg.builder().textContent("state-a").build());
            b.getAgentState().contextMutable().add(Msg.builder().textContent("state-b").build());
            AtomicInteger calls = new AtomicInteger();
            SubAgentTool tool =
                    new SubAgentTool(
                            () -> calls.getAndIncrement() % 2 == 0 ? a : b,
                            SubAgentConfig.builder()
                                    .toolName("pool")
                                    .description("shared pool")
                                    .forwardEvents(false)
                                    .lifecyclePolicy(SubAgentConfig.LifecyclePolicy.SHARED)
                                    .build());
            tool.callAsync(param("first")).block();
            tool.callAsync(param("second")).block();
            assertEquals("state-a", a.getAgentState().getContext().get(0).getTextContent());
            assertEquals("state-b", b.getAgentState().getContext().get(0).getTextContent());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void explicitMetadataAutoFactoryClosesProbeAndFirstInvocation() throws Exception {
        var closed = new CountDownLatch(2);
        AtomicInteger provisions = new AtomicInteger();
        SubAgentTool tool =
                new SubAgentTool(
                        () -> {
                            provisions.incrementAndGet();
                            ReActAgent agent =
                                    Mockito.spy(
                                            ReActAgent.builder()
                                                    .name("factory")
                                                    .model(new MockModel("OK"))
                                                    .build());
                            Mockito.doAnswer(
                                            call -> {
                                                call.callRealMethod();
                                                closed.countDown();
                                                return null;
                                            })
                                    .when(agent)
                                    .close();
                            return agent;
                        },
                        SubAgentConfig.builder()
                                .toolName("factory")
                                .description("factory")
                                .forwardEvents(false)
                                .build());
        assertEquals(0, provisions.get());
        tool.callAsync(param("first")).block();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        assertEquals(2, provisions.get());
    }

    private static ToolCallParam param(String message) {
        return ToolCallParam.builder().input(Map.of("message", message)).build();
    }
}
