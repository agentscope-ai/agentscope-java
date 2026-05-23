/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.claw.app.usage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * In-memory usage event store. Records individual turn events (one per user message) and provides
 * hourly/daily aggregations for trend charts.
 *
 * <p>Data is <em>not</em> persisted across restarts. This is intentional for the current phase;
 * the store is designed to be replaceable with a durable implementation later.
 */
@Component
public class UsageStore {

    /** Maximum number of raw events to retain in memory (rolling window). */
    private static final int MAX_EVENTS = 50_000;

    private final CopyOnWriteArrayList<UsageEvent> events = new CopyOnWriteArrayList<>();

    /** Records a single turn completion. */
    public void record(String userId, String agentId, long durationMs) {
        events.add(new UsageEvent(System.currentTimeMillis(), userId, agentId, durationMs));
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /** Returns all raw events (newest-first) up to {@code limit}. */
    public List<UsageEvent> recentEvents(int limit) {
        List<UsageEvent> copy = new ArrayList<>(events);
        Collections.reverse(copy);
        return copy.stream().limit(limit).toList();
    }

    /**
     * Returns hourly turn counts for the past {@code hours} hours.
     *
     * @param hours number of hours of history to include (max 168 = 7 days)
     */
    public List<BucketCount> hourlyTurns(int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        // Pre-fill all hours with 0
        for (int i = 0; i < h; i++) {
            long bucketMs = startMs + (long) i * 3_600_000L;
            buckets.put(truncateHour(bucketMs), 0);
        }
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateHour(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * Returns daily turn counts for the past {@code days} days.
     *
     * @param days number of days of history to include (max 90)
     */
    public List<BucketCount> dailyTurns(int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;

        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            long bucketMs = startMs + (long) i * 86_400_000L;
            buckets.put(truncateDay(bucketMs), 0);
        }
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs) continue;
            long bucket = truncateDay(e.timestampMs());
            buckets.merge(bucket, 1, Integer::sum);
        }

        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /** Returns aggregate totals for a specific user only. */
    public UsageSummary summaryForUser(String userId) {
        List<UsageEvent> mine = events.stream().filter(e -> userId.equals(e.userId())).toList();
        long totalTurns = mine.size();
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns = mine.stream().filter(e -> truncateDay(e.timestampMs()) == today).count();
        long avgDurationMs =
                mine.isEmpty()
                        ? 0L
                        : (long)
                                mine.stream().mapToLong(UsageEvent::durationMs).average().orElse(0);
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, 1L);
    }

