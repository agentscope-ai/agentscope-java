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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.builder.runtime.managed.ManagedMessageBus.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The target wildcard/FIFO/held-callback contract, independently of the retiring pull adapter. */
@EnabledIfEnvironmentVariable(named = "MANAGED_LITE_PUSH_TEST_ENDPOINT", matches = ".+")
@Timeout(180)
class RocketMqLitePushIntegrationTest {
    @Test
    void wildcardReceivesExistingAndNewSessionsWhileHeldCallbackBlocksOnlyItsSuccessor()
            throws Exception {
        String topic = required("MANAGED_LITE_PUSH_TEST_TOPIC");
        String group = required("MANAGED_LITE_PUSH_TEST_GROUP");
        int holdSeconds =
                Integer.parseInt(
                        System.getenv().getOrDefault("MANAGED_LITE_PUSH_HOLD_SECONDS", "2"));
        assertTrue(holdSeconds >= 2 && holdSeconds <= 90);
        var provider = ClientServiceProvider.loadService();
        var configuration =
                ClientConfiguration.newBuilder()
                        .setEndpoints(required("MANAGED_LITE_PUSH_TEST_ENDPOINT"))
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .setMaxStartupAttempts(2)
                        .enableSsl(false)
                        .build();
        String tenant = "push-" + UUID.randomUUID();
        var first = new ManagedScope(tenant, "test", "first");
        var other = new ManagedScope(tenant, "test", "other");
        var createdLater = new ManagedScope(tenant, "test", "created-after-consumer-start");
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var otherCompleted = new CountDownLatch(1);
        var successorCompleted = new CountDownLatch(1);
        var newSessionCompleted = new CountDownLatch(1);
        var firstReturned = new AtomicBoolean();
        var firstInvocations = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        var owner = new AtomicReference<RocketMqManagedPushConsumer>();
        var firstDelivery = new AtomicReference<ManagedPushDelivery>();
        try (var producer =
                provider.newProducerBuilder()
                        .setClientConfiguration(configuration)
                        .setTopics(topic)
                        .build()) {
            // All three messages predate the consumer. No database directory or subscribeLite call.
            for (String id : new String[] {"first", "successor", "other"})
                producer.send(
                        provider.newMessageBuilder()
                                .setTopic(topic)
                                .setLiteTopic(("other".equals(id) ? other : first).messageGroup())
                                .setKeys(id)
                                .addProperty("ma_logical_id", id)
                                .addProperty("ma_depth", "0")
                                .setBody(id.getBytes(StandardCharsets.UTF_8))
                                .build());
            try (var consumer =
                    new RocketMqManagedPushConsumer(
                            configuration,
                            Channel.TURNS,
                            topic,
                            group,
                            4,
                            32,
                            1024 * 1024,
                            delivery -> {
                                try {
                                    String id =
                                            new String(
                                                    delivery.message().body(),
                                                    StandardCharsets.UTF_8);
                                    assertEquals(id, delivery.message().id());
                                    String expected =
                                            "other".equals(id)
                                                    ? other.messageGroup()
                                                    : "new-session".equals(id)
                                                            ? createdLater.messageGroup()
                                                            : first.messageGroup();
                                    assertEquals(expected, delivery.message().messageGroup());
                                    switch (id) {
                                        case "first" -> {
                                            assertTrue(owner.get().active(delivery));
                                            firstDelivery.set(delivery);
                                            firstInvocations.incrementAndGet();
                                            firstStarted.countDown();
                                            assertTrue(
                                                    releaseFirst.await(150, TimeUnit.SECONDS),
                                                    "Held callback never released");
                                            firstReturned.set(true);
                                        }
                                        case "successor" -> {
                                            assertTrue(
                                                    firstReturned.get(),
                                                    "Successor ran before original callback"
                                                            + " completed");
                                            successorCompleted.countDown();
                                        }
                                        case "other" -> otherCompleted.countDown();
                                        case "new-session" -> newSessionCompleted.countDown();
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected probe delivery: " + id);
                                    }
                                } catch (Throwable failure) {
                                    if (failure instanceof InterruptedException)
                                        Thread.currentThread().interrupt();
                                    throw new IllegalStateException(
                                            "Push callback assertion failed", failure);
                                }
                            },
                            (delivery, failure) -> failures.add(failure))) {
                owner.set(consumer);
                consumer.start();
                try {
                    assertTrue(
                            firstStarted.await(40, TimeUnit.SECONDS),
                            "Wildcard did not deliver an existing Session");
                    assertTrue(
                            otherCompleted.await(15, TimeUnit.SECONDS),
                            "Held callback blocked another LiteTopic");
                    System.out.println("PUSH_PROBE first callback held; another Session completed");
                    producer.send(
                            provider.newMessageBuilder()
                                    .setTopic(topic)
                                    .setLiteTopic(createdLater.messageGroup())
                                    .setKeys("new-session")
                                    .addProperty("ma_logical_id", "new-session")
                                    .addProperty("ma_depth", "0")
                                    .setBody("new-session".getBytes(StandardCharsets.UTF_8))
                                    .build());
                    assertTrue(
                            newSessionCompleted.await(15, TimeUnit.SECONDS),
                            "New Session needed a dynamic subscription");
                    assertFalse(
                            successorCompleted.await(holdSeconds, TimeUnit.SECONDS),
                            "Successor bypassed held Turn");
                    assertTrue(failures.isEmpty(), failures.toString());
                    releaseFirst.countDown();
                    assertTrue(
                            successorCompleted.await(30, TimeUnit.SECONDS),
                            "Successor did not follow callback SUCCESS");
                    assertEquals(
                            1,
                            firstInvocations.get(),
                            "Original Turn callback was delivered again during the hold");
                    assertTrue(failures.isEmpty(), failures.toString());
                    assertFalse(consumer.active(firstDelivery.get()));
                    System.out.println(
                            "PUSH_PROBE wildcard, FIFO and cross-Session concurrency passed; held="
                                    + holdSeconds
                                    + "s");
                } finally {
                    releaseFirst.countDown();
                }
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(
                value != null && !value.isBlank(), "Missing explicit owned test setting: " + name);
        return value;
    }
}
