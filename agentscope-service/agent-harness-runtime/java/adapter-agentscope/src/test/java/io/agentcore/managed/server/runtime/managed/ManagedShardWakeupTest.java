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
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

class ManagedShardWakeupTest {
    @Test
    void failedShardCannotStarveOthersAndCloseStopsSends() {
        var a =
                new ManagedShardWakeup.Destination(
                        ManagedMessageBus.Channel.SESSION_PROJECTIONS, "a");
        var b =
                new ManagedShardWakeup.Destination(
                        ManagedMessageBus.Channel.SESSION_PROJECTIONS, "b");
        var seen = new ArrayList<String>();
        var wakeup =
                new ManagedShardWakeup(
                        List.of(a, b),
                        d -> {
                            seen.add(d.liteTopic());
                            if (d.equals(a)) throw new IllegalStateException("unavailable");
                        });
        wakeup.tick();
        wakeup.tick();
        wakeup.tick();
        wakeup.close();
        wakeup.tick();
        assertEquals(List.of("a", "b", "a"), seen);
    }

    @Test
    void wakeupAcknowledgesWithoutBusinessDeliveryOrSql() throws Exception {
        var source = mock(DataSource.class);
        var routing = new ManagedStableRouting(source, "cell", 1, 64);
        var listener = mock(ManagedMessageBus.Listener.class);
        var config = ClientConfiguration.newBuilder().setEndpoints("localhost:8080").build();
        var view = view("cell_v1_s48", ManagedShardWakeup.BODY);
        when(view.getTopic()).thenReturn("projections");
        try (var consumer =
                new RocketMqManagedPushConsumer(
                        config,
                        ManagedMessageBus.Channel.SESSION_PROJECTIONS,
                        "projections",
                        "group",
                        1,
                        1,
                        1024,
                        listener,
                        (d, e) -> {
                            throw new AssertionError(e);
                        })) {
            consumer.routing(routing);
            var consume =
                    RocketMqManagedPushConsumer.class.getDeclaredMethod(
                            "consume", MessageView.class);
            consume.setAccessible(true);
            assertEquals(ConsumeResult.SUCCESS, consume.invoke(consumer, view));
        }
        verifyNoInteractions(source, listener);
    }

    @Test
    void invalidMarkersCannotBypassRoutingOrConsumeBusinessEnvelopes() {
        var source = mock(DataSource.class);
        var routing = new ManagedStableRouting(source, "cell", 1, 64);
        assertEquals(64, routing.destinations().size());
        for (String wrong : List.of("other_v1_s48", "cell_v2_s48", "cell_v1_s64", "cell_v1_s048"))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            ManagedShardWakeup.matches(
                                    view(wrong, ManagedShardWakeup.BODY), routing));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManagedShardWakeup.matches(view("cell_v1_s48", new byte[] {1}), routing));
        var business = mock(MessageView.class);
        when(business.getProperties()).thenReturn(Map.of("ma_scope", "scope"));
        assertFalse(ManagedShardWakeup.matches(business, routing));
        verifyNoInteractions(source);
    }

    private MessageView view(String destination, byte[] body) {
        var view = mock(MessageView.class);
        when(view.getProperties()).thenReturn(Map.of(ManagedShardWakeup.PROPERTY, "1"));
        when(view.getLiteTopic()).thenReturn(Optional.of(destination));
        when(view.getBody()).thenAnswer(i -> ByteBuffer.wrap(body));
        return view;
    }
}
