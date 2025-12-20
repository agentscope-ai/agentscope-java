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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import java.util.List;
import java.util.Random;

/**
 * Utility class for benchmark operations.
 * Provides helper methods for generating test data and other common benchmark tasks.
 */
public class BenchmarkUtils {

    private static final Random random = new Random();

    /**
     * Generate a random message for benchmarking
     *
     * @param role the role of the message sender
     * @param length the approximate length of the message content
     * @return a new Msg instance with random content
     */
    public static Msg generateRandomMessage(MsgRole role, int length) {
        StringBuilder content = new StringBuilder();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ";

        for (int i = 0; i < length; i++) {
            content.append(chars.charAt(random.nextInt(chars.length())));
        }

        return Msg.builder()
                .name("BenchmarkUser")
                .role(role)
                .textContent(content.toString())
                .build();
    }

    /**
     * Generate a list of random messages
     *
     * @param count the number of messages to generate
     * @param role the role of the message sender
     * @param length the approximate length of each message content
     * @return a list of Msg instances
     */
    public static List<Msg> generateRandomMessages(int count, MsgRole role, int length) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> generateRandomMessage(role, length))
                .toList();
    }

    /**
     * Calculate the average execution time from a list of times
     *
     * @param times the list of execution times in nanoseconds
     * @return the average time in milliseconds
     */
    public static double calculateAverageTimeMs(List<Long> times) {
        if (times.isEmpty()) {
            return 0.0;
        }

        long sum = times.stream().mapToLong(Long::longValue).sum();
        return sum / (double) times.size() / 1_000_000; // Convert nanoseconds to milliseconds
    }

    /**
     * Calculate the percentile value from a list of times
     *
     * @param times the list of execution times in nanoseconds
     * @param percentile the percentile to calculate (0-100)
     * @return the percentile time in milliseconds
     */
    public static double calculatePercentileMs(List<Long> times, double percentile) {
        if (times.isEmpty()) {
            return 0.0;
        }

        List<Long> sortedTimes = times.stream().sorted().toList();
        int index = (int) Math.ceil(percentile / 100.0 * sortedTimes.size()) - 1;
        index = Math.max(0, Math.min(index, sortedTimes.size() - 1));

        return sortedTimes.get(index) / 1_000_000.0; // Convert nanoseconds to milliseconds
    }
}
