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
package io.agentscope.harness.agent.coordination;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JVM-local {@link PeriodicGate} backed by a process-wide {@link ConcurrentHashMap} and CAS
 * updates. Suitable for single-process deployments or as the default when no distributed store
 * is configured.
 *
 * <p>The map is static so the throttle window survives across {@code HarnessAgent.Builder.build()}
 * calls — each rebuild creates new middleware instances, and an instance-level map would reset
 * to {@link Instant#EPOCH} on every request.
 */
public final class LocalPeriodicGate implements PeriodicGate {

    private static final Instant EPOCH = Instant.EPOCH;

    static final ConcurrentHashMap<String, AtomicReference<Instant>> SHARED_LAST_CLAIM_AT =
            new ConcurrentHashMap<>();

    @Override
    public boolean tryClaim(String name, Duration minGap) {
        if (name == null || name.isBlank() || minGap == null) {
            return false;
        }
        Instant now = Instant.now();
        AtomicReference<Instant> ref =
                SHARED_LAST_CLAIM_AT.computeIfAbsent(name, k -> new AtomicReference<>(EPOCH));
        Instant last = ref.get();
        if (Duration.between(last, now).compareTo(minGap) < 0) {
            return false;
        }
        return ref.compareAndSet(last, now);
    }

    /**
     * Removes entries whose last claim is older than {@code maxAge}. Bounds map size in
     * long-running services with high key churn.
     */
    public void cleanupStaleEntries(Duration maxAge) {
        if (maxAge == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        SHARED_LAST_CLAIM_AT.entrySet().removeIf(e -> e.getValue().get().isBefore(cutoff));
    }

    /** Clears all entries — for unit tests only. */
    public static void clearForTests() {
        SHARED_LAST_CLAIM_AT.clear();
    }

    /** Seeds a claim timestamp — for unit tests only. */
    public static void seedLastClaimAtForTests(String name, Instant instant) {
        SHARED_LAST_CLAIM_AT
                .computeIfAbsent(name, k -> new AtomicReference<>(EPOCH))
                .set(instant != null ? instant : EPOCH);
    }

    /** Returns whether a named slot exists — for unit tests only. */
    public static boolean hasClaimForTests(String name) {
        return SHARED_LAST_CLAIM_AT.containsKey(name);
    }
}
