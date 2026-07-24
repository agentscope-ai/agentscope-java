/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.a2a.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.server.hitl.HitlCancelRequest;
import io.agentscope.core.a2a.server.hitl.HitlClaimRequest;
import io.agentscope.core.a2a.server.hitl.HitlDurabilityCapability;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.a2a.server.hitl.HitlResumeRejectedException;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisHitlResumeCoordinatorTest {

    private static RedisServerSupport redis;

    @BeforeAll
    static void startRedis() throws Exception {
        redis = RedisServerSupport.start();
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.close();
            assertThat(redis.processAlive()).isFalse();
        }
    }

    @Test
    void validatesWithoutConsumptionAndAnotherReplicaClaimsExactlyOnce() throws Exception {
        String namespace = "a2a:test:coordinator:claim:";
        RedisHitlResumeCoordinator replicaA = coordinator(namespace);
        RedisHitlResumeCoordinator replicaB = coordinator(namespace);
        HitlHandoffRecord opened = open(replicaA, "secret-cross-replica", Duration.ofMinutes(5));
        HitlClaimRequest claim = claim(opened, "secret-cross-replica");

        HitlHandoffRecord validated = replicaB.validateClaim(claim);
        assertThat(validated.status()).isEqualTo(HitlHandoffStatus.OPEN);
        assertThat(replicaA.hasOpenHandoff(opened.executionKey())).isTrue();

        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> attemptClaim(replicaA, claim, start));
            var second = pool.submit(() -> attemptClaim(replicaB, claim, start));
            start.countDown();
            List<Boolean> results =
                    List.of(
                            first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                            second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(replicaA.get(opened.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.CLAIMED);
        assertThat(replicaA.hasOpenHandoff(opened.executionKey())).isFalse();
        assertThat(replicaA.durabilityCapability()).isEqualTo(HitlDurabilityCapability.DURABLE);
        assertThatThrownBy(() -> replicaB.claim(claim))
                .isInstanceOf(HitlResumeRejectedException.class);

        assertNamespaceContainsNoSecret(namespace, "secret-cross-replica");
    }

    @Test
    void wrongCoordinatesTokenAndFingerprintLeaveOpenUnchanged() {
        String namespace = "a2a:test:coordinator:wrong:";
        RedisHitlResumeCoordinator coordinator = coordinator(namespace);
        HitlHandoffRecord opened = open(coordinator, "correct-token", Duration.ofMinutes(5));

        assertThatThrownBy(
                        () ->
                                coordinator.claim(
                                        new HitlClaimRequest(
                                                "wrong-task",
                                                opened.contextId(),
                                                opened.handoffId(),
                                                "correct-token")))
                .isInstanceOf(HitlResumeRejectedException.class);
        assertThatThrownBy(
                        () ->
                                coordinator.claim(
                                        new HitlClaimRequest(
                                                opened.taskId(),
                                                "wrong-context",
                                                opened.handoffId(),
                                                "correct-token")))
                .isInstanceOf(HitlResumeRejectedException.class);
        assertThatThrownBy(() -> coordinator.claim(claim(opened, "wrong-token")))
                .isInstanceOf(HitlResumeRejectedException.class);

        redis.client()
                .getMap(coordinator.recordKey(opened.handoffId()), StringCodec.INSTANCE)
                .put("pendingFingerprint", "tampered-fingerprint");
        assertThatThrownBy(() -> coordinator.claim(claim(opened, "correct-token")))
                .isInstanceOf(HitlResumeRejectedException.class);
        assertThat(coordinator.get(opened.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.OPEN);
    }

    @Test
    void cancelExpiryAndRepauseHaveAtomicLifecycle() throws Exception {
        RedisHitlResumeCoordinator coordinator = coordinator("a2a:test:coordinator:lifecycle:");
        HitlHandoffRecord canceled = open(coordinator, "cancel-token", Duration.ofMinutes(5));
        coordinator.cancel(
                new HitlCancelRequest(
                        canceled.taskId(),
                        canceled.contextId(),
                        canceled.handoffId(),
                        "cancel-token"));
        assertThat(coordinator.get(canceled.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.CANCELED);
        assertThat(coordinator.hasOpenHandoff(canceled.executionKey())).isFalse();

        HitlHandoffRecord expiring = open(coordinator, "expiring-token", Duration.ofMillis(80));
        Thread.sleep(130);
        assertThatThrownBy(() -> coordinator.claim(claim(expiring, "expiring-token")))
                .isInstanceOf(HitlResumeRejectedException.class);
        assertThat(coordinator.get(expiring.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.EXPIRED);

        HitlHandoffRecord first = open(coordinator, "first-token", Duration.ofMinutes(5));
        coordinator.claim(claim(first, "first-token"));
        HitlHandoffRecord second =
                coordinator.open(
                        new HitlOpenRequest(
                                "task-next",
                                first.contextId(),
                                first.executionKey(),
                                A2aHandoffType.USER_CONFIRM,
                                List.of(new ToolUseBlock("tool-next", "confirm", Map.of("v", 2))),
                                "second-token",
                                Duration.ofMinutes(5),
                                first.handoffId()));
        assertThat(coordinator.get(first.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.SUPERSEDED);
        assertThat(second.status()).isEqualTo(HitlHandoffStatus.OPEN);
    }

    @Test
    void terminalTransitionOfOldRecordCannotDeleteNewSessionOwner() throws Exception {
        String namespace = "a2a:test:coordinator:session-owner-transition:";
        RedisHitlResumeCoordinator coordinator = coordinator(namespace);
        HitlExecutionKey executionKey = new HitlExecutionKey("user", "agent", "shared-context");
        HitlHandoffRecord old =
                openWithKey(
                        coordinator,
                        executionKey,
                        "old-task",
                        "old-tool",
                        "old-token",
                        Duration.ofMillis(80));
        awaitMissing(coordinator.sessionKey(executionKey));
        HitlHandoffRecord replacement =
                openWithKey(
                        coordinator,
                        executionKey,
                        "new-task",
                        "new-tool",
                        "new-token",
                        Duration.ofMinutes(5));

        coordinator.transition(old.handoffId(), HitlHandoffStatus.OPEN, HitlHandoffStatus.EXPIRED);

        assertThat(coordinator.hasOpenHandoff(executionKey)).isTrue();
        assertThat(coordinator.get(replacement.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.OPEN);
    }

    @Test
    void claimExpiryRaceCannotDeleteNewSessionOwner() {
        String namespace = "a2a:test:coordinator:session-owner-admit:";
        Instant base = Instant.parse("2026-07-18T00:00:00Z");
        ArmableClock clock = new ArmableClock(base);
        RedisHitlResumeCoordinator racing =
                new RedisHitlResumeCoordinator(
                        redis.client(), namespace, Duration.ofDays(30), clock);
        RedisHitlResumeCoordinator replacementCoordinator = coordinator(namespace);
        HitlExecutionKey executionKey = new HitlExecutionKey("user", "agent", "shared-context");
        HitlHandoffRecord old =
                openWithKey(
                        racing,
                        executionKey,
                        "old-task",
                        "old-tool",
                        "old-token",
                        Duration.ofMinutes(5));
        AtomicReference<HitlHandoffRecord> replacement = new AtomicReference<>();
        clock.armSecondRead(
                base.plus(Duration.ofMinutes(10)),
                () -> {
                    redis.client()
                            .getBucket(racing.sessionKey(executionKey), StringCodec.INSTANCE)
                            .delete();
                    replacement.set(
                            openWithKey(
                                    replacementCoordinator,
                                    executionKey,
                                    "new-task",
                                    "new-tool",
                                    "new-token",
                                    Duration.ofMinutes(5)));
                });

        assertThatThrownBy(() -> racing.claim(claim(old, "old-token")))
                .isInstanceOf(HitlResumeRejectedException.class);

        assertThat(replacement).doesNotHaveValue(null);
        assertThat(replacementCoordinator.hasOpenHandoff(executionKey)).isTrue();
        assertThat(replacementCoordinator.get(replacement.get().handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.OPEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleHasOpenObservationRechecksNewSessionOwnerAfterCompareDeleteLosesRace() {
        String namespace = "a2a:test:coordinator:session-owner-has-open:";
        RedisHitlResumeCoordinator stateCoordinator = coordinator(namespace);
        HitlExecutionKey executionKey = new HitlExecutionKey("user", "agent", "shared-context");
        HitlHandoffRecord old =
                openWithKey(
                        stateCoordinator,
                        executionKey,
                        "old-task",
                        "old-tool",
                        "old-token",
                        Duration.ofMinutes(5));
        redis.client()
                .getMap(stateCoordinator.recordKey(old.handoffId()), StringCodec.INSTANCE)
                .put("status", HitlHandoffStatus.CANCELED.name());

        String sessionKey = stateCoordinator.sessionKey(executionKey);
        RBucket<String> realBucket = redis.client().getBucket(sessionKey, StringCodec.INSTANCE);
        RBucket<String> observedBucket = mock(RBucket.class, delegatesTo(realBucket));
        RedissonClient observingClient = mock(RedissonClient.class, delegatesTo(redis.client()));
        when(observingClient.<String>getBucket(sessionKey, StringCodec.INSTANCE))
                .thenReturn(observedBucket);
        AtomicBoolean swapped = new AtomicBoolean();
        AtomicReference<HitlHandoffRecord> replacement = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            String observed = realBucket.get();
                            if (swapped.compareAndSet(false, true)) {
                                realBucket.delete();
                                replacement.set(
                                        openWithKey(
                                                stateCoordinator,
                                                executionKey,
                                                "new-task",
                                                "new-tool",
                                                "new-token",
                                                Duration.ofMinutes(5)));
                            }
                            return observed;
                        })
                .when(observedBucket)
                .get();
        RedisHitlResumeCoordinator observer =
                new RedisHitlResumeCoordinator(observingClient, namespace, Duration.ofDays(30));

        assertThat(observer.hasOpenHandoff(executionKey)).isTrue();
        assertThat(replacement).doesNotHaveValue(null);
        assertThat(stateCoordinator.hasOpenHandoff(executionKey)).isTrue();
    }

    @Test
    void everyMultiKeyFamilyUsesTheSameExplicitClusterHashTag() {
        RedisHitlResumeCoordinator coordinator = coordinator("a2a:test:coordinator:slots:");
        HitlExecutionKey key = new HitlExecutionKey("u", "agent", "ctx");
        String expected = RedisNamespaceAssertions.hashTag(coordinator.recordKey("handoff"));
        assertThat(RedisNamespaceAssertions.hashTag(coordinator.sessionKey(key)))
                .isEqualTo(expected);
        assertThat(RedisNamespaceAssertions.hashTag(coordinator.leaseKey(key))).isEqualTo(expected);
        assertThat(RedisNamespaceAssertions.hashTag(coordinator.claimedIndexKey()))
                .isEqualTo(expected);
    }

    private static RedisHitlResumeCoordinator coordinator(String namespace) {
        return new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(30));
    }

    private static HitlHandoffRecord open(
            RedisHitlResumeCoordinator coordinator, String token, Duration ttl) {
        String suffix = Integer.toHexString(token.hashCode());
        String context = "ctx-" + suffix;
        return coordinator.open(
                new HitlOpenRequest(
                        "task-" + suffix,
                        context,
                        new HitlExecutionKey("user", "agent", context),
                        A2aHandoffType.USER_CONFIRM,
                        List.of(new ToolUseBlock("tool-" + suffix, "confirm", Map.of("v", 1))),
                        token,
                        ttl,
                        null));
    }

    private static HitlHandoffRecord openWithKey(
            RedisHitlResumeCoordinator coordinator,
            HitlExecutionKey executionKey,
            String taskId,
            String toolId,
            String token,
            Duration ttl) {
        return coordinator.open(
                new HitlOpenRequest(
                        taskId,
                        executionKey.contextId(),
                        executionKey,
                        A2aHandoffType.USER_CONFIRM,
                        List.of(new ToolUseBlock(toolId, "confirm", Map.of("v", 1))),
                        token,
                        ttl,
                        null));
    }

    private static void awaitMissing(String key) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (redis.client().getKeys().countExists(key) != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(redis.client().getKeys().countExists(key)).isZero();
    }

    private static HitlClaimRequest claim(HitlHandoffRecord record, String token) {
        return new HitlClaimRequest(record.taskId(), record.contextId(), record.handoffId(), token);
    }

    private static boolean attemptClaim(
            RedisHitlResumeCoordinator coordinator, HitlClaimRequest claim, CountDownLatch start) {
        try {
            start.await();
            coordinator.claim(claim);
            return true;
        } catch (HitlResumeRejectedException expected) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void assertNamespaceContainsNoSecret(String namespace, String secret) {
        RedisNamespaceAssertions.containsNone(
                redis.client(), namespace, secret, "tenant", "authenticated", "AgentState");
    }

    private static final class ArmableClock extends Clock {
        private final Instant before;
        private final AtomicInteger reads = new AtomicInteger();
        private volatile Instant after;
        private volatile Runnable onSecondRead;

        private ArmableClock(Instant before) {
            this.before = before;
        }

        private void armSecondRead(Instant after, Runnable action) {
            this.after = after;
            this.onSecondRead = action;
            reads.set(0);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (onSecondRead != null && reads.incrementAndGet() == 2) {
                Runnable action = onSecondRead;
                onSecondRead = null;
                action.run();
                return after;
            }
            return before;
        }
    }
}
