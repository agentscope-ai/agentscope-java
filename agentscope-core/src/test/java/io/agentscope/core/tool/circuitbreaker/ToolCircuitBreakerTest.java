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
package io.agentscope.core.tool.circuitbreaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** State-machine, backoff and supervision-scope behaviour of {@link ToolCircuitBreaker}. */
class ToolCircuitBreakerTest {

    private static final String WEATHER = "query_weather";
    private static final String DATABASE = "query_database";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    // ==================== Supervision scope ====================

    @Test
    void breakerIsInertUntilToolsAreNamed() {
        ToolCircuitBreaker breaker = breaker(ToolCircuitBreakerConfig.builder());

        for (int i = 0; i < 10; i++) {
            breaker.recordFailure(WEATHER);
        }

        assertFalse(breaker.supervises(WEATHER));
        assertFalse(breaker.isWithheld(WEATHER));
        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
    }

    @Test
    void unnamedToolIsNotSupervisedWhenAnotherToolIs() {
        ToolCircuitBreaker breaker =
                breaker(ToolCircuitBreakerConfig.builder().monitorTools(WEATHER));

        for (int i = 0; i < 10; i++) {
            breaker.recordFailure(DATABASE);
        }

        assertFalse(breaker.isWithheld(DATABASE));
        assertTrue(breaker.supervises(WEATHER));
    }

    @Test
    void exclusionOverridesMonitorAllTools() {
        ToolCircuitBreaker breaker =
                breaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorAllTools(true)
                                .excludeTools(DATABASE));