    /** Returns hourly turn counts for the past {@code hours} hours, scoped to one user. */
    public List<BucketCount> hourlyTurnsForUser(String userId, int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) h * 3_600_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < h; i++) {
            buckets.put(truncateHour(startMs + (long) i * 3_600_000L), 0);
        }
        for (UsageEvent e : events) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateHour(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelHour(en.getKey()), en.getValue()))
                .toList();
    }

    /** Returns daily turn counts for the past {@code days} days, scoped to one user. */
    public List<BucketCount> dailyTurnsForUser(String userId, int days) {
        int d = Math.max(1, Math.min(days, 90));
        long nowMs = System.currentTimeMillis();
        long startMs = nowMs - (long) d * 86_400_000L;
        Map<Long, Integer> buckets = new TreeMap<>();
        for (int i = 0; i < d; i++) {
            buckets.put(truncateDay(startMs + (long) i * 86_400_000L), 0);
        }
        for (UsageEvent e : events) {
            if (!userId.equals(e.userId()) || e.timestampMs() < startMs) continue;
            buckets.merge(truncateDay(e.timestampMs()), 1, Integer::sum);
        }
        return buckets.entrySet().stream()
                .map(en -> new BucketCount(en.getKey(), labelDay(en.getKey()), en.getValue()))
                .toList();
    }

    /**
     * Returns top-N users by turn count over the past {@code days} days.
     * Used by admin usage dashboard.
     */
    public List<GroupCount> topUsersByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs || e.userId() == null) continue;
            counts.merge(e.userId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /**
     * Returns top-N agents by turn count over the past {@code days} days.
     * Used by admin usage dashboard.
     */
    public List<GroupCount> topAgentsByTurns(int days, int topN) {
        long startMs = System.currentTimeMillis() - (long) days * 86_400_000L;
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.timestampMs() < startMs || e.agentId() == null) continue;
            counts.merge(e.agentId(), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(topN)
                .map(en -> new GroupCount(en.getKey(), en.getValue()))
                .toList();
    }

    /**
     * Per-user rollup with last-seen timestamp and total turns over the past {@code days}. Used
     * by the admin "Users" page to render an activity overview without N+1 calls.
     */
    public List<UserRollup> usersRollup(int days) {
        long startMs = System.currentTimeMillis() - (long) Math.max(1, days) * 86_400_000L;
        java.util.Map<String, long[]> agg = new java.util.HashMap<>();
        // [0]=count, [1]=lastSeenMs, [2]=durationSumMs
        for (UsageEvent e : events) {
            if (e.userId() == null || e.userId().isBlank()) continue;
            if (e.timestampMs() < startMs) continue;
            long[] cell = agg.computeIfAbsent(e.userId(), k -> new long[3]);
            cell[0]++;
            if (e.timestampMs() > cell[1]) cell[1] = e.timestampMs();
            cell[2] += e.durationMs();
        }
        return agg.entrySet().stream()
                .map(
                        en -> {
                            long[] c = en.getValue();
                            long avg = c[0] == 0 ? 0 : c[2] / c[0];
                            return new UserRollup(en.getKey(), c[0], c[1], avg);
                        })
                .sorted(Comparator.comparingLong(UserRollup::lastSeenMs).reversed())
                .toList();
    }

    /**
     * Per-agent rollup with last-used timestamp and total turns over the past {@code days}.
     */
    public List<AgentRollup> agentsRollup(int days) {
        long startMs = System.currentTimeMillis() - (long) Math.max(1, days) * 86_400_000L;
        java.util.Map<String, long[]> agg = new java.util.HashMap<>();
        // [0]=count, [1]=lastUsedMs, [2]=durationSumMs, [3]=uniqueUserBitset-not-applicable
        java.util.Map<String, java.util.Set<String>> usersByAgent = new java.util.HashMap<>();
        for (UsageEvent e : events) {
            if (e.agentId() == null || e.agentId().isBlank()) continue;
            if (e.timestampMs() < startMs) continue;
            long[] cell = agg.computeIfAbsent(e.agentId(), k -> new long[3]);
            cell[0]++;
            if (e.timestampMs() > cell[1]) cell[1] = e.timestampMs();
            cell[2] += e.durationMs();
            if (e.userId() != null) {
                usersByAgent
                        .computeIfAbsent(e.agentId(), k -> new java.util.HashSet<>())
                        .add(e.userId());
            }
        }
        return agg.entrySet().stream()
                .map(
                        en -> {
                            long[] c = en.getValue();
                            long avg = c[0] == 0 ? 0 : c[2] / c[0];
                            int uniq =
                                    usersByAgent
                                            .getOrDefault(en.getKey(), java.util.Set.of())
                                            .size();
                            return new AgentRollup(en.getKey(), c[0], c[1], avg, uniq);
                        })
                .sorted(Comparator.comparingLong(AgentRollup::lastUsedMs).reversed())
                .toList();
    }

    /** Returns aggregate totals. */
    public UsageSummary summary() {
        long totalTurns = events.size();
        long today = truncateDay(System.currentTimeMillis());
        long todayTurns =
                events.stream().filter(e -> truncateDay(e.timestampMs()) == today).count();
        long avgDurationMs =
                events.isEmpty()
                        ? 0L
                        : (long)
                                events.stream()
                                        .mapToLong(UsageEvent::durationMs)
                                        .average()
                                        .orElse(0);
        long uniqueUsers =
                events.stream()
                        .map(UsageEvent::userId)
                        .filter(u -> u != null && !u.isBlank())
                        .distinct()
                        .count();
        return new UsageSummary(totalTurns, todayTurns, avgDurationMs, uniqueUsers);
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private static long truncateHour(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.HOURS)
                .toInstant()
                .toEpochMilli();
    }

    private static long truncateDay(long epochMs) {
        return Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toEpochMilli();
    }

    private static String labelHour(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d:%02d", zdt.getHour(), 0);
    }

    private static String labelDay(long epochMs) {
        ZonedDateTime zdt = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault());
        return String.format("%02d-%02d", zdt.getMonthValue(), zdt.getDayOfMonth());
    }

    // -----------------------------------------------------------------
    //  DTO types
    // -----------------------------------------------------------------

    public record UsageEvent(long timestampMs, String userId, String agentId, long durationMs) {}

    public record BucketCount(long epochMs, String label, int count) {}

    public record GroupCount(String key, int count) {}

    public record UsageSummary(
            long totalTurns, long todayTurns, long avgDurationMs, long uniqueUsers) {}

    public record UserRollup(String userId, long turns, long lastSeenMs, long avgDurationMs) {}

    public record AgentRollup(
            String agentId, long turns, long lastUsedMs, long avgDurationMs, int uniqueUsers) {}
}
