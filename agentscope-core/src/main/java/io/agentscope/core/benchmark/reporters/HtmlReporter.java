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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTML reporter for benchmark results.
 * Generates interactive HTML reports with charts for visualization.
 */
public class HtmlReporter {

    private static final Logger log = LoggerFactory.getLogger(HtmlReporter.class);

    private final MetricsCollector metricsCollector;

    public HtmlReporter(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    /**
     * Generate benchmark report in HTML format and save to file
     *
     * @param outputPath the path to save the HTML report
     * @throws IOException if file writing fails
     */
    public void generateReport(String outputPath) throws IOException {
        String htmlContent = generateHtmlContent();

        // Ensure parent directory exists
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());

        // Write to file
        try (FileWriter writer = new FileWriter(outputPath)) {
            writer.write(htmlContent);
        }

        log.info("HTML report generated at: {}", outputPath);
    }

    /**
     * Generate the HTML content for the report
     *
     * @return the HTML content as a string
     */
    private String generateHtmlContent() {
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n")
                .append("<html>\n")
                .append("<head>\n")
                .append("    <meta charset=\"UTF-8\">\n")
                .append("    <title>AgentScope Performance Benchmark Report</title>\n")
                .append("    <script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n")
                .append("    <style>\n")
                .append("        body { font-family: Arial, sans-serif; margin: 20px; }\n")
                .append("        h1, h2 { color: #333; }\n")
                .append(
                        "        .metric-card { border: 1px solid #ddd; border-radius: 8px;"
                                + " padding: 15px; margin: 10px 0; }\n")
                .append(
                        "        .metric-value { font-size: 1.2em; font-weight: bold; color:"
                                + " #007acc; }\n")
                .append(
                        "        .chart-container { width: 100%; max-width: 800px; margin: 20px 0;"
                                + " }\n")
                .append("    </style>\n")
                .append("</head>\n")
                .append("<body>\n")
                .append("    <h1>AgentScope Performance Benchmark Report</h1>\n")
                .append("    <p>Generated on: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                .append("</p>\n")
                .append("    \n")
                .append("    <h2>Performance Metrics</h2>\n");

        // Add metrics cards
        metricsCollector
                .getMeterRegistry()
                .forEachMeter(
                        meter -> {
                            String metricName = meter.getId().getName();
                            html.append("    <div class=\"metric-card\">\n")
                                    .append("        <h3>")
                                    .append(metricName)
                                    .append("</h3>\n");

                            if (meter instanceof Timer) {
                                Timer timer = (Timer) meter;
                                html.append(
                                                "        <p>Average Time: <span"
                                                        + " class=\"metric-value\">")
                                        .append(
                                                String.format(
                                                        "%.2f ms",
                                                        timer.mean(TimeUnit.MILLISECONDS)))
                                        .append("</span></p>\n")
                                        .append(
                                                "        <p>Max Time: <span"
                                                        + " class=\"metric-value\">")
                                        .append(
                                                String.format(
                                                        "%.2f ms",
                                                        timer.max(TimeUnit.MILLISECONDS)))
                                        .append("</span></p>\n")
                                        .append(
                                                "        <p>Total Calls: <span"
                                                        + " class=\"metric-value\">")
                                        .append(timer.count())
                                        .append("</span></p>\n");
                            } else if (meter instanceof Counter) {
                                Counter counter = (Counter) meter;
                                html.append("        <p>Count: <span class=\"metric-value\">")
                                        .append(String.format("%.0f", counter.count()))
                                        .append("</span></p>\n");
                            } else if (meter instanceof Gauge) {
                                Gauge gauge = (Gauge) meter;
                                html.append("        <p>Value: <span class=\"metric-value\">")
                                        .append(String.format("%.2f", gauge.value()))
                                        .append("</span></p>\n");
                            }

                            html.append("    </div>\n");
                        });

        html.append("    \n")
                .append("    <h2>Visualizations</h2>\n")
                .append("    <div class=\"chart-container\">\n")
                .append("        <canvas id=\"latencyChart\"></canvas>\n")
                .append("    </div>\n")
                .append("    \n")
                .append("    <script>\n")
                .append(
                        "        // Sample chart - in a real implementation, this would be"
                                + " populated with actual data\n")
                .append(
                        "        const ctx ="
                                + " document.getElementById('latencyChart').getContext('2d');\n")
                .append("        new Chart(ctx, {\n")
                .append("            type: 'bar',\n")
                .append("            data: {\n")
                .append(
                        "                labels: ['Sample Metric 1', 'Sample Metric 2', 'Sample"
                                + " Metric 3'],\n")
                .append("                datasets: [{\n")
                .append("                    label: 'Latency (ms)',\n")
                .append("                    data: [12.5, 8.3, 15.7],\n")
                .append("                    backgroundColor: 'rgba(54, 162, 235, 0.2)',\n")
                .append("                    borderColor: 'rgba(54, 162, 235, 1)',\n")
                .append("                    borderWidth: 1\n")
                .append("                }]\n")
                .append("            },\n")
                .append("            options: {\n")
                .append("                scales: {\n")
                .append("                    y: {\n")
                .append("                        beginAtZero: true\n")
                .append("                    }\n")
                .append("                }\n")
                .append("            }\n")
                .append("        });\n")
                .append("    </script>\n")
                .append("</body>\n")
                .append("</html>");

        return html.toString();
    }
}
