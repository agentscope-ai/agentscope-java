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

package io.agentscope.core.a2a.server.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.a2a.agent.hitl.A2aHandoffType;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalHitlResumeCoordinatorTest {

    private static final String TOKEN = "never-persist-this-token";
    private static final Instant NOW = Instant.parse("2026-07-14T00:00:00Z");

    private LocalHitlResumeCoordinator coordinator;
    private HitlExecutionKey executionKey;

    @BeforeEach
    void setUp() {
        coordinator = new LocalHitlResumeCoordinator(Clock.fixed(NOW, ZoneOffset.UTC));
        executionKey = new HitlExecutionKey("alice", "agent-a", "context-1");
    }

    @Test
    void shouldAtomicallyClaimACompleteBoundTokenWithoutPersistingPlaintext() {
        HitlHandoffRecord opened = open();

        assertFalse(opened.toString().contains(TOKEN));
        assertFalse(opened.tokenDigest().contains(TOKEN));
        HitlHandoffRecord claimed =
                coordinator.claim(
                        new HitlClaimRequest("task-1", "context-1", opened.handoffId(), TOKEN));

        assertEquals(HitlHandoffStatus.CLAIMED, claimed.status());
        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.claim(
                                new HitlClaimRequest(
                                        "task-1", "context-1", opened.handoffId(), TOKEN)));
    }

    @Test
    void shouldValidateClaimCredentialWithoutConsumingTheOpenHandoff() {
        HitlHandoffRecord opened = open();
        HitlClaimRequest valid =
                new HitlClaimRequest("task-1", "context-1", opened.handoffId(), TOKEN);

        HitlHandoffRecord validated = coordinator.validateClaim(valid);

        assertEquals(HitlHandoffStatus.OPEN, validated.status());
        assertEquals(
                HitlHandoffStatus.OPEN, coordinator.get(opened.handoffId()).orElseThrow().status());
        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.validateClaim(
                                new HitlClaimRequest(
                                        "task-1", "context-1", opened.handoffId(), "wrong-token")));
        assertEquals(HitlHandoffStatus.CLAIMED, coordinator.claim(valid).status());
    }

    @Test
    void shouldUsePersistedExecutionKeyAndRejectWrongTaskAndToken() {
        HitlHandoffRecord opened = open();

        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.claim(
                                new HitlClaimRequest(
                                        "wrong-task", "context-1", opened.handoffId(), TOKEN)));
        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.claim(
                                new HitlClaimRequest(
                                        "task-1", "context-1", opened.handoffId(), "wrong-token")));
        assertEquals(
                HitlHandoffStatus.OPEN, coordinator.get(opened.handoffId()).orElseThrow().status());
    }

    @Test
    void shouldBindTokenDigestToThePersistedUserId() {
        String fingerprint = HitlTokenDigests.pendingFingerprint(List.of(tool("call-1")));
        String alice =
                HitlTokenDigests.boundTokenDigest(
                        "task-1", "context-1", "handoff-1", executionKey, fingerprint, TOKEN);
        String bob =
                HitlTokenDigests.boundTokenDigest(
                        "task-1",
                        "context-1",
                        "handoff-1",
                        new HitlExecutionKey("bob", "agent-a", "context-1"),
                        fingerprint,
                        TOKEN);

        assertFalse(alice.equals(bob));
    }

    @Test
    void shouldCancelOnlyOpenHandoffsAndReleaseSession() {
        HitlHandoffRecord opened = open();
        assertTrue(coordinator.hasOpenHandoff(executionKey));

        HitlHandoffRecord canceled =
                coordinator.cancel(
                        new HitlCancelRequest("task-1", "context-1", opened.handoffId(), TOKEN));

        assertEquals(HitlHandoffStatus.CANCELED, canceled.status());
        assertFalse(coordinator.hasOpenHandoff(executionKey));
        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.cancel(
                                new HitlCancelRequest(
                                        "task-1", "context-1", opened.handoffId(), TOKEN)));
    }

    @Test
    void shouldExpireOpenHandoffAtTtl() {
        HitlHandoffRecord opened =
                coordinator.open(
                        new HitlOpenRequest(
                                "task-1",
                                "context-1",
                                executionKey,
                                A2aHandoffType.USER_CONFIRM,
                                List.of(tool("call-1")),
                                TOKEN,
                                Duration.ZERO,
                                null));

        assertFalse(
                coordinator.hasOpenHandoff(executionKey),
                "an expired local handoff must not keep blocking ordinary turns");
        assertThrows(
                HitlResumeRejectedException.class,
                () ->
                        coordinator.claim(
                                new HitlClaimRequest(
                                        "task-1", "context-1", opened.handoffId(), TOKEN)));
        assertEquals(
                HitlHandoffStatus.EXPIRED,
                coordinator.get(opened.handoffId()).orElseThrow().status());
    }

    @Test
    void shouldSupersedeClaimedHandoffWhenAgentPausesAgain() {
        HitlHandoffRecord first = open();
        coordinator.claim(new HitlClaimRequest("task-1", "context-1", first.handoffId(), TOKEN));

        HitlHandoffRecord second =
                coordinator.open(
                        new HitlOpenRequest(
                                "task-1",
                                "context-1",
                                executionKey,
                                A2aHandoffType.EXTERNAL_EXECUTION,
                                List.of(tool("call-2")),
                                "next-token",
                                Duration.ofDays(7),
                                first.handoffId()));

        assertEquals(
                HitlHandoffStatus.SUPERSEDED,
                coordinator.get(first.handoffId()).orElseThrow().status());
        assertEquals(HitlHandoffStatus.OPEN, second.status());
    }

    private HitlHandoffRecord open() {
        return coordinator.open(
                new HitlOpenRequest(
                        "task-1",
                        "context-1",
                        executionKey,
                        A2aHandoffType.USER_CONFIRM,
                        List.of(tool("call-1")),
                        TOKEN,
                        Duration.ofDays(7),
                        null));
    }

    private ToolUseBlock tool(String id) {
        return new ToolUseBlock(id, "probe", Map.of("value", 1));
    }
}
