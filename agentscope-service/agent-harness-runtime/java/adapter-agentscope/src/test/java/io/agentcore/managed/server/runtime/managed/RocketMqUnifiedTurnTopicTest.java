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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.java.impl.consumer.LitePushConsumerBuilderImpl;
import org.apache.rocketmq.client.java.impl.consumer.ManagedObservedLitePushConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RocketMqUnifiedTurnTopicTest {
    @Test
    void allDepthsPublishToOneParentWithSessionLiteTopicsAndOriginalDepth() throws Exception {
        var producer = mock(Producer.class);
        var receipt = mock(SendReceipt.class);
        when(receipt.getMessageId()).thenReturn(mock(MessageId.class));
        when(producer.send(any())).thenReturn(receipt);
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
            for (int depth : List.of(0, 1, 16))
                bus.publish(
                        Channel.TURNS,
                        new ManagedMessageBus.Message(
                                "turn-" + depth, "session-" + depth, new byte[] {1}, depth));
        }
        var messages = ArgumentCaptor.forClass(Message.class);
        verify(producer, times(3)).send(messages.capture());
        for (int i = 0; i < 3; i++) {
            int depth = List.of(0, 1, 16).get(i);
            var message = messages.getAllValues().get(i);
            assertEquals("turns", message.getTopic());
            assertEquals(Optional.of("session-" + depth), message.getLiteTopic());
            assertEquals(Integer.toString(depth), message.getProperties().get("ma_depth"));
        }
    }

    @Test
    void producerRegistersOnlyTheUnifiedTurnParent() throws Exception {
        var provider = mock(ClientServiceProvider.class);
        var builder = mock(ProducerBuilder.class, RETURNS_SELF);
        when(provider.newProducerBuilder()).thenReturn(builder);
        when(builder.build()).thenReturn(mock(Producer.class));
        try (var service = mockStatic(ClientServiceProvider.class)) {
            service.when(ClientServiceProvider::loadService).thenReturn(provider);
            try (var bus =
                    RocketMqManagedMessageBus.connect(
                            new RocketMqManagedMessageBus.Settings(
                                    "localhost:19981",
                                    "turns",
                                    "resumes",
                                    "events",
                                    "executes",
                                    "controls",
                                    Set.of(Channel.TURNS),
                                    Map.of(),
                                    8,
                                    4096,
                                    false,
                                    null))) {
                verify(builder).setTopics("turns");
            }
        }
    }

    @Test
    void turnSubscriptionCreatesExactlyOneConsumerAndKeepsChildDeliveryActive() {
        try (var construction = mockConstruction(RocketMqManagedPushConsumer.class)) {
            var bus =
                    new RocketMqManagedMessageBus(
                            null,
                            null,
                            null,
                            Map.of(Channel.TURNS, "turns"),
                            Set.of(),
                            Map.of(Channel.TURNS, "brain"),
                            8,
                            4096);
            try (bus;
                    var subscription = bus.subscribe(Channel.TURNS, 1, d -> {}, (d, e) -> {})) {
                assertEquals(1, construction.constructed().size());
                var consumer = construction.constructed().get(0);
                verify(consumer).start();
                var delivery =
                        new ManagedPushDelivery(
                                "delivery",
                                "consumer",
                                Channel.TURNS,
                                "turns",
                                "brain",
                                "broker",
                                1,
                                new ManagedMessageBus.Message(
                                        "child", "child-session", new byte[] {1}, 16));
                when(consumer.active(delivery)).thenReturn(true);
                assertEquals(true, bus.active(delivery));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> bus.subscribe(Channel.TURNS, 1, d -> {}, (d, e) -> {}));
            }
            verify(construction.constructed().get(0)).close();
        }
    }

    @Test
    void sharedSdkConsumerAcceptsEveryBusinessDepthAndRejectsInvalidMetadata() throws Exception {
        var provider = mock(ClientServiceProvider.class);
        var builder = mock(LitePushConsumerBuilderImpl.class, RETURNS_SELF);
        when(provider.newLitePushConsumerBuilder()).thenReturn(builder);
        var seen = new ArrayList<Integer>();
        var errors = new ArrayList<Exception>();
        try (var sdk =
                        mockConstruction(
                                ManagedObservedLitePushConsumer.class,
                                (consumer, context) ->
                                        when(consumer.startAsync()).thenReturn(consumer));
                var service = mockStatic(ClientServiceProvider.class)) {
            service.when(ClientServiceProvider::loadService).thenReturn(provider);
            var config = ClientConfiguration.newBuilder().setEndpoints("localhost:19981").build();
            try (var consumer =
                    new RocketMqManagedPushConsumer(
                            config,
                            Channel.TURNS,
                            "turns",
                            "brain",
                            1,
                            8,
                            4096,
                            d -> seen.add(d.message().depth()),
                            (d, e) -> errors.add(e))) {
                consumer.start();
                verify(builder).bindTopic("turns");
                verify(builder).setConsumerGroup("brain");
                verify(builder).setConsumptionThreadCount(1);
                var listener = ArgumentCaptor.forClass(MessageListener.class);
                verify(builder).setMessageListener(listener.capture());
                for (int depth : List.of(0, 1, 16))
                    assertEquals(
                            ConsumeResult.SUCCESS,
                            listener.getValue().consume(view("turns", Integer.toString(depth))));
                assertEquals(List.of(0, 1, 16), seen);
                for (String invalid : List.of("-1", "17", "bad"))
                    assertEquals(
                            ConsumeResult.FAILURE,
                            listener.getValue().consume(view("turns", invalid)));
                assertEquals(
                        ConsumeResult.FAILURE,
                        listener.getValue().consume(view("other-topic", "0")));
                assertEquals(4, errors.size());
            }
        }
    }

    private MessageView view(String topic, String depth) {
        var view = mock(MessageView.class);
        when(view.getTopic()).thenReturn(topic);
        when(view.getProperties()).thenReturn(Map.of("ma_logical_id", "turn", "ma_depth", depth));
        when(view.getLiteTopic()).thenReturn(Optional.of("session"));
        when(view.getBody()).thenReturn(ByteBuffer.wrap(new byte[] {1}));
        when(view.getMessageId()).thenReturn(mock(MessageId.class));
        when(view.getDeliveryAttempt()).thenReturn(1);
        return view;
    }
}
