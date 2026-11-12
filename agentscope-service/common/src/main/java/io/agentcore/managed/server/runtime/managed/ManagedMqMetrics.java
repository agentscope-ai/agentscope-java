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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.java.exception.ForbiddenException;
import org.apache.rocketmq.client.java.exception.TooManyRequestsException;
import org.slf4j.LoggerFactory;

/** Bounded channel-level metrics. Message identifiers belong only in diagnostic logs. */
public final class ManagedMqMetrics {
    private static final Pattern RESPONSE_CODE = Pattern.compile("response-code=(\\d{3,6})");
    private static final Pattern REQUEST_ID =
            Pattern.compile("(?:request-id|requestId)=([a-zA-Z0-9_-]{1,128})");
    private static final Pattern FLOW_LIMIT =
            Pattern.compile("flow limit threshold is (\\d{1,12})(?!\\d)");
    private static final Pattern FLOW_DIRECTION = Pattern.compile("\\b(send|receive) topic\\b");

    record Failure(
            String category,
            List<String> causes,
            String responseCode,
            String requestId,
            Long flowLimit,
            String flowDirection) {}

    /** Never include broker text, payloads, credentials or SQL in diagnostics or metric labels. */
    static Failure failure(Throwable failure) {
        String category = "other_failure", code = null, request = null;
        Long flowLimit = null;
        String flowDirection = null;
        var causes = new ArrayList<String>();
        for (Throwable cause = failure;
                cause != null && causes.size() < 8;
                cause = cause.getCause()) {
            causes.add(cause.getClass().getSimpleName());
            if (cause instanceof ClientException && cause.getMessage() != null) {
                var codes = RESPONSE_CODE.matcher(cause.getMessage());
                if (codes.find()) code = codes.group(1);
                var requests = REQUEST_ID.matcher(cause.getMessage());
                while (requests.find()) {
                    if (!"null".equals(requests.group(1))) {
                        request = requests.group(1);
                        break;
                    }
                }
                if (cause instanceof TooManyRequestsException) {
                    var limit = FLOW_LIMIT.matcher(cause.getMessage());
                    if (limit.find()) flowLimit = Long.parseLong(limit.group(1));
                    var direction = FLOW_DIRECTION.matcher(cause.getMessage());
                    if (direction.find()) flowDirection = direction.group(1);
                }
            }
            if (cause instanceof ForbiddenException)
                category =
                        cause.getMessage() != null
                                        && cause.getMessage().contains("too many unacked messages")
                                ? "inflight_rejected"
                                : "forbidden";
            else if (cause instanceof TooManyRequestsException) category = "throttled";
        }
        return new Failure(category, List.copyOf(causes), code, request, flowLimit, flowDirection);
    }

    private void logFailure(String operation, Throwable error, String logicalId) {
        var detail = failure(error);
        ManagedRuntimeLog.info(
                LoggerFactory.getLogger(ManagedMqMetrics.class),
                "mq." + operation + ".failed",
                null,
                null,
                "channel",
                channel,
                "topic",
                topic,
                "consumer_group",
                group,
                "logical_id",
                logicalId,
                "failure_category",
                detail.category(),
                "cause_types",
                detail.causes(),
                "response_code",
                detail.responseCode(),
                "request_id",
                detail.requestId(),
                "flow_limit_threshold",
                detail.flowLimit(),
                "flow_direction",
                detail.flowDirection());
    }

    public void publishFailure(Throwable failure, String logicalId) {
        count("publish.failure", 1);
        count("publish." + failure(failure).category(), 1);
        logFailure("publish", failure, logicalId);
    }

    private final MeterRegistry registry;
    private final Tags tags;
    private final String channel;
    private final String topic;
    private final String group;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> totals = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public ManagedMqMetrics(String channel, String topic, String group) {
        this(Metrics.globalRegistry, channel, topic, group);
    }

    public ManagedMqMetrics(MeterRegistry registry, String channel, String topic, String group) {
        this.registry = registry;
        this.channel = channel;
        this.topic = topic;
        this.group = group;
        tags = Tags.of("channel", channel, "topic", topic, "group", group);
    }

    public static boolean traceMessage(String messageId) {
        return Math.floorMod(messageId.hashCode(), 100) == 0;
    }

    public void count(String name, long amount) {
        if (amount <= 0) return;
        totals.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(amount);
        registry.counter("managed.mq." + name, tags).increment(amount);
    }

    public void receiveFailure(Throwable failure) {
        count("receive.failures", 1);
        logFailure("receive", failure, null);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ForbiddenException) {
                // This broker uses 40300 for both permissions and its unacknowledged window.
                // Keep the distinction observable without changing SDK retry semantics.
                count(
                        cause.getMessage() != null
                                        && cause.getMessage().contains("too many unacked messages")
                                ? "receive.inflight_rejected"
                                : "receive.forbidden",
                        1);
                return;
            }
            if (cause instanceof TooManyRequestsException) {
                count("receive.throttled", 1);
                return;
            }
        }
        count("receive.other_failure", 1);
    }

    public void gauge(String name, long value) {
        gaugeValue(name).set(value);
    }

    public void addGauge(String name, long delta) {
        gaugeValue(name).addAndGet(delta);
    }

    private AtomicLong gaugeValue(String name) {
        return gauges.computeIfAbsent(
                name,
                key -> {
                    var value = new AtomicLong();
                    registry.gauge("managed.mq." + key, tags, value);
                    return value;
                });
    }

    public void duration(String phase, long nanos) {
        timers.computeIfAbsent(
                        phase,
                        key ->
                                Timer.builder("managed.mq." + key)
                                        .tags(tags)
                                        .publishPercentileHistogram()
                                        .minimumExpectedValue(Duration.ofMillis(1))
                                        .maximumExpectedValue(Duration.ofMinutes(20))
                                        .register(registry))
                .record(Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    public void millis(String phase, long millis) {
        // Cross-host timestamp skew is observed separately instead of creating negative latency.
        if (millis < 0) count("clock_skew", 1);
        else duration(phase, TimeUnit.MILLISECONDS.toNanos(millis));
    }

    public Map<String, Long> snapshot() {
        var values = new TreeMap<String, Long>();
        gauges.forEach((key, value) -> values.put(key, value.get()));
        totals.forEach((key, value) -> values.put(key + "_total", value.get()));
        return Map.copyOf(values);
    }

    public void logSnapshot(String clientId) {
        ManagedRuntimeLog.info(
                LoggerFactory.getLogger(ManagedMqMetrics.class),
                "mq.metrics",
                null,
                null,
                "channel",
                channel,
                "topic",
                topic,
                "consumer_group",
                group,
                "client_id",
                clientId,
                "values",
                snapshot());
    }
}
