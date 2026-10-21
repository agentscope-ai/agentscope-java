/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.builder.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Negative baseline: use a fresh classic FIFO Topic with exactly one physical queue. */
@EnabledIfEnvironmentVariable(named = "MANAGED_FIFO_ISOLATION_ENDPOINT", matches = ".+")
@Timeout(90)
class RocketMqFifoIsolationIntegrationTest {
    private final Duration ttl = Duration.ofSeconds(60);

    @Test
    void anotherSessionCanRunWhileFirstSessionRetainsItsDelivery() throws Exception {
        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints(System.getenv("MANAGED_FIFO_ISOLATION_ENDPOINT"))
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .enableSsl(false)
                        .build();
        String topic = System.getenv("MANAGED_FIFO_ISOLATION_TOPIC");
        String group = System.getenv("MANAGED_FIFO_ISOLATION_GROUP");
        String first = UUID.randomUUID().toString();
        String other = UUID.randomUUID().toString();
        try (var producer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(config)
                                .setTopics(topic)
                                .build();
                var consumerA =
                        provider.newSimpleConsumerBuilder()
                                .setClientConfiguration(config)
                                .setConsumerGroup(group)
                                .setAwaitDuration(Duration.ofSeconds(2))
                                .setSubscriptionExpressions(
                                        Map.of(topic, new FilterExpression("*")))
                                .build();
                var consumerB =
                        provider.newSimpleConsumerBuilder()
                                .setClientConfiguration(config)
                                .setConsumerGroup(group)
                                .setAwaitDuration(Duration.ofSeconds(2))
                                .setSubscriptionExpressions(
                                        Map.of(topic, new FilterExpression("*")))
                                .build()) {
            producer.send(
                    provider.newMessageBuilder()
                            .setTopic(topic)
                            .setMessageGroup(first)
                            .setBody("first".getBytes(StandardCharsets.UTF_8))
                            .build());
            var held = receive(consumerA);
            assertEquals("first", body(held));
            try {
                producer.send(
                        provider.newMessageBuilder()
                                .setTopic(topic)
                                .setMessageGroup(first)
                                .setBody("successor".getBytes(StandardCharsets.UTF_8))
                                .build());
                producer.send(
                        provider.newMessageBuilder()
                                .setTopic(topic)
                                .setMessageGroup(other)
                                .setBody("other".getBytes(StandardCharsets.UTF_8))
                                .build());
                var next = receive(consumerB);
                assertEquals(
                        "other",
                        body(next),
                        "An unacked Session must block only its own successor");
                consumerB.ack(next);
            } finally {
                consumerA.ack(held);
            }
        }
    }

    private MessageView receive(SimpleConsumer consumer) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        List<MessageView> received = List.of();
        while (received.isEmpty() && System.nanoTime() < deadline)
            received = consumer.receive(1, ttl);
        assertFalse(
                received.isEmpty(),
                "No delivery: the physical FIFO queue may be blocking unrelated Sessions");
        return received.get(0);
    }

    private String body(MessageView view) {
        return StandardCharsets.UTF_8.decode(view.getBody().duplicate()).toString();
    }
}
