/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.benchmark.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics collector for AgentScope performance benchmarks.
 * Uses Micrometer to collect various performance metrics.
 */
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Timer> timers;
    private final ConcurrentMap<String, Counter> counters;
    private final ConcurrentMap<String, Gauge> gauges;
    private final ConcurrentMap<String, AtomicInteger> gaugeValues;

    public MetricsCollector() {
        this.meterRegistry = new SimpleMeterRegistry();
        this.timers = new ConcurrentHashMap<>();
        this.counters = new ConcurrentHashMap<>();
        this.gauges = new ConcurrentHashMap<>();
        this.gaugeValues = new ConcurrentHashMap<>();
    }

    /**
     * Record execution time for an operation
     *
     * @param metricName the name of the metric
     * @param duration the duration in nanoseconds
     */
    public void recordTime(String metricName, long duration) {
        Timer timer =
                timers.computeIfAbsent(
                        metricName,
                        name ->
                                Timer.builder(name)
                                        .description("Execution time for " + name)
                                        .register(meterRegistry));
        timer.record(duration, TimeUnit.NANOSECONDS);
    }

    /**
     * Record execution time for an operation with tags
     *
     * @param metricName the name of the metric
     * @param tags the tags for the metric
     * @param duration the duration in nanoseconds
     */
    public void recordTime(String metricName, Tags tags, long duration) {
        String fullMetricName = metricName + tags.hashCode(); // Simple way to create unique key
        Timer timer =
                timers.computeIfAbsent(
                        fullMetricName,
                        name ->
                                Timer.builder(metricName)
                                        .tags(tags)
                                        .description("Execution time for " + metricName)
                                        .register(meterRegistry));
        timer.record(duration, TimeUnit.NANOSECONDS);
    }

    /**
     * Increment a counter
     *
     * @param metricName the name of the counter
     * @param amount the amount to increment by
     */
    public void incrementCounter(String metricName, double amount) {
        Counter counter =
                counters.computeIfAbsent(
                        metricName,
                        name ->
                                Counter.builder(name)
                                        .description("Counter for " + name)
                                        .register(meterRegistry));
        counter.increment(amount);
    }

    /**
     * Increment a counter with tags
     *
     * @param metricName the name of the counter
     * @param tags the tags for the metric
     * @param amount the amount to increment by
     */
    public void incrementCounter(String metricName, Tags tags, double amount) {
        String fullMetricName = metricName + tags.hashCode(); // Simple way to create unique key
        Counter counter =
                counters.computeIfAbsent(
                        fullMetricName,
                        name ->
                                Counter.builder(metricName)
                                        .tags(tags)
                                        .description("Counter for " + metricName)
                                        .register(meterRegistry));
        counter.increment(amount);
    }

    /**
     * Register a gauge with a numeric value
     *
     * @param metricName the name of the gauge
     * @param value the current value of the gauge
     */
    public void registerGauge(String metricName, AtomicInteger value) {
        // TODO: Fix Gauge registration
        /*
        if (!gauges.containsKey(metricName)) {
            Gauge gauge = Gauge.builder(metricName)
                 .description("Gauge for " + metricName)
                 .register(meterRegistry, value, val -> val.doubleValue());
            gauges.put(metricName, gauge);
        }
        */
        gaugeValues.put(metricName, value);
    }

    /**
     * Update the value of a registered gauge
     *
     * @param metricName the name of the gauge
     * @param value the new value
     */
    public void updateGauge(String metricName, int value) {
        AtomicInteger gaugeValue = gaugeValues.get(metricName);
        if (gaugeValue != null) {
            gaugeValue.set(value);
        } else {
            log.warn("Gauge {} not registered, cannot update value", metricName);
        }
    }

    /**
     * Get the meter registry
     *
     * @return the meter registry
     */
    public MeterRegistry getMeterRegistry() {
        return meterRegistry;
    }

    /**
     * Get a timer by name
     *
     * @param metricName the name of the timer
     * @return the timer, or null if not found
     */
    public Timer getTimer(String metricName) {
        return timers.get(metricName);
    }

    /**
     * Get a counter by name
     *
     * @param metricName the name of the counter
     * @return the counter, or null if not found
     */
    public Counter getCounter(String metricName) {
        return counters.get(metricName);
    }

    /**
     * Get all collected metrics as a string representation
     *
     * @return string representation of all metrics
     */
    public String getMetricsAsString() {
        StringBuilder sb = new StringBuilder();
        meterRegistry.forEachMeter(
                meter -> {
                    sb.append(meter.getId().getName());
                    meter.getId()
                            .getTags()
                            .forEach(
                                    tag ->
                                            sb.append(".")
                                                    .append(tag.getKey())
                                                    .append("=")
                                                    .append(tag.getValue()));
                    sb.append(": ");

                    if (meter instanceof Timer) {
                        Timer timer = (Timer) meter;
                        sb.append("count=")
                                .append(timer.count())
                                .append(", mean=")
                                .append(timer.mean(TimeUnit.MILLISECONDS))
                                .append("ms, max=")
                                .append(timer.max(TimeUnit.MILLISECONDS))
                                .append("ms");
                    } else if (meter instanceof Counter) {
                        Counter counter = (Counter) meter;
                        sb.append(counter.count());
                    } else if (meter instanceof Gauge) {
                        Gauge gauge = (Gauge) meter;
                        sb.append(gauge.value());
                    }
                    sb.append("\n");
                });
        return sb.toString();
    }
}
