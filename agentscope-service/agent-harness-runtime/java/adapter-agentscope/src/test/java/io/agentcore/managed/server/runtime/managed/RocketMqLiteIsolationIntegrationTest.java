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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** The actual LitePush transport keeps Session FIFO while another Session progresses. */
@EnabledIfEnvironmentVariable(named = "MANAGED_LITE_TEST_ENDPOINT", matches = ".+")
@Timeout(60)
class RocketMqLiteIsolationIntegrationTest {
    @Test
    void anotherSessionProgressesWhileFirstSessionKeepsItsTurnUnacked() throws Exception {
        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints(System.getenv("MANAGED_LITE_TEST_ENDPOINT"))
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .setMaxStartupAttempts(2)
                        .enableSsl(false)
                        .build();
        String topic = "ma_lite_turns_it_v2";
        String first = "session_" + UUID.randomUUID().toString().replace("-", "");
        String second = "session_" + UUID.randomUUID().toString().replace("-", "");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new CountDownLatch(1);
        var successor = new CountDownLatch(1);
        var premature = new AtomicBoolean();
        try (var producer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(config)
                                .setTopics(topic)
                                .build();
                var consumer =
                        provider.newLitePushConsumerBuilder()
                                .setClientConfiguration(config)
                                .bindTopic(topic)
                                .setConsumerGroup("ma_lite_brain_it_v2")
                                .setConsumptionThreadCount(2)
                                .setEnableFifoConsumeAccelerator(true)
                                .setMessageListener(
                                        view -> {
                                            String body =
                                                    StandardCharsets.UTF_8
                                                            .decode(view.getBody())
                                                            .toString();
                                            if (body.equals(first + ":t1")) {
                                                entered.countDown();
                                                try {
                                                    if (!release.await(30, TimeUnit.SECONDS))
                                                        return ConsumeResult.FAILURE;
                                                } catch (InterruptedException stopped) {
                                                    Thread.currentThread().interrupt();
                                                    return ConsumeResult.FAILURE;
                                                }
                                            } else if (body.equals(first + ":t2")) {
                                                premature.set(release.getCount() != 0);
                                                successor.countDown();
                                            } else if (body.equals(second + ":other"))
                                                other.countDown();
                                            return ConsumeResult.SUCCESS;
                                        })
                                .build()) {
            try {
                for (String body : new String[] {first + ":t1", first + ":t2", second + ":other"}) {
                    String session = body.substring(0, body.indexOf(':'));
                    producer.send(
                            provider.newMessageBuilder()
                                    .setTopic(topic)
                                    .setLiteTopic(session)
                                    .setBody(body.getBytes(StandardCharsets.UTF_8))
                                    .build());
                }
                assertTrue(entered.await(20, TimeUnit.SECONDS));
                assertTrue(other.await(20, TimeUnit.SECONDS), "Another Session must progress");
                assertEquals(1, successor.getCount(), "T2 must remain behind unacknowledged T1");
            } finally {
                release.countDown();
            }
            assertTrue(successor.await(20, TimeUnit.SECONDS));
            assertFalse(premature.get());
        }
    }
}
