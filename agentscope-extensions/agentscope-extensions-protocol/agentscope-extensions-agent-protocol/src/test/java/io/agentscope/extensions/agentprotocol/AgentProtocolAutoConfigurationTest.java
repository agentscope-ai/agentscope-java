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

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

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
