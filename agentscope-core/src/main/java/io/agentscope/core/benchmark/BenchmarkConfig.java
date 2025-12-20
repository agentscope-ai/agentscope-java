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

/**
 * Configuration class for benchmark execution parameters.
 */
public class BenchmarkConfig {

    // Default values
    private static final int DEFAULT_WARMUP_ITERATIONS = 3;
    private static final int DEFAULT_WARMUP_TIME_SECONDS = 10;
    private static final int DEFAULT_MEASUREMENT_ITERATIONS = 5;
    private static final int DEFAULT_MEASUREMENT_TIME_SECONDS = 10;
    private static final int DEFAULT_THREAD_COUNT = 1;
    private static final int DEFAULT_FORK_COUNT = 1;
    private static final boolean DEFAULT_ENABLE_PROFILING = false;

    private int warmupIterations = DEFAULT_WARMUP_ITERATIONS;
    private int warmupTimeSeconds = DEFAULT_WARMUP_TIME_SECONDS;
    private int measurementIterations = DEFAULT_MEASUREMENT_ITERATIONS;
    private int measurementTimeSeconds = DEFAULT_MEASUREMENT_TIME_SECONDS;
    private int threadCount = DEFAULT_THREAD_COUNT;
    private int forkCount = DEFAULT_FORK_COUNT;
    private boolean enableProfiling = DEFAULT_ENABLE_PROFILING;

    public BenchmarkConfig() {
        // Default constructor
    }

    // Getters and setters

    public int getWarmupIterations() {
        return warmupIterations;
    }

    public void setWarmupIterations(int warmupIterations) {
        this.warmupIterations = warmupIterations;
    }

    public int getWarmupTimeSeconds() {
        return warmupTimeSeconds;
    }

    public void setWarmupTimeSeconds(int warmupTimeSeconds) {
        this.warmupTimeSeconds = warmupTimeSeconds;
    }

    public int getMeasurementIterations() {
        return measurementIterations;
    }

    public void setMeasurementIterations(int measurementIterations) {
        this.measurementIterations = measurementIterations;
    }

    public int getMeasurementTimeSeconds() {
        return measurementTimeSeconds;
    }

    public void setMeasurementTimeSeconds(int measurementTimeSeconds) {
        this.measurementTimeSeconds = measurementTimeSeconds;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    public int getForkCount() {
        return forkCount;
    }

    public void setForkCount(int forkCount) {
        this.forkCount = forkCount;
    }

    public boolean isEnableProfiling() {
        return enableProfiling;
    }

    public void setEnableProfiling(boolean enableProfiling) {
        this.enableProfiling = enableProfiling;
    }

    /**
     * Create a default configuration
     *
     * @return a new BenchmarkConfig instance with default values
     */
    public static BenchmarkConfig createDefault() {
        return new BenchmarkConfig();
    }

    /**
     * Create a configuration for quick testing
     *
     * @return a new BenchmarkConfig instance with reduced iterations for quick testing
     */
    public static BenchmarkConfig createQuickTest() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setWarmupIterations(1);
        config.setWarmupTimeSeconds(2);
        config.setMeasurementIterations(2);
        config.setMeasurementTimeSeconds(3);
        return config;
    }

    /**
     * Create a configuration for thorough testing
     *
     * @return a new BenchmarkConfig instance with increased iterations for thorough testing
     */
    public static BenchmarkConfig createThoroughTest() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setWarmupIterations(5);
        config.setWarmupTimeSeconds(15);
        config.setMeasurementIterations(10);
        config.setMeasurementTimeSeconds(15);
        config.setForkCount(2);
        return config;
    }
}
