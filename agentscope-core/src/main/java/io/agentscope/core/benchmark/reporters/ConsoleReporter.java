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

import io.agentscope.core.benchmark.metrics.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Console reporter for benchmark results.
 * Outputs benchmark metrics to the console in a human-readable format.
 */
public class ConsoleReporter {

    private static final Logger log = LoggerFactory.getLogger(ConsoleReporter.class);

    private final MetricsCollector metricsCollector;

    public ConsoleReporter(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Generate and print benchmark report to console
     */
    public void generateReport() {
        System.out.println("========================================");
        System.out.println("  AgentScope Performance Benchmark Report");
        System.out.println("========================================");
        System.out.println();

        String metrics = metricsCollector.getMetricsAsString();
        if (metrics.isEmpty()) {
            System.out.println("No metrics collected.");
        } else {
            System.out.println("Collected Metrics:");
            System.out.println("------------------");
            System.out.print(metrics);
        }

        System.out.println("========================================");
    }
}
