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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Standalone SDK capability gate: it deliberately has no Service or Fake transport dependency. */
public final class LiteInitialOffsetProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0], endpoint = args[1], topic = args[2], group = args[3];
        int pairs = Integer.parseInt(args[4]);
        Path scopesFile = Path.of(args[5]);
        boolean fromLast = Boolean.parseBoolean(args[6]);
        var provider = ClientServiceProvider.loadService();
        var config =
                ClientConfiguration.newBuilder()
                        .setEndpoints(endpoint)
                        .enableSsl(false)
                        .setRequestTimeout(Duration.ofSeconds(5))
                        .build();
        List<String> scopes;
        if (mode.equals("consume")) scopes = Files.readAllLines(scopesFile);
        else {
            scopes = new ArrayList<>();
            for (int i = 0; i < pairs; i++) scopes.add("first-" + UUID.randomUUID());
            Files.write(scopesFile, scopes, StandardCharsets.UTF_8);
        }
        if (scopes.size() != pairs || Set.copyOf(scopes).size() != pairs)
            throw new IllegalArgumentException("The recorded scope set differs");
        if (mode.equals("send")) {
            send(provider, config, topic, scopes, scopesFile.resolveSibling("send-ledger.tsv"));
            System.out.println("PROBE_SEND_CONFIRMED");
            return;
        }
        var received = new ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>();
        var errors = new ConcurrentLinkedQueue<String>();
        var expected = fromLast ? List.of("next") : List.of("first", "next");
        var done = new CountDownLatch(pairs * expected.size());
        try (var consumer =
                provider.newLitePushConsumerBuilder()
                        .setClientConfiguration(config)
                        .bindTopic(topic)
                        .setConsumerGroup(group)
                        .setConsumptionThreadCount(1)
                        .setMaxCacheMessageCount(32)
                        .setMaxCacheMessageSizeInBytes(1048576)
                        .setEnableFifoConsumeAccelerator(true)
                        .setMessageListener(
                                view -> {
                                    try {
                                        String scope = view.getLiteTopic().orElseThrow();
                                        String id = view.getProperties().get("ma_logical_id");
                                        String kind = id.substring(scope.length() + 1);
                                        if (!scopes.contains(scope)
                                                || !List.of("first", "next").contains(kind)
                                                || !id.equals(scope + "-" + kind))
                                            throw new IllegalArgumentException(
                                                    "Unexpected message identity");
                                        received.computeIfAbsent(
                                                        scope, k -> new ConcurrentLinkedQueue<>())
                                                .add(kind);
                                        done.countDown();
                                        return ConsumeResult.SUCCESS;
                                    } catch (RuntimeException invalid) {
                                        errors.add(invalid.toString());
                                        return ConsumeResult.FAILURE;
                                    }
                                })
                        .build()) {
            if (mode.equals("live"))
                send(provider, config, topic, scopes, scopesFile.resolveSibling("send-ledger.tsv"));
            boolean arrived = done.await(40, TimeUnit.SECONDS);
            var bad = new ArrayList<String>();
            for (String scope : scopes) {
                var actual =
                        List.copyOf(received.getOrDefault(scope, new ConcurrentLinkedQueue<>()));
                if (!actual.equals(expected)) bad.add(scope + "=" + actual);
            }
            System.out.println(
                    "pairs="
                            + pairs
                            + ", expected="
                            + expected
                            + ", arrived="
                            + arrived
                            + ", mismatches="
                            + bad
                            + ", errors="
                            + errors);
            if (!arrived || !bad.isEmpty() || !errors.isEmpty())
                throw new AssertionError("Initial LiteTopic prefix or FIFO differs");
            Thread.sleep(500);
            System.out.println("INITIAL_OFFSET_PROBE_PASSED");
        }
    }

    private static void send(
            ClientServiceProvider provider,
            ClientConfiguration config,
            String topic,
            List<String> scopes,
            Path ledger)
            throws Exception {
        try (var producer =
                        provider.newProducerBuilder()
                                .setClientConfiguration(config)
                                .setTopics(topic)
                                .build();
                var output = Files.newBufferedWriter(ledger, StandardCharsets.UTF_8)) {
            for (String scope : scopes) {
                for (String kind : List.of("first", "next")) {
                    String id = scope + "-" + kind;
                    var receipt =
                            producer.send(
                                    provider.newMessageBuilder()
                                            .setTopic(topic)
                                            .setLiteTopic(scope)
                                            .setKeys(id)
                                            .addProperty("ma_logical_id", id)
                                            .addProperty("ma_depth", "0")
                                            .setBody(new byte[] {0})
                                            .build());
                    output.write(scope + "\t" + kind + "\t" + receipt.getMessageId());
                    output.newLine();
                    output.flush();
                }
            }
        }
    }
}
