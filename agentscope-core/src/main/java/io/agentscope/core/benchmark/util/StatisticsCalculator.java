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
package io.agentscope.core.benchmark.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Statistics calculator for benchmark results.
 * Provides methods for calculating various statistical measures from benchmark data.
 */
public class StatisticsCalculator {

    /**
     * Calculate basic statistics from a list of values
     *
     * @param values the list of values to calculate statistics for
     * @return a map containing the calculated statistics
     */
    public static Map<String, Double> calculateBasicStatistics(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        Map<String, Double> stats = new ConcurrentHashMap<>();

        // Calculate mean
        double sum = values.stream().mapToDouble(Double::doubleValue).sum();
        double mean = sum / values.size();
        stats.put("mean", mean);

        // Calculate min and max
        stats.put("min", values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));
        stats.put("max", values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0));

        // Calculate standard deviation
        double variance =
                values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum() / values.size();
        stats.put("stddev", Math.sqrt(variance));

        return stats;
    }

    /**
     * Calculate percentiles from a list of values
     *
     * @param values the list of values to calculate percentiles for
     * @param percentiles the percentiles to calculate (e.g., 50, 90, 95, 99)
     * @return a map containing the calculated percentiles
     */
    public static Map<Integer, Double> calculatePercentiles(
            List<Double> values, int... percentiles) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }

        List<Double> sortedValues = values.stream().sorted().collect(Collectors.toList());
        int size = sortedValues.size();

        Map<Integer, Double> result = new ConcurrentHashMap<>();

        for (int percentile : percentiles) {
            if (percentile < 0 || percentile > 100) {
                continue;
            }

            int index = (int) Math.ceil(percentile / 100.0 * size) - 1;
            index = Math.max(0, Math.min(index, size - 1));
            result.put(percentile, sortedValues.get(index));
        }

        return result;
    }

    /**
     * Calculate throughput statistics
     *
     * @param operationCount the number of operations performed
     * @param totalTimeMs the total time taken in milliseconds
     * @return throughput in operations per second
     */
    public static double calculateThroughput(long operationCount, double totalTimeMs) {
        if (totalTimeMs <= 0) {
            return 0.0;
        }

        return operationCount / (totalTimeMs / 1000.0);
    }
}
