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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.a2a.server.hitl.HitlClaimRequest;
import io.agentscope.core.a2a.server.hitl.HitlExecutionKey;
import io.agentscope.core.a2a.server.hitl.HitlHandoffRecord;
import io.agentscope.core.a2a.server.hitl.HitlHandoffStatus;
import io.agentscope.core.a2a.server.hitl.HitlLeaseHandle;
import io.agentscope.core.a2a.server.hitl.HitlOpenRequest;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisHitlRecoveryReconcilerTest {

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
    void recoversOnlyStaleClaimWithoutLiveLeaseAndCleansClaimedIndex() throws Exception {
        String namespace = "a2a:test:reconcile:";
        RedisHitlResumeCoordinator coordinator =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        RedisHitlSessionLease leases = new RedisHitlSessionLease(redis.client(), namespace);
        HitlExecutionKey executionKey = new HitlExecutionKey("u", "agent", "ctx");
        HitlHandoffRecord opened =
                coordinator.open(
                        new HitlOpenRequest(
                                "task",
                                "ctx",
                                executionKey,
                                A2aHandoffType.USER_CONFIRM,
                                List.of(new ToolUseBlock("tool", "confirm", Map.of())),
                                "token",
                                Duration.ofHours(1),
                                null));
        coordinator.claim(new HitlClaimRequest("task", "ctx", opened.handoffId(), "token"));
        assertThat(
                        redis.client()
                                .getScoredSortedSet(
                                        coordinator.claimedIndexKey(), StringCodec.INSTANCE)
                                .readAll())
                .containsExactly(opened.handoffId());

        try (HitlLeaseHandle ignored = leases.acquire(executionKey, Duration.ofSeconds(2))) {
            Thread.sleep(90);
            assertThat(coordinator.reconcileClaimed(Duration.ofMillis(40))).isZero();
            assertThat(coordinator.get(opened.handoffId()).orElseThrow().status())
                    .isEqualTo(HitlHandoffStatus.CLAIMED);
        }
        assertThat(coordinator.reconcileClaimed(Duration.ofMillis(40))).isEqualTo(1);
        assertThat(coordinator.get(opened.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.RECOVERY_REQUIRED);
        assertThat(
                        redis.client()
                                .getScoredSortedSet(
                                        coordinator.claimedIndexKey(), StringCodec.INSTANCE)
                                .readAll())
                .isEmpty();
        assertThat(coordinator.reconcileClaimed(Duration.ofMillis(40))).isZero();
        leases.close();
    }

    @Test
    void lifecycleIsIdempotentAndClosedReconcilerCannotRestart() {
        RedisHitlResumeCoordinator coordinator =
                new RedisHitlResumeCoordinator(
                        redis.client(), "a2a:test:reconcile:lifecycle:", Duration.ofDays(1));
        RedisHitlRecoveryReconciler reconciler =
                new RedisHitlRecoveryReconciler(
                        coordinator, Duration.ofSeconds(1), Duration.ofMillis(20));
        assertThatCode(
                        () -> {
                            reconciler.start();
                            reconciler.start();
                            reconciler.close();
                            reconciler.close();
                        })
                .doesNotThrowAnyException();
        assertThatThrownBy(reconciler::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void liveLeasesInFirstBatchDoNotStarveLaterAbandonedClaim() {
        String namespace = "a2a:test:reconcile:pagination:";
        RedisHitlResumeCoordinator coordinator =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        var claimedIndex =
                redis.client()
                        .<String>getScoredSortedSet(
                                coordinator.claimedIndexKey(), StringCodec.INSTANCE);
        List<HitlHandoffRecord> live = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            HitlHandoffRecord record = claim(coordinator, index);
            live.add(record);
            claimedIndex.add(index, record.handoffId());
            redis.client()
                    .getBucket(coordinator.leaseKey(record.executionKey()), StringCodec.INSTANCE)
                    .set("live-owner", Duration.ofMinutes(1));
        }
        HitlHandoffRecord abandoned = claim(coordinator, 256);
        claimedIndex.add(256, abandoned.handoffId());

        assertThat(coordinator.reconcileClaimed(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(coordinator.get(abandoned.handoffId()).orElseThrow().status())
                .isEqualTo(HitlHandoffStatus.RECOVERY_REQUIRED);
        assertThat(live)
                .allSatisfy(
                        record ->
                                assertThat(
                                                coordinator
                                                        .get(record.handoffId())
                                                        .orElseThrow()
                                                        .status())
                                        .isEqualTo(HitlHandoffStatus.CLAIMED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientRecordReadFailurePropagatesAndRetainsClaimedIndex() {
        String namespace = "a2a:test:reconcile:transient-read:";
        RedisHitlResumeCoordinator stateCoordinator =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        HitlHandoffRecord record = claim(stateCoordinator, 1);
        var claimedIndex =
                redis.client()
                        .<String>getScoredSortedSet(
                                stateCoordinator.claimedIndexKey(), StringCodec.INSTANCE);
        claimedIndex.add(0, record.handoffId());

        RMap<String, String> failingMap = mock(RMap.class);
        when(failingMap.readAllMap())
                .thenThrow(new RuntimeException("temporary Redis read failure"));
        RedissonClient failingClient = mock(RedissonClient.class, delegatesTo(redis.client()));
        when(failingClient.<String, String>getMap(
                        stateCoordinator.recordKey(record.handoffId()), StringCodec.INSTANCE))
                .thenReturn(failingMap);
        RedisHitlResumeCoordinator observer =
                new RedisHitlResumeCoordinator(failingClient, namespace, Duration.ofDays(1));

        assertThatThrownBy(() -> observer.reconcileClaimed(Duration.ofMillis(1)))
                .hasMessageContaining("temporary Redis read failure");
        assertThat(claimedIndex.readAll()).contains(record.handoffId());
    }

    @Test
    void corruptRecordIsDeterministicallyRemovedFromClaimedIndex() {
        String namespace = "a2a:test:reconcile:corrupt-record:";
        RedisHitlResumeCoordinator coordinator =
                new RedisHitlResumeCoordinator(redis.client(), namespace, Duration.ofDays(1));
        HitlHandoffRecord record = claim(coordinator, 1);
        var claimedIndex =
                redis.client()
                        .<String>getScoredSortedSet(
                                coordinator.claimedIndexKey(), StringCodec.INSTANCE);
        claimedIndex.add(0, record.handoffId());
        redis.client()
                .getMap(coordinator.recordKey(record.handoffId()), StringCodec.INSTANCE)
                .put("pendingJson", "{");

        assertThat(coordinator.reconcileClaimed(Duration.ofMillis(1))).isZero();
        assertThat(claimedIndex.readAll()).doesNotContain(record.handoffId());
    }

    private static HitlHandoffRecord claim(RedisHitlResumeCoordinator coordinator, int ordinal) {
        String contextId = "ctx-" + ordinal;
        String token = "token-" + ordinal;
        HitlHandoffRecord record =
                coordinator.open(
                        new HitlOpenRequest(
                                "task-" + ordinal,
                                contextId,
                                new HitlExecutionKey("user", "agent", contextId),
                                A2aHandoffType.USER_CONFIRM,
                                List.of(new ToolUseBlock("tool-" + ordinal, "confirm", Map.of())),
                                token,
                                Duration.ofMinutes(5),
                                null));
        coordinator.claim(
                new HitlClaimRequest(
                        record.taskId(), record.contextId(), record.handoffId(), token));
        redis.client()
                .getMap(coordinator.recordKey(record.handoffId()), StringCodec.INSTANCE)
                .put("claimedAt", "1");
        return record;
    }
}
