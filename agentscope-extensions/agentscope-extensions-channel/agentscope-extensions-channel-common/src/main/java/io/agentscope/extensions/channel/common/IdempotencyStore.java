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
package io.agentscope.extensions.channel.common;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded in-process implementation of {@link InboundEventDeduplicator}. Webhook providers
 * (notably WeCom) commonly retry the same message id under failure; this store de-duplicates by
 * {@code msgId}.
 *
 * <p>Internal map is bounded to {@link #maxEntries} — when full, entries are evicted to make
 * room: expired entries are dropped first, after which the victim is unspecified. Entries are
 * also lazily expired after {@link #ttlMillis}.
 *
 * <p>State lives in the JVM heap, so deduplication only holds within a single process; see {@link
 * InboundEventDeduplicator} for multi-instance deployments. This class is thread-safe.
 */
public final class IdempotencyStore implements InboundEventDeduplicator {

    private final long ttlMillis;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Long> seen = new ConcurrentHashMap<>();

    /** Default 5-minute TTL, 10k entries — sufficient for any single channel's retry burst. */
    public IdempotencyStore() {
        this(5 * 60_000L, 10_000);
    }

    public IdempotencyStore(long ttlMillis, int maxEntries) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.ttlMillis = ttlMillis;
        this.maxEntries = maxEntries;
    }

    /**
     * Records {@code key} as seen. Returns {@code true} when {@code key} has not been observed
     * within the TTL (the caller should proceed), {@code false} when it is a redelivery. An
     * accepted observation restarts the key's retention window, so after the TTL lapses only the
     * first redelivery proceeds and the rest are still dropped.
     */
    @Override
    public boolean firstSeen(String key) {
        if (key == null) {
            return true;
        }
        long now = System.currentTimeMillis();
        sweep(now);
        Long prior = seen.putIfAbsent(key, now);
        if (prior == null) {
            return true;
        }
        if (now - prior > ttlMillis) {
            // Only the CAS winner restarts the window; the loser sees a redelivery.
            return seen.replace(key, prior, now);
        }
        return false;
    }

    /** Drops any entries older than {@link #ttlMillis} and bounds size to {@link #maxEntries}. */
    private void sweep(long now) {
        if (seen.size() < maxEntries) {
            return;
        }
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> e = it.next();
            if (now - e.getValue() > ttlMillis) {
                it.remove();
            }
        }
        // Hard cap: if still over budget, evict arbitrary entries.
        while (seen.size() >= maxEntries) {
            Iterator<String> keyIt = seen.keySet().iterator();
            if (!keyIt.hasNext()) {
                break;
            }
            seen.remove(keyIt.next());
        }
    }

    /** Returns the current size; mostly for tests/observability. */
    public int size() {
        return seen.size();
    }
}
