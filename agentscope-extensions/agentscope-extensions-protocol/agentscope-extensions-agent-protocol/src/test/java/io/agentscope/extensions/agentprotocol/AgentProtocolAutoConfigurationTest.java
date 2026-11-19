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
package io.agentscope.extensions.agentprotocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.subagent.protocol.RemoteAgentEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

class AgentProtocolAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(AgentProtocolAutoConfiguration.class))
                    .withPropertyValues("agentscope.agent-protocol.enabled=true");

    @Test
    void createsInMemoryEventBusByDefault() {
        contextRunner.run(
                context ->
                        assertInstanceOf(
                                AgentProtocolTaskEventBus.class,
                                context.getBean(AgentProtocolEventBus.class)));
    }

    @Test
    void keepsUserEventBusWhenOneIsProvided() {
        AgentProtocolEventBus customEventBus = new TestEventBus();

        contextRunner
                .withBean(AgentProtocolEventBus.class, () -> customEventBus)
                .run(
                        context ->
                                assertSame(
                                        customEventBus,
                                        context.getBean(AgentProtocolEventBus.class)));
    }

    @Test
    void createsProcessLocalCollaboratorsByDefault() {
        contextRunner.run(
                context -> {
                    assertInstanceOf(
                            BaseStoreProtocolTaskRunRegistry.class,
                            context.getBean(ProtocolTaskRunRegistry.class));
                    assertInstanceOf(
                            BaseStoreProtocolTaskContextStore.class,
                            context.getBean(ProtocolTaskContextStore.class));
                });
    }

    @Test
    void protocolStoresBeanSwitchesCollaboratorsToTheSharedStore() {
        InMemoryStore shared = new InMemoryStore();
        ProtocolStores stores = ProtocolStores.on(shared);

        contextRunner
                .withBean(ProtocolStores.class, () -> stores)
                .run(
                        context -> {
                            assertSame(
                                    stores.runRegistry(),
                                    context.getBean(ProtocolTaskRunRegistry.class));
                            assertSame(
                                    stores.contextStore(),
                                    context.getBean(ProtocolTaskContextStore.class));
                            context.getBean(ProtocolSessionRegistry.class)
                                    .registerOrVerify("conv-1", "u1", "worker", "planner");
                            assertNotNull(
                                    shared.get(
                                            List.of("agent-protocol", "session-registry"),
                                            "conv-1"),
                                    "the session must land in the configured shared store");
                        });
    }

    @Test
    void keepsUserCollaboratorsWhenProvided() {
        ProtocolTaskRunRegistry customRegistry = BaseStoreProtocolTaskRunRegistry.inMemory();
        ProtocolTaskContextStore customStore = BaseStoreProtocolTaskContextStore.inMemory();

        contextRunner
                .withBean(ProtocolTaskRunRegistry.class, () -> customRegistry)
                .withBean(ProtocolTaskContextStore.class, () -> customStore)
                .run(
                        context -> {
                            assertSame(
                                    customRegistry, context.getBean(ProtocolTaskRunRegistry.class));
                            assertSame(
                                    customStore, context.getBean(ProtocolTaskContextStore.class));
                        });
    }

    @Test
    void callerParentIdentityIsOffByDefault() {
        contextRunner.run(
                context ->
                        assertFalse(
                                context.getBean(AgentProtocolProperties.class)
                                        .isUseCallerParentIdentity()));
    }

    @Test
    void bindsCallerParentIdentityFlag() {
        contextRunner
                .withPropertyValues("agentscope.agent-protocol.use-caller-parent-identity=true")
                .run(
                        context ->
                                assertTrue(
                                        context.getBean(AgentProtocolProperties.class)
                                                .isUseCallerParentIdentity()));
    }

    @Test
    void resumeLeaseWaitDefaultsToTwoSeconds() {
        contextRunner.run(
                context ->
                        assertEquals(
                                2_000L,
                                context.getBean(AgentProtocolProperties.class)
                                        .getResumeLeaseWaitMs()));
    }

    @Test
    void bindsResumeLeaseWait() {
        contextRunner
                .withPropertyValues("agentscope.agent-protocol.resume-lease-wait-ms=750")
                .run(
                        context ->
                                assertEquals(
                                        750L,
                                        context.getBean(AgentProtocolProperties.class)
                                                .getResumeLeaseWaitMs()));
    }

    private static final class TestEventBus implements AgentProtocolEventBus {

        @Override
        public RemoteAgentEvent publish(String taskId, RemoteAgentEvent event) {
            return event;
        }

        @Override
        public Flux<RemoteAgentEvent> subscribe(String taskId, long fromSeq) {
            return Flux.fromIterable(List.of());
        }

        @Override
        public void complete(String taskId) {}
    }
}
