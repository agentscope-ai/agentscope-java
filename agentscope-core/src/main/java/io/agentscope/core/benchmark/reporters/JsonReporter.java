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
package io.agentscope.core.benchmark.reporters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.benchmark.metrics.MetricsCollector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON reporter for benchmark results.
 * Generates benchmark reports in JSON format for machine processing and CI integration.
 */
public class JsonReporter {

    private static final Logger log = LoggerFactory.getLogger(JsonReporter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final MetricsCollector metricsCollector;

    public JsonReporter(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Generate benchmark report in JSON format and save to file
     *
     * @param outputPath the path to save the JSON report
     * @throws IOException if file writing fails
     */
    public void generateReport(String outputPath) throws IOException {
        ObjectNode report = objectMapper.createObjectNode();

        // Add metadata
        report.put("timestamp", System.currentTimeMillis());
        report.put("generator", "AgentScope Benchmark Reporter");

        // Add metrics
        ObjectNode metricsNode = report.putObject("metrics");
        metricsCollector
                .getMeterRegistry()
                .forEachMeter(
                        meter -> {
                            String metricName = meter.getId().getName();
                            ObjectNode metricNode = metricsNode.putObject(metricName);

                            // Add tags
                            ObjectNode tagsNode = metricNode.putObject("tags");
                            meter.getId()
                                    .getTags()
                                    .forEach(tag -> tagsNode.put(tag.getKey(), tag.getValue()));

                            // Add metric values based on type
                            if (meter instanceof Timer) {
                                Timer timer = (Timer) meter;
                                metricNode.put("type", "timer");
                                metricNode.put("count", timer.count());
                                metricNode.put("mean_ms", timer.mean(TimeUnit.MILLISECONDS));
                                metricNode.put("max_ms", timer.max(TimeUnit.MILLISECONDS));
                                metricNode.put(
                                        "total_time_ms", timer.totalTime(TimeUnit.MILLISECONDS));
                            } else if (meter instanceof Counter) {
                                Counter counter = (Counter) meter;
                                metricNode.put("type", "counter");
                                metricNode.put("count", counter.count());
                            } else if (meter instanceof Gauge) {
                                Gauge gauge = (Gauge) meter;
                                metricNode.put("type", "gauge");
                                metricNode.put("value", gauge.value());
                            }
                        });

        // Ensure parent directory exists
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());

        // Write to file
        try (FileWriter writer = new FileWriter(outputPath)) {
            writer.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }

        log.info("JSON report generated at: {}", outputPath);
    }
}
