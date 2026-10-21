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
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.util.Map;
import java.util.Set;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.junit.jupiter.api.Test;

class RocketMqManagedMessageBusTest {
    @Test
    void producerRoleCannotWriteAnotherComponentsChannel() throws Exception {
        var producer = mock(Producer.class);
        try (var bus =
                new RocketMqManagedMessageBus(
                        ClientServiceProvider.loadService(),
                        producer,
                        null,
                        Map.of(Channel.TURNS, "turns"),
                        Set.of(Channel.TURNS),
                        Map.of(),
                        8,
                        4096)) {
            var message = new ManagedMessageBus.Message("result", "scope", new byte[] {1});
            assertThrows(
                    IllegalArgumentException.class,
                    () -> bus.publish(Channel.TURN_RESUMES, message));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> bus.publish(Channel.TOOL_EVENTS, message));
            verify(producer, never()).send(any());
        }
        verify(producer).close();
    }

    @Test
    void fiveParentTopicsAndConsumerBindingsAreDistinctWithoutDepthExpansion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("resumes", Set.of(Channel.TURNS), Map.of(), 8));
        assertThrows(
                IllegalArgumentException.class, () -> settings("tools", Set.of(), Map.of(), 8));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        settings(
                                "tools",
                                Set.of(Channel.TURNS),
                                Map.of(Channel.TURNS, "same", Channel.TURN_RESUMES, "same"),
                                8));
        assertThrows(
                IllegalArgumentException.class,
                () -> settings("tools", Set.of(Channel.TURNS), Map.of(), 0));
        assertEquals(
                "turns_d1",
                settings("turns_d1", Set.of(Channel.TURNS), Map.of(), 8).toolEventTopic());
        assertEquals(
                "brain_d1",
                settings(
                                "tools",
                                Set.of(Channel.TURNS),
                                Map.of(Channel.TURNS, "brain", Channel.TURN_RESUMES, "brain_d1"),
                                8)
                        .consumerGroups()
                        .get(Channel.TURN_RESUMES));
        assertEquals(8, settings("tools", Set.of(Channel.TURNS), Map.of(), 8).cacheMessages());
    }

    @Test
    void closedBusRejectsPublicationAndSubscriptionBeforeSdkAccess() {
        var producer = mock(Producer.class);
        var bus =
                new RocketMqManagedMessageBus(
                        ClientServiceProvider.loadService(),
                        producer,
                        null,
                        Map.of(Channel.TURNS, "turns"),
                        Set.of(Channel.TURNS),
                        Map.of(),
                        8,
                        4096);
        bus.close();
        bus.close();
        assertThrows(
                ManagedTransportException.class,
                () ->
                        bus.publish(
                                Channel.TURNS,
                                new ManagedMessageBus.Message("id", "scope", new byte[] {1})));
        assertThrows(
                ManagedTransportException.class,
                () -> bus.subscribe(Channel.TURNS, 1, d -> {}, (d, e) -> {}));
    }

    private RocketMqManagedMessageBus.Settings settings(
            String tools, Set<Channel> role, Map<Channel, String> groups, int cache) {
        return new RocketMqManagedMessageBus.Settings(
                "localhost:19981",
                "turns",
                "resumes",
                tools,
                "executes",
                "controls",
                role,
                groups,
                cache,
                4096,
                false,
                null);
    }
}
