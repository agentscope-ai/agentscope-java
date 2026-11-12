/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentcore.managed.server.runtime.managed.ManagedMqMetrics;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.ConsumeResultSuspend;
import org.apache.rocketmq.client.java.impl.consumer.LitePushConsumerBuilderImpl;
import org.apache.rocketmq.client.java.impl.consumer.ManagedRetryingLitePushConsumer;
import org.apache.rocketmq.client.java.message.MessageViewImpl;

/** Isolated MQ-only experiment: two replicas, unique LiteTopics, no platform DB or tool effects. */
public final class MqAckIsolation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static long number(String name, long fallback) {
        return Long.parseLong(System.getenv().getOrDefault(name, Long.toString(fallback)));
    }
    public static void main(String[] args) throws Exception {
        String topic = System.getenv("PROBE_TOPIC"), group = System.getenv("PROBE_GROUP");
        String replica = System.getenv().getOrDefault("HOSTNAME", UUID.randomUUID().toString());
        int cache = (int) number("PROBE_CACHE", 512), count = (int) number("PROBE_COUNT", 5000);
        long delay = number("PROBE_DELAY_MS", 6000), suspend = number("PROBE_SUSPEND_MS", 500);
        long sendSpacingMillis = number("PROBE_SEND_SPACING_MS", 2);
        var started = new AtomicBoolean();
        var stopped = new AtomicBoolean();
        var sent = new AtomicLong();
        var completed = new AtomicLong();
        var errors = new AtomicLong();
        var prepared = new ConcurrentHashMap<String, Long>();
        var publications = Files.newBufferedWriter(Path.of("/tmp/probe-publications.jsonl"));
        var callbacks = Files.newBufferedWriter(Path.of("/tmp/probe-callbacks.jsonl"));
        Metrics.addRegistry(new SimpleMeterRegistry());
        var metrics = new ManagedMqMetrics("TURN_RESUMES", topic, group);
        var writer = Files.newBufferedWriter(Path.of("/tmp/probe-events.jsonl"));
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 8099), 0);
        http.createContext("/status", exchange -> {
            byte[] body = JSON.writeValueAsBytes(Map.of("replica", replica, "sent", sent.get(),
                    "completed", completed.get(), "errors", errors.get(), "metrics", metrics.snapshot()));
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        http.createContext("/start", exchange -> {
            started.set(true); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        http.createContext("/stop", exchange -> {
            stopped.set(true); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        http.start();
        var provider = ClientServiceProvider.loadService();
        var configuration = ClientConfiguration.newBuilder()
                .setEndpoints(System.getenv("MANAGED_AGENTS_RUNTIME_MQ_ENDPOINTS"))
                .setCredentialProvider(new StaticSessionCredentialsProvider(
                        System.getenv("MANAGED_AGENTS_RUNTIME_MQ_ACCESS_KEY"),
                        System.getenv("MANAGED_AGENTS_RUNTIME_MQ_SECRET_KEY")))
                .setRequestTimeout(Duration.ofSeconds(10)).enableSsl(false).build();
        var builder = (LitePushConsumerBuilderImpl) provider.newLitePushConsumerBuilder()
                .setClientConfiguration(configuration).bindTopic(topic).setConsumerGroup(group)
                .setConsumptionThreadCount(1024).setMaxCacheMessageCount(cache)
                .setMaxCacheMessageSizeInBytes(67108864).setEnableFifoConsumeAccelerator(true)
                .setMessageListener(message -> {
                    var view = (MessageViewImpl) message;
                    long entry = System.currentTimeMillis(), nano = System.nanoTime();
                    metrics.count("callback.calls", 1); metrics.addGauge("callback.active", 1);
                    metrics.millis("receive_to_callback", entry - view.getDecodeTimestamp());
                    try {
                        String id = view.getMessageId().toString();
                        synchronized (callbacks) {
                            callbacks.write(JSON.writeValueAsString(Map.of("id", id, "at", entry,
                                    "received", view.getDecodeTimestamp(), "replica", replica)));
                            callbacks.newLine(); callbacks.flush();
                        }
                        long until = prepared.computeIfAbsent(id, key -> entry + suspend);
                        if (entry < until) {
                            metrics.count("callback.suspend", 1);
                            return ConsumeResultSuspend.of(Duration.ofMillis(Math.min(500, until - entry)));
                        }
                        if (delay > 0) Thread.sleep(delay);
                        synchronized (writer) {
                            writer.write(JSON.writeValueAsString(Map.of("id", id,
                                    "born", view.getBornTimestamp(),
                                    "delivery", view.getTransportDeliveryTimestamp().orElse(0L),
                                    "received", view.getDecodeTimestamp(), "entry", entry,
                                    "finished", System.currentTimeMillis(), "replica", replica)));
                            writer.newLine(); writer.flush();
                        }
                        prepared.remove(id); completed.incrementAndGet();
                        metrics.count("callback.success", 1);
                        return ConsumeResult.SUCCESS;
                    } catch (Exception failure) {
                        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                        errors.incrementAndGet(); return ConsumeResult.FAILURE;
                    } finally {
                        metrics.addGauge("callback.active", -1);
                        metrics.duration("callback.execution", System.nanoTime() - nano);
                    }
                });
        var consumer = new ManagedRetryingLitePushConsumer(builder);
        consumer.observe(metrics);
        consumer.startAsync().awaitRunning();
        var senders = Executors.newFixedThreadPool(32);
        try (var producer = provider.newProducerBuilder().setClientConfiguration(configuration)
                .setTopics(topic).build()) {
            System.out.println("PROBE_READY " + replica);
            while (!started.get() && !stopped.get()) Thread.sleep(100);
            if (stopped.get()) return;
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < count; i++) {
                if (sendSpacingMillis > 0) Thread.sleep(sendSpacingMillis);
                String lite = replica + "-" + i;
                futures.add(senders.submit(() -> {
                    try {
                        long publishStart = System.currentTimeMillis();
                        var receipt = producer.send(provider.newMessageBuilder().setTopic(topic).setLiteTopic(lite)
                                .setKeys(lite).setBody(lite.getBytes(StandardCharsets.UTF_8)).build());
                        synchronized (publications) {
                            publications.write(JSON.writeValueAsString(Map.of("id", receipt.getMessageId().toString(),
                                    "lite", lite, "start", publishStart, "confirmed", System.currentTimeMillis(), "replica", replica)));
                            publications.newLine(); publications.flush();
                        }
                        sent.incrementAndGet();
                    } catch (Exception failure) {
                        errors.incrementAndGet();
                        metrics.count("publish.failure", 1);
                        System.out.println("PROBE_SEND_ERROR " + failure.getClass().getSimpleName());
                    }
                }));
            }
            for (var future : futures) future.get();
            System.out.println("PROBE_PUBLISHED " + sent.get());
            long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
            while (!stopped.get() && System.nanoTime() < deadline) Thread.sleep(1000);
        } finally {
            senders.shutdownNow(); consumer.close(); writer.close(); publications.close(); callbacks.close(); http.stop(0);
        }
    }
}
