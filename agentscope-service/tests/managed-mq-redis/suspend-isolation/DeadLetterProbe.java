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

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.OffsetOption;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.java.impl.consumer.LitePushConsumerImpl;
import org.apache.rocketmq.client.java.message.MessageViewImpl;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Minimal SDK-only dead-letter reproducer: no Redis, SQL or Service runtime. */
public final class DeadLetterProbe {
    public static void main(String[] args) throws Exception {
        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints("mq-proxy:19981")
                        .enableSsl(false)
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .setMaxStartupAttempts(20)
                        .build();
        boolean lite = args[1].equals("lite");
        String topic = args[2];
        String group = args[3];
        String caseId = args[4];
        if (args[0].equals("send")) {
            try (var producer =
                    provider.newProducerBuilder()
                            .setClientConfiguration(config)
                            .setTopics(topic)
                            .build()) {
                for (int i = 5; i < args.length; i++) {
                    String kind = args[i];
                    var message =
                            provider.newMessageBuilder()
                                    .setTopic(topic)
                                    .setKeys(caseId + "-" + kind)
                                    .addProperty("probe_kind", kind)
                                    .addProperty("probe_case", caseId)
                                    .setBody(kind.getBytes(StandardCharsets.UTF_8));
                    if (lite) message.setLiteTopic("dlq-" + caseId);
                    else message.setMessageGroup(caseId);
                    var receipt = producer.send(message.build());
                    System.out.println(
                            "DLQ_SEND\t" + caseId + "\t" + kind + "\t" + receipt.getMessageId());
                }
            }
            return;
        }
        boolean accelerator = Boolean.parseBoolean(args[5]);
        Closeable consumer;
        if (lite) {
            var c =
                    provider.newLitePushConsumerBuilder()
                            .setClientConfiguration(config)
                            .bindTopic(topic)
                            .setConsumerGroup(group)
                            .setConsumptionThreadCount(1)
                            .setEnableFifoConsumeAccelerator(accelerator)
                            .setMessageListener(DeadLetterProbe::consume)
                            .build();
            if (args[6].equals("explicit"))
                c.subscribeLite("dlq-" + caseId, OffsetOption.MIN_OFFSET);
            System.out.println(
                    "DLQ_MAX_ATTEMPTS\t"
                            + ((LitePushConsumerImpl) c).getRetryPolicy().getMaxAttempts());
            consumer = c;
        } else {
            consumer =
                    provider.newPushConsumerBuilder()
                            .setClientConfiguration(config)
                            .setConsumerGroup(group)
                            .setSubscriptionExpressions(Map.of(topic, new FilterExpression("*")))
                            .setConsumptionThreadCount(1)
                            .setEnableFifoConsumeAccelerator(accelerator)
                            .setMessageListener(DeadLetterProbe::consume)
                            .build();
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        consumer.close();
                                    } catch (Exception failure) {
                                        failure.printStackTrace();
                                    }
                                }));
        Files.writeString(Path.of("/tmp/dlq-ready"), "ready\n");
        System.out.println("DLQ_READY\t" + caseId);
        new CountDownLatch(1).await();
    }

    private static ConsumeResult consume(MessageView view) {
        var impl = (MessageViewImpl) view;
        String kind = view.getProperties().get("probe_kind");
        System.out.println(
                "DLQ_EVENT\t"
                        + System.currentTimeMillis()
                        + "\t"
                        + kind
                        + "\t"
                        + view.getDeliveryAttempt()
                        + "\t"
                        + view.getTopic()
                        + "\t"
                        + view.getLiteTopic().orElse("")
                        + "\t"
                        + impl.getOffset()
                        + "\t"
                        + encode(impl.getReceiptHandle())
                        + "\t"
                        + view.getMessageId()
                        + "\t"
                        + encode(view.getProperties().toString()));
        return "FAIL".equals(kind) ? ConsumeResult.FAILURE : ConsumeResult.SUCCESS;
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
