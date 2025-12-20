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
package io.agentscope.core.benchmark;

// import io.agentscope.core.benchmark.scenarios.*;
import io.agentscope.core.benchmark.metrics.MetricsCollector;
import io.agentscope.core.benchmark.reporters.ConsoleReporter;
import io.agentscope.core.benchmark.reporters.HtmlReporter;
import io.agentscope.core.benchmark.reporters.JsonReporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Benchmark Runner for AgentScope performance testing.
 *
 * This class coordinates the execution of performance benchmarks across all core components
 * including Agent, Model, Tool, Memory, and Pipeline.
 */
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final BenchmarkConfig config;
    private final MetricsCollector metricsCollector;
    private final List<String> executedScenarios;

    public BenchmarkRunner() {
        this.config = new BenchmarkConfig();
        this.metricsCollector = new MetricsCollector();
        this.executedScenarios = new ArrayList<>();
    }

    public BenchmarkRunner(BenchmarkConfig config) {
        this.config = config;
        this.metricsCollector = new MetricsCollector();
        this.executedScenarios = new ArrayList<>();
    }

    /**
     * Run all benchmark scenarios
     *
     * @throws RunnerException if benchmark execution fails
     * @throws IOException if report generation fails
     */
    public void runAllBenchmarks() throws RunnerException, IOException {
        log.info("Starting AgentScope performance benchmark suite...");

        // Warmup phase
        performWarmup();

        // Execute all scenarios
        // executeScenario(AgentBenchmark.class.getSimpleName());
        // executeScenario(ModelBenchmark.class.getSimpleName());
        // executeScenario(ToolBenchmark.class.getSimpleName());
        // executeScenario(MemoryBenchmark.class.getSimpleName());
        // executeScenario(PipelineBenchmark.class.getSimpleName());

        // Generate reports
        generateReports();

        log.info("Benchmark execution completed. {} scenarios executed.", executedScenarios.size());
    }

    /**
     * Run specific benchmark scenario
     *
     * @param scenarioName the name of the scenario to run
     * @throws RunnerException if benchmark execution fails
     */
    public void runBenchmark(String scenarioName) throws RunnerException {
        log.info("Running benchmark scenario: {}", scenarioName);
        executeScenario(scenarioName);
    }

    /**
     * Perform warmup before actual benchmark execution
     */
    private void performWarmup() {
        log.info("Performing warmup phase...");
        // TODO: Implement warmup logic
        try {
            Thread.sleep(1000); // Placeholder
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Warmup phase completed.");
    }

    /**
     * Execute a specific benchmark scenario using JMH
     *
     * @param scenarioName the name of the scenario to execute
     * @throws RunnerException if benchmark execution fails
     */
    private void executeScenario(String scenarioName) throws RunnerException {
        log.info("Executing scenario: {}", scenarioName);

        Options opt =
                new OptionsBuilder()
                        .include(".*" + scenarioName + ".*")
                        .warmupIterations(config.getWarmupIterations())
                        .warmupTime(TimeValue.seconds(config.getWarmupTimeSeconds()))
                        .measurementIterations(config.getMeasurementIterations())
                        .measurementTime(TimeValue.seconds(config.getMeasurementTimeSeconds()))
                        .threads(config.getThreadCount())
                        .forks(config.getForkCount())
                        .shouldDoGC(true)
                        .shouldFailOnError(true)
                        .build();

        new Runner(opt).run();
        executedScenarios.add(scenarioName);

        log.info("Scenario {} completed.", scenarioName);
    }

    /**
     * Generate benchmark reports in multiple formats
     *
     * @throws IOException if report generation fails
     */
    private void generateReports() throws IOException {
        log.info("Generating benchmark reports...");

        String timestamp =
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path reportDir = Paths.get("target", "benchmark-reports", timestamp);
        Files.createDirectories(reportDir);

        // Generate console report
        ConsoleReporter consoleReporter = new ConsoleReporter(metricsCollector);
        consoleReporter.generateReport();

        // Generate JSON report
        JsonReporter jsonReporter = new JsonReporter(metricsCollector);
        Path jsonReportPath = reportDir.resolve("benchmark-report.json");
        jsonReporter.generateReport(jsonReportPath.toString());

        // Generate HTML report
        HtmlReporter htmlReporter = new HtmlReporter(metricsCollector);
        Path htmlReportPath = reportDir.resolve("benchmark-report.html");
        htmlReporter.generateReport(htmlReportPath.toString());

        log.info("Reports generated at: {}", reportDir.toAbsolutePath());
    }

    /**
     * Get the metrics collector instance
     *
     * @return the metrics collector
     */
    public MetricsCollector getMetricsCollector() {
        return metricsCollector;
    }

    /**
     * Get the list of executed scenarios
     *
     * @return list of executed scenario names
     */
    public List<String> getExecutedScenarios() {
        return new ArrayList<>(executedScenarios);
    }

    /**
     * Main entry point for running benchmarks
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        try {
            BenchmarkRunner runner = new BenchmarkRunner();
            runner.runAllBenchmarks();
        } catch (Exception e) {
            log.error("Benchmark execution failed", e);
            System.exit(1);
        }
    }
}
