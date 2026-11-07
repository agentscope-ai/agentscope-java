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
package io.agentscope.core.skill.mirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.session.InMemorySessionLogStore;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionKey;
import io.agentscope.core.session.SessionLog;
import io.agentscope.core.session.SessionRecorder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for session anchoring over the session journal: first-writer-wins on INITIAL
 * anchors, FALLBACK as the only legal re-anchor, refresh audits never touching bindings, and
 * persistence across recorder instances (replay semantics).
 */
@Tag("unit")
@DisplayName("SkillAnchorService session anchoring")
class SkillAnchorServiceTest {

    private InMemorySessionLogStore store;
    private SessionLog log;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionLogStore();
        log = store.open(new SessionKey("user", "agent", "session"), RuntimeContext.empty());
    }

    @Test
    @DisplayName("INITIAL anchoring is first-writer-wins")
    void testInitialAnchorFirstWriterWins() {
        SessionRecorder recorder = new SessionRecorder(log, "t1");

        SkillAnchorService.AnchoredVersion first =
                SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", "pin");
        SkillAnchorService.AnchoredVersion second =
                SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v2", "pin");

        assertEquals("v1", first.version());
        assertEquals(
                "v1", second.version(), "a later anchor attempt must keep the winner's version");
        assertEquals(
                "v1",
                SkillAnchorService.findAnchor(recorder, "demo-skill_src").orElseThrow().version());
    }

    @Test
    @DisplayName("Anchors persist across recorder instances (replay rebuild)")
    void testAnchorsPersistAcrossRecorders() {
        SessionRecorder first = new SessionRecorder(log, "t1");
        SkillAnchorService.anchorInitial(first, "demo-skill_src", "v1", "pin");
        first.close();

        SessionRecorder second = new SessionRecorder(log, "t2");
        assertEquals(
                "v1",
                SkillAnchorService.findAnchor(second, "demo-skill_src").orElseThrow().version(),
                "a fresh recorder replays the journal and finds the anchor");
        second.close();
    }

    @Test
    @DisplayName("FALLBACK is the only legal re-anchor of an existing pin")
    void testFallbackReanchors() {
        SessionRecorder recorder = new SessionRecorder(log, "t1");
        SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", "pin");

        SkillAnchorService.anchorFallback(recorder, "demo-skill_src", "v1", "v5");

        assertEquals(
                "v5",
                SkillAnchorService.findAnchor(recorder, "demo-skill_src").orElseThrow().version(),
                "the one-shot fallback re-anchor takes effect");
        SessionEvent fallback =
                recorder.findLast(SkillAnchorService.EVENT_TYPE, event -> true).orElseThrow();
        assertEquals("FALLBACK", fallback.data().get("reason"));
        assertEquals(
                "v1",
                fallback.data().get("fromVersion"),
                "the journal must record the abandoned version for audit");
        assertEquals("v5", fallback.data().get("version"));
    }

    @Test
    @DisplayName("FALLBACK without a matching current anchor is a no-op (compare-and-set)")
    void testFallbackRequiresMatchingAnchor() {
        SessionRecorder recorder = new SessionRecorder(log, "t1");

        SkillAnchorService.anchorFallback(recorder, "demo-skill_src", "v1", "v5");
        assertTrue(
                SkillAnchorService.findAnchor(recorder, "demo-skill_src").isEmpty(),
                "a fallback without a prior anchor must not create one");

        SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", "pin");
        SkillAnchorService.anchorFallback(recorder, "demo-skill_src", "v9", "v5");
        assertEquals(
                "v1",
                SkillAnchorService.findAnchor(recorder, "demo-skill_src").orElseThrow().version(),
                "a stale fallback must not clobber the current anchor");
    }

    @Test
    @DisplayName("INITIAL events carry no fromVersion; null arguments are rejected")
    void testInitialShapeAndNullContracts() {
        SessionRecorder recorder = new SessionRecorder(log, "t1");
        SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", "pin");
        SessionEvent initial =
                recorder.findLast(SkillAnchorService.EVENT_TYPE, event -> true).orElseThrow();
        assertEquals("INITIAL", initial.data().get("reason"));
        assertFalse(
                initial.data().containsKey("fromVersion"),
                "fromVersion is a FALLBACK-only audit field");

        assertThrows(
                NullPointerException.class,
                () -> SkillAnchorService.anchorInitial(recorder, null, "v1", "pin"));
        assertThrows(
                NullPointerException.class,
                () -> SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", null));
        assertThrows(
                NullPointerException.class, () -> SkillAnchorService.findAnchor(recorder, null));
        assertThrows(
                NullPointerException.class,
                () -> SkillAnchorService.anchorFallback(recorder, "demo-skill_src", null, "v2"));
    }

    @Test
    @DisplayName("Refresh audits never change the effective anchor")
    void testRefreshAuditDoesNotAffectAnchors() {
        SessionRecorder recorder = new SessionRecorder(log, "t1");
        SkillAnchorService.anchorInitial(recorder, "demo-skill_src", "v1", "pin");

        SkillAnchorService.refreshAudit(recorder, "demo-skill_src", "v1", "v9");
        SkillAnchorService.refreshAudit(recorder, "other-skill_src", null, "v2");

        assertEquals(
                "v1",
                SkillAnchorService.findAnchor(recorder, "demo-skill_src").orElseThrow().version());
        assertTrue(
                SkillAnchorService.findAnchor(recorder, "other-skill_src").isEmpty(),
                "a refresh audit alone must not create an anchor");
    }

    @Test
    @DisplayName("Without a recorder everything degrades to no-ops / empty")
    void testNullRecorderDegrades() {
        SkillAnchorService.AnchoredVersion anchored =
                SkillAnchorService.anchorInitial(null, "demo-skill_src", "v1", "pin");
        assertEquals("v1", anchored.version(), "in-memory anchoring still returns its own version");
        SkillAnchorService.anchorFallback(null, "demo-skill_src", "v1", "v2");
        SkillAnchorService.refreshAudit(null, "demo-skill_src", "v1", "v2");
        assertTrue(SkillAnchorService.findAnchor(null, "demo-skill_src").isEmpty());
    }

    @Test
    @DisplayName("Racing INITIAL anchors converge on one effective anchor")
    void testRacingInitialAnchorsConverge() throws Exception {
        // The check-then-append window is narrow; many fresh journals make the race likely
        // at least once, and the assertion must hold on every round either way.
        for (int round = 0; round < 50; round++) {
            InMemorySessionLogStore roundStore = new InMemorySessionLogStore();
            SessionLog roundLog =
                    roundStore.open(
                            new SessionKey("user", "agent", "race-" + round),
                            RuntimeContext.empty());
            SessionRecorder recorder = new SessionRecorder(roundLog, "t1");
            SkillAnchorService.AnchoredVersion[] results =
                    new SkillAnchorService.AnchoredVersion[2];
            CountDownLatch done = new CountDownLatch(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(
                        () -> {
                            barrier.await();
                            results[0] =
                                    SkillAnchorService.anchorInitial(
                                            recorder, "demo-skill_src", "v1", "pin");
                            done.countDown();
                            return null;
                        });
                pool.submit(
                        () -> {
                            barrier.await();
                            results[1] =
                                    SkillAnchorService.anchorInitial(
                                            recorder, "demo-skill_src", "v2", "pin");
                            done.countDown();
                            return null;
                        });
                assertTrue(done.await(10, TimeUnit.SECONDS), "both racers must finish");

                assertEquals(
                        results[0].version(),
                        results[1].version(),
                        "racing builders must walk away with the same effective anchor");
                assertEquals(
                        results[0].version(),
                        SkillAnchorService.findAnchor(recorder, "demo-skill_src")
                                .orElseThrow()
                                .version(),
                        "the returned anchor is the journal's effective one");
            } finally {
                pool.shutdownNow();
                recorder.close();
            }
        }
    }
}
