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
package io.agentscope.harness.agent.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.middleware.TeamsMiddleware;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@ResourceLock("team-wakeups")
class TeamWakeupsTest {

    private TeamWakeups.Hook previousHook;

    @BeforeEach
    void clearHook() throws ReflectiveOperationException {
        // Initialize the middleware's built-in hook before replacing it for this test.
        TeamsMiddleware.wakeupTeamMember("team-wakeups-test-initialization", "missing");
        // Capture only the fixture state, so cleanup restores the actual production hook
        // instead of a duplicate implementation that makes coverage depend on class order.
        Field hookField = TeamWakeups.class.getDeclaredField("HOOK");
        hookField.setAccessible(true);
        previousHook = (TeamWakeups.Hook) ((AtomicReference<?>) hookField.get(null)).get();
        TeamWakeups.register(null);
    }

    @AfterEach
    void restorePreviousHook() {
        TeamWakeups.register(previousHook);
    }

    @Test
    void legacyWake_acceptsThreeArgumentLambda() {
        AtomicReference<List<String>> received = new AtomicReference<>();
        TeamWakeups.register(
                (team, member, notice) -> {
                    received.set(List.of(team, member, notice));
                    return true;
                });

        assertTrue(TeamWakeups.wake("team", "worker", "legacy notice"));
        assertEquals(List.of("team", "worker", "legacy notice"), received.get());
    }

    @Test
    void scopedWake_bridgesLegacyHookWithoutNamespaceIsolation() {
        List<List<String>> received = new ArrayList<>();
        TeamWakeups.register(
                (team, member, notice) -> {
                    received.add(List.of(team, member, notice));
                    return true;
                });

        assertTrue(TeamWakeups.wake("namespace-a", "team", "worker", "same notice"));
        assertTrue(TeamWakeups.wake("namespace-b", "team", "worker", "same notice"));

        assertEquals(2, received.size());
        assertEquals(List.of("team", "worker", "same notice"), received.get(0));
        assertEquals(received.get(0), received.get(1), "legacy hooks receive no namespace");
    }

    @Test
    void scopedWake_callsScopedHookOverride() {
        AtomicInteger legacyCalls = new AtomicInteger();
        AtomicReference<List<String>> received = new AtomicReference<>();
        TeamWakeups.register(
                new TeamWakeups.Hook() {
                    @Override
                    public boolean wake(String teamName, String memberName, String notice) {
                        legacyCalls.incrementAndGet();
                        return true;
                    }

                    @Override
                    public boolean wake(
                            String namespace, String teamName, String memberName, String notice) {
                        received.set(List.of(namespace, teamName, memberName, notice));
                        return true;
                    }
                });

        assertTrue(TeamWakeups.wake("namespace-a", "team", "worker", "scoped notice"));
        assertEquals(List.of("namespace-a", "team", "worker", "scoped notice"), received.get());
        assertEquals(0, legacyCalls.get());
        assertTrue(TeamWakeups.wake("team", "worker", "legacy notice"));
        assertEquals(1, legacyCalls.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void scopedWake_normalizesMissingNamespace(String namespace) {
        AtomicReference<String> received = new AtomicReference<>();
        TeamWakeups.register(
                new TeamWakeups.Hook() {
                    @Override
                    public boolean wake(String teamName, String memberName, String notice) {
                        return false;
                    }

                    @Override
                    public boolean wake(
                            String scope, String teamName, String memberName, String notice) {
                        received.set(scope);
                        return true;
                    }
                });

        assertTrue(TeamWakeups.wake(namespace, "team", "worker", null));
        assertEquals("default", received.get());
    }

    @Test
    void wake_withoutHookReturnsFalse() {
        assertFalse(TeamWakeups.wake("team", "worker", "notice"));
        assertFalse(TeamWakeups.wake("namespace", "team", "worker", "notice"));
    }

    @Test
    void wake_withInvalidTargetDoesNotInvokeHook() {
        AtomicInteger calls = new AtomicInteger();
        TeamWakeups.register(
                (team, member, notice) -> {
                    calls.incrementAndGet();
                    return true;
                });

        assertFalse(TeamWakeups.wake(null, "worker", "notice"));
        assertFalse(TeamWakeups.wake("team", null, "notice"));
        assertFalse(TeamWakeups.wake("team", " ", "notice"));
        assertFalse(TeamWakeups.wake("namespace", null, "worker", "notice"));
        assertFalse(TeamWakeups.wake("namespace", "team", null, "notice"));
        assertFalse(TeamWakeups.wake("namespace", "team", " ", "notice"));
        assertEquals(0, calls.get());
    }

    @Test
    void wake_whenHookThrowsReturnsFalse() {
        TeamWakeups.register(
                (team, member, notice) -> {
                    throw new IllegalStateException("hook unavailable");
                });

        assertFalse(TeamWakeups.wake("team", "worker", "notice"));
        assertFalse(TeamWakeups.wake("namespace", "team", "worker", "notice"));
    }
}
