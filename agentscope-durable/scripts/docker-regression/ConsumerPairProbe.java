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
package io.agentscope.durable.examples.brainhands.echo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;

/** Separate JVMs for the producer and each member of a SimpleConsumer group. */
public final class ConsumerPairProbe {
    public static void main(String[] args) throws Exception {
        var provider = ClientServiceProvider.loadService();
        var config = ClientConfiguration.newBuilder()
                .setEndpoints("127.0.0.1:8081")
                .enableSsl(false)
                .build();
        String topic = "paired-commands";
        if ("send".equals(args[0])) {
            try (var producer = provider.newProducerBuilder()
                    .setClientConfiguration(config)
                    .setTopics(topic)
                    .build()) {
                int count = Integer.parseInt(args[2]);
                for (int i = 0; i < count; i++) {
                    String key = args[1] + i;
                    var message = provider.newMessageBuilder()
                            .setTopic(topic)
                            .setKeys(key)
                            .setBody(key.getBytes(StandardCharsets.UTF_8))
                            .build();
                    producer.send(message);
                }
            }
            return;
        }
        try (var consumer = provider.newSimpleConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup("paired-hands")
                .setAwaitDuration(Duration.ofSeconds(10))
                .setSubscriptionExpressions(Map.of(topic, FilterExpression.SUB_ALL))
                .build()) {
            System.out.println("READY");
            while (true) {
                try {
                    for (var message : consumer.receive(16, Duration.ofSeconds(30))) {
                        String key = message.getKeys().iterator().next();
                        System.out.println("RECEIVE " + key + " " + message.getDeliveryAttempt());
                        if ("fault0".equals(key) && message.getDeliveryAttempt() == 1) {
                            System.out.println("BEFORE_ACK");
                            new CountDownLatch(1).await();
                        }
                        consumer.ack(message);
                        System.out.println("ACK " + key);
                    }
                } catch (Exception failure) {
                    System.out.println("ERROR " + failure);
                    Thread.sleep(100);
                }
            }
        }
    }
}