        assertTrue(breaker.supervises(WEATHER));
        assertFalse(breaker.supervises(DATABASE));
    }

    @Test
    void exclusionOverridesExplicitMonitoring() {
        ToolCircuitBreaker breaker =
                breaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorTools(WEATHER)
                                .excludeTools(WEATHER));

        assertFalse(breaker.supervises(WEATHER));
    }

    @Test
    void disabledConfigSupervisesNothing() {
        ToolCircuitBreaker breaker =
                breaker(
                        ToolCircuitBreakerConfig.builder()
                                .enabled(false)
                                .monitorTools(WEATHER)
                                .failureThreshold(1));

        breaker.recordFailure(WEATHER);

        assertFalse(breaker.supervises(WEATHER));
        assertFalse(breaker.isWithheld(WEATHER));
    }

    @Test
    void nullToolNameIsNeverSupervised() {
        ToolCircuitBreaker breaker =
                breaker(ToolCircuitBreakerConfig.builder().monitorAllTools(true));

        assertFalse(breaker.supervises(null));
        assertFalse(breaker.isWithheld(null));
    }

    // ==================== CLOSED -> OPEN ====================

    @Test
    void tripsOnlyOnceThresholdConsecutiveFailuresAreReached() {
        ToolCircuitBreaker breaker = weatherBreaker(3);

        breaker.recordFailure(WEATHER);
        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
        assertFalse(breaker.isWithheld(WEATHER));

        breaker.recordFailure(WEATHER);
        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
        assertFalse(breaker.isWithheld(WEATHER));

        breaker.recordFailure(WEATHER);
        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));
        assertTrue(breaker.isWithheld(WEATHER));
    }

    @Test
    void successBreaksTheStreakSoScatteredFailuresNeverTrip() {
        ToolCircuitBreaker breaker = weatherBreaker(3);

        breaker.recordFailure(WEATHER);
        breaker.recordFailure(WEATHER);
        breaker.recordSuccess(WEATHER);
        breaker.recordFailure(WEATHER);
        breaker.recordFailure(WEATHER);

        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
    }

    @Test
    void successDuringThresholdFailurePreventsAStaleOpen() throws Exception {
        ThresholdReturnBarrierStore store = new ThresholdReturnBarrierStore();
        ToolCircuitBreaker breaker =
                new ToolCircuitBreaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorTools(WEATHER)
                                .failureThreshold(3)
                                .build(),
                        store,
                        clock);
        breaker.recordFailure(WEATHER);
        breaker.recordFailure(WEATHER);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> thresholdFailure = pool.submit(() -> breaker.recordFailure(WEATHER));
        try {
            assertTrue(store.thresholdRecorded.await(5, TimeUnit.SECONDS));

            breaker.recordSuccess(WEATHER);
            store.allowThresholdResult.countDown();
            thresholdFailure.get(5, TimeUnit.SECONDS);

            assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
            assertEquals(1L, store.snapshot(WEATHER).failureCount());
        } finally {
            store.allowThresholdResult.countDown();
            pool.shutdownNow();
        }
    }

    // ==================== OPEN -> HALF_OPEN -> CLOSED ====================

    @Test
    void toolStaysWithheldForTheWholeCooldown() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);

        clock.advance(Duration.ofSeconds(59));

        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));
        assertTrue(breaker.isWithheld(WEATHER));
    }

    @Test
    void cooldownElapsingOffersTheToolAgainAsAProbe() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);

        clock.advance(Duration.ofSeconds(60));

        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
        assertFalse(breaker.isWithheld(WEATHER));
    }

    @Test
    void successfulProbeClosesCircuitAndForgetsBackoff() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));

        breaker.recordSuccess(WEATHER);

        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));

        // Backoff was discarded, so the next incident starts from the initial cooldown again.
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
    }

    @Test
    void failedProbeReopensCircuitWithTheNextLongerCooldown() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));

        breaker.recordFailure(WEATHER);

        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));

        // Second generation waits 120s, so the original 60s is no longer enough.
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));

        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
    }

    @Test
    void failureWhileWithheldDoesNotDeepenBackoff() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);

        // A call the model decided on in the same turn the circuit tripped still lands here.
        breaker.recordFailure(WEATHER);
        breaker.recordFailure(WEATHER);

        // Cooldown must still be the first generation's 60s, not 120s or 240s.
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
    }

    @Test
    void successWhileWithheldDoesNotCloseCircuitEarly() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);

        breaker.recordSuccess(WEATHER);

        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));
        assertTrue(breaker.isWithheld(WEATHER));
    }

    @Test
    void resetClearsStateAndAccumulatedBackoff() {
        ToolCircuitBreaker breaker = weatherBreaker(1);
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        breaker.recordFailure(WEATHER);
        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));

        breaker.reset(WEATHER);

        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));

        // Backoff restarted: one trip then 60s is enough to probe again.
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
    }

    // ==================== Exponential backoff ====================

    @Test
    void cooldownDoublesPerGenerationAndIsCapped() {
        ToolCircuitBreaker breaker = weatherBreaker(1);

        assertEquals(Duration.ZERO, breaker.cooldownFor(0));
        assertEquals(Duration.ofSeconds(60), breaker.cooldownFor(1));
        assertEquals(Duration.ofSeconds(120), breaker.cooldownFor(2));
        assertEquals(Duration.ofSeconds(240), breaker.cooldownFor(3));
        assertEquals(Duration.ofSeconds(480), breaker.cooldownFor(4));
        assertEquals(Duration.ofSeconds(600), breaker.cooldownFor(5));
        assertEquals(Duration.ofSeconds(600), breaker.cooldownFor(6));
    }

    @Test
    void hugeGenerationClampsToMaxInsteadOfOverflowing() {
        ToolCircuitBreaker breaker = weatherBreaker(1);

        assertEquals(Duration.ofSeconds(600), breaker.cooldownFor(1_000L));
        assertEquals(Duration.ofSeconds(600), breaker.cooldownFor(Long.MAX_VALUE));
    }

    @Test
    void multiplierOfOneGivesAFixedCooldown() {
        ToolCircuitBreaker breaker =
                breaker(
                        ToolCircuitBreakerConfig.builder()
                                .monitorTools(WEATHER)
                                .failureThreshold(1)
                                .backoffMultiplier(1.0)
                                .initialCooldown(Duration.ofSeconds(30))
                                .maxCooldown(Duration.ofSeconds(600)));

        assertEquals(Duration.ofSeconds(30), breaker.cooldownFor(1));
        assertEquals(Duration.ofSeconds(30), breaker.cooldownFor(5));
    }

    // ==================== Configuration validation ====================

    @Test
    void configRejectsNonPositiveThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolCircuitBreakerConfig.builder().failureThreshold(0).build());
    }

    @Test
    void configRejectsMultiplierBelowOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolCircuitBreakerConfig.builder().backoffMultiplier(0.5).build());
    }

    @Test
    void configRejectsNonPositiveInitialCooldown() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolCircuitBreakerConfig.builder().initialCooldown(Duration.ZERO).build());
    }

    @Test
    void configRejectsInvertedCooldownBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ToolCircuitBreakerConfig.builder()
                                .initialCooldown(Duration.ofSeconds(120))
                                .maxCooldown(Duration.ofSeconds(60))
                                .build());
    }

    @Test
    void onlyOneCallerHoldsTheRecoveryProbe() {
        ToolCircuitBreaker breaker = probeBreaker();
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));

        String held = breaker.tryAcquireProbe(WEATHER).orElseThrow();

        assertTrue(breaker.tryAcquireProbe(WEATHER).isEmpty());
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
        assertFalse(held.isEmpty());
    }

    @Test
    void probeIsNotOfferedWhileTheCircuitIsStillCoolingDown() {
        ToolCircuitBreaker breaker = probeBreaker();
        breaker.recordFailure(WEATHER);

        clock.advance(Duration.ofSeconds(59));

        assertTrue(breaker.tryAcquireProbe(WEATHER).isEmpty());
    }

    @Test
    void expiredProbeIsReclaimableAndTheSupersededTokenCannotCompleteIt() {
        ToolCircuitBreaker breaker = probeBreaker();
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        String stale = breaker.tryAcquireProbe(WEATHER).orElseThrow();

        // The holder never reports an outcome; once the lease expires another turn may retry.
        clock.advance(Duration.ofSeconds(30));
        String live = breaker.tryAcquireProbe(WEATHER).orElseThrow();
        assertNotEquals(stale, live);

        // A late result from the superseded probe must neither close nor re-open the new one.
        breaker.recordSuccess(WEATHER, stale);
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
        breaker.recordFailure(WEATHER, stale);
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));

        breaker.recordSuccess(WEATHER, live);
        assertEquals(ToolCircuitState.CLOSED, breaker.state(WEATHER));
    }

    @Test
    void releasingAProbeLetsTheNextCallerRetryImmediately() {
        ToolCircuitBreaker breaker = probeBreaker();
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        String held = breaker.tryAcquireProbe(WEATHER).orElseThrow();
        assertTrue(breaker.tryAcquireProbe(WEATHER).isEmpty());

        breaker.releaseProbe(WEATHER, held);

        assertTrue(breaker.tryAcquireProbe(WEATHER).isPresent());
    }

    @Test
    void failingProbeReopensWithTheNextGenerationAndDropsTheClaim() {
        ToolCircuitBreaker breaker = probeBreaker();
        breaker.recordFailure(WEATHER);
        clock.advance(Duration.ofSeconds(60));
        String held = breaker.tryAcquireProbe(WEATHER).orElseThrow();

        breaker.recordFailure(WEATHER, held);

        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));
        // Second generation waits 120s, so the original 60s is no longer enough.
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.OPEN, breaker.state(WEATHER));
        clock.advance(Duration.ofSeconds(60));
        assertEquals(ToolCircuitState.HALF_OPEN, breaker.state(WEATHER));
        assertTrue(breaker.tryAcquireProbe(WEATHER).isPresent());
    }

    // ==================== Helpers ====================

    private ToolCircuitBreaker weatherBreaker(int threshold) {
        return breaker(
                ToolCircuitBreakerConfig.builder()
                        .monitorTools(WEATHER)
                        .failureThreshold(threshold)
                        .initialCooldown(Duration.ofSeconds(60))
                        .backoffMultiplier(2.0)
                        .maxCooldown(Duration.ofSeconds(600)));
    }

    private ToolCircuitBreaker probeBreaker() {
        return breaker(
                ToolCircuitBreakerConfig.builder()
                        .monitorTools(WEATHER)
                        .failureThreshold(1)
                        .initialCooldown(Duration.ofSeconds(60))
                        .backoffMultiplier(2.0)
                        .maxCooldown(Duration.ofSeconds(600))
                        .probeTimeout(Duration.ofSeconds(30)));
    }

    private ToolCircuitBreaker breaker(ToolCircuitBreakerConfig.Builder config) {
        return new ToolCircuitBreaker(config.build(), new InMemoryToolCircuitBreakerStore(), clock);
    }

    /**
     * Delegating store that suspends the compare-and-set which would trip the circuit, so a test can
     * interleave a success into the exact window the reviewer described.
     */
    private static final class ThresholdReturnBarrierStore implements ToolCircuitBreakerStore {

        private final InMemoryToolCircuitBreakerStore delegate =
                new InMemoryToolCircuitBreakerStore();
        private final CountDownLatch thresholdRecorded = new CountDownLatch(1);
        private final CountDownLatch allowThresholdResult = new CountDownLatch(1);

        @Override
        public ToolCircuitSnapshot snapshot(String toolName) {
            return delegate.snapshot(toolName);
        }

        @Override
        public boolean compareAndSet(
                String toolName, ToolCircuitSnapshot expected, ToolCircuitSnapshot update) {
            if (!expected.isOpen() && update.isOpen()) {
                thresholdRecorded.countDown();
                try {
                    if (!allowThresholdResult.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to commit the trip");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the trip", e);
                }
            }
            return delegate.compareAndSet(toolName, expected, update);
        }

        @Override
        public void reset(String toolName) {
            delegate.reset(toolName);
        }
    }
}
