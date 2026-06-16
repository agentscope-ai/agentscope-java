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
package io.agentscope.harness.agent.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Focused unit test for {@link MemoryFlushMiddleware#shouldFlushNow} — the trigger gate
 * is the only piece of new logic in the middleware and can be exercised without standing
 * up a full {@code ReActAgent} pipeline.
 */
class MemoryFlushMiddlewareTriggerTest {

    private static final RuntimeContext RC_ANON = RuntimeContext.empty();
    private static final RuntimeContext RC_USER_A =
            RuntimeContext.builder().userId("userA").build();
    private static final RuntimeContext RC_USER_B =
            RuntimeContext.builder().userId("userB").build();
    private static final RuntimeContext RC_SESSION_1 =
            RuntimeContext.builder().userId("userA").sessionId("session1").build();
    private static final RuntimeContext RC_SESSION_2 =
            RuntimeContext.builder().userId("userA").sessionId("session2").build();

    /** Creates a USER-scope (default) middleware for trigger-gate tests. */
    private static MemoryFlushMiddleware make(MemoryConfig.FlushTrigger trigger) {
        return make(trigger, IsolationScope.USER);
    }

    /** workspaceManager + model are only consumed inside doFlush, which we don't call here. */
    private static MemoryFlushMiddleware make(
            MemoryConfig.FlushTrigger trigger, IsolationScope scope) {
        return new MemoryFlushMiddleware(
                null, null, MemoryFlushManager.DEFAULT_FLUSH_PROMPT, trigger, scope);
    }

    @Test
    void alwaysMode_returnsTrueOnEveryCall() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.always());
        for (int i = 0; i < 5; i++) {
            assertTrue(
                    mw.shouldFlushNow(RC_ANON), "ALWAYS should always return true (i=" + i + ")");
        }
    }

    @Test
    void neverMode_returnsFalseOnEveryCall() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.never());
        for (int i = 0; i < 5; i++) {
            assertFalse(
                    mw.shouldFlushNow(RC_ANON), "NEVER should always return false (i=" + i + ")");
        }
    }

    @Test
    void throttledMode_firstCallWinsThenBackOff() {
        // 1-hour gap — way larger than the test runtime, so only the first call should pass.
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)));

        assertTrue(mw.shouldFlushNow(RC_ANON), "first call must win the slot");
        assertFalse(mw.shouldFlushNow(RC_ANON), "second call within the window must be throttled");
        assertFalse(mw.shouldFlushNow(RC_ANON), "third call within the window must be throttled");
    }

    @Test
    void throttledMode_smallGapEventuallyReleases() throws InterruptedException {
        Duration gap = Duration.ofMillis(50);
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(gap));

        assertTrue(mw.shouldFlushNow(RC_ANON), "first call wins");
        assertFalse(mw.shouldFlushNow(RC_ANON), "immediate retry is throttled");

        // Sleep just over the gap so the next call can re-acquire.
        Thread.sleep(gap.toMillis() * 3);

        assertTrue(mw.shouldFlushNow(RC_ANON), "after gap, slot is free again");
        assertFalse(
                mw.shouldFlushNow(RC_ANON), "immediate retry after the new winner is throttled");
    }

    @Test
    void throttledMode_zeroGapNormalisesToAlways() {
        // FlushTrigger.throttled(Duration.ZERO) is the always() singleton — verify the gate
        // behaves accordingly even when callers pass the zero-Duration form.
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ZERO));
        for (int i = 0; i < 3; i++) {
            assertTrue(mw.shouldFlushNow(RC_ANON), "zero-gap throttling must behave like ALWAYS");
        }
    }

    @Test
    void throttledMode_perUserIsolation_userBNotBlockedByUserA() {
        // 1-hour gap. User A wins the slot. User B must still get their own independent slot
        // on the same shared middleware instance (shared agent multi-tenant scenario).
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)));

        assertTrue(mw.shouldFlushNow(RC_USER_A), "user A wins their slot");
        assertFalse(mw.shouldFlushNow(RC_USER_A), "user A is now throttled");

        assertTrue(
                mw.shouldFlushNow(RC_USER_B),
                "user B must win their own independent slot even though user A already flushed");
        assertFalse(mw.shouldFlushNow(RC_USER_B), "user B is now throttled in their own window");
    }

    @Test
    void throttledMode_anonymousCallersShareOneSlot() {
        // Callers without a userId are treated as a single anonymous tenant and share one slot.
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)));

        assertTrue(mw.shouldFlushNow(RC_ANON), "first anonymous call wins");
        assertFalse(mw.shouldFlushNow(RC_ANON), "second anonymous call is throttled");
        assertFalse(mw.shouldFlushNow(null), "null rc also maps to anonymous slot");
    }

    // ── IsolationScope.SESSION ────────────────────────────────────────────────

    @Test
    void sessionScope_timerKeyUsesSessionId() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.always(), IsolationScope.SESSION);
        assertEquals("session1", mw.timerKeyFor(RC_SESSION_1));
        assertEquals("session2", mw.timerKeyFor(RC_SESSION_2));
        assertEquals("", mw.timerKeyFor(RC_ANON), "no sessionId → empty key");
    }

    @Test
    void sessionScope_sameUserDifferentSessionsAreIndependent() {
        // SESSION scope: same userId but different sessionIds → each session has its own throttle.
        MemoryFlushMiddleware mw =
                make(
                        MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)),
                        IsolationScope.SESSION);

        assertTrue(mw.shouldFlushNow(RC_SESSION_1), "session1 wins its slot");
        assertFalse(mw.shouldFlushNow(RC_SESSION_1), "session1 is now throttled");

        assertTrue(
                mw.shouldFlushNow(RC_SESSION_2),
                "session2 must win its own independent slot even though session1 already flushed");
        assertFalse(mw.shouldFlushNow(RC_SESSION_2), "session2 is now throttled");
    }

    // ── IsolationScope.AGENT / GLOBAL ────────────────────────────────────────

    @Test
    void agentScope_timerKeyIsConstant() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.always(), IsolationScope.AGENT);
        assertEquals("", mw.timerKeyFor(RC_USER_A), "AGENT scope uses empty key for all callers");
        assertEquals("", mw.timerKeyFor(RC_USER_B));
        assertEquals("", mw.timerKeyFor(RC_ANON));
    }

    @Test
    void agentScope_allCallersShareOneThrottleSlot() {
        // AGENT scope: memory is shared across all users → all callers must share one throttle
        // window to avoid concurrent maintenance races on shared MEMORY.md.
        MemoryFlushMiddleware mw =
                make(
                        MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)),
                        IsolationScope.AGENT);

        assertTrue(mw.shouldFlushNow(RC_USER_A), "userA wins the shared slot");
        assertFalse(mw.shouldFlushNow(RC_USER_A), "userA is throttled");
        assertFalse(
                mw.shouldFlushNow(RC_USER_B),
                "userB must also be blocked because they share the same memory namespace");
    }

    @Test
    void globalScope_allCallersShareOneThrottleSlot() {
        MemoryFlushMiddleware mw =
                make(
                        MemoryConfig.FlushTrigger.throttled(Duration.ofHours(1)),
                        IsolationScope.GLOBAL);

        assertTrue(mw.shouldFlushNow(RC_USER_A), "first caller wins the global slot");
        assertFalse(mw.shouldFlushNow(RC_USER_B), "second caller blocked by shared global slot");
    }

    // ── timerKeyFor edge cases ────────────────────────────────────────────────

    @Test
    void userScope_timerKeyUsesUserId() {
        MemoryFlushMiddleware mw = make(MemoryConfig.FlushTrigger.always(), IsolationScope.USER);
        assertEquals("userA", mw.timerKeyFor(RC_USER_A));
        assertEquals("userB", mw.timerKeyFor(RC_USER_B));
        assertEquals("", mw.timerKeyFor(RC_ANON), "no userId → empty key");
        assertEquals("", mw.timerKeyFor(null), "null rc → empty key");
    }

    @Test
    void onAgent_passesThroughDownstreamEvents_andRunsOffloadPath() {
        WorkspaceManager workspaceManager = mock(WorkspaceManager.class);
        MemoryFlushMiddleware mw =
                new MemoryFlushMiddleware(
                        workspaceManager,
                        null,
                        MemoryFlushManager.DEFAULT_FLUSH_PROMPT,
                        MemoryConfig.FlushTrigger.never());

        ReActAgent agent =
                ReActAgent.builder()
                        .name("test-agent")
                        .sysPrompt("Test agent")
                        .model(new MockModel("noop"))
                        .toolkit(new Toolkit())
                        .build();

        Msg message = Msg.builder().textContent("remember this").build();
        AgentState state =
                AgentState.builder()
                        .sessionId("session-1")
                        .userId("user-1")
                        .addMessage(message)
                        .build();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("session-1")
                        .userId("user-1")
                        .agentState(state)
                        .build();

        CustomEvent downstream = new CustomEvent("downstream");
        List<AgentEvent> events =
                mw.onAgent(
                                agent,
                                ctx,
                                new AgentInput(List.of(message)),
                                in -> Flux.just(downstream))
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals(1, events.size());
        assertSame(downstream, events.get(0));
        verify(workspaceManager)
                .updateSessionIndex(
                        any(), eq("test-agent"), eq("session-1"), eq("conversation offloaded"));
    }
}
