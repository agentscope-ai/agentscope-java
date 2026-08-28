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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxLease;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Reproduction for issue #2490 — concurrent calls on one agent corrupt each other's sandbox
 * binding.
 *
 * <p>{@link SandboxLifecycleMiddleware} and {@link SandboxBackedFilesystem} are built once per
 * agent bean and hold <em>single-slot</em> state: one {@code currentAcquireResult} reference and
 * one {@code volatile Sandbox} field. The framework explicitly allows calls for <em>distinct</em>
 * {@code (userId, sessionId)} sessions to run in parallel on the same agent bean
 * ({@code AgentBase.serializeOnKey} only serialises same-session calls), so two concurrent
 * different-session calls race on these shared slots.
 *
 * <p>The test drives the exact ordering a legal two-session interleaving produces —
 * {@code A.acquire → B.acquire → A.use → A.release} — and asserts the <em>correct</em>, isolated
 * behaviour every session is entitled to: each call executes against its own sandbox, and A's
 * release tears down only A's sandbox while B's turn keeps working. On current {@code main} (single
 * shared slot) these invariants are violated, so this test <b>fails (red)</b> — that failure is the
 * reproduction of #2490. Once the sandbox binding is made per-call, the test goes green. No
 * {@code sleep}/timing is used, so it is deterministic.
 */
class SandboxLifecycleConcurrencyReproTest {

    @Test
    void differentSessionsMustNotShareSandboxBinding() {
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();

        RecordingSandbox sandboxA = new RecordingSandbox("s1");
        RecordingSandbox sandboxB = new RecordingSandbox("s2");
        RecordingLease leaseA = new RecordingLease();
        RecordingLease leaseB = new RecordingLease();

        Map<String, RecordingSandbox> sandboxBySession =
                new ConcurrentHashMap<>(Map.of("s1", sandboxA, "s2", sandboxB));
        Map<String, RecordingLease> leaseBySession =
                new ConcurrentHashMap<>(Map.of("s1", leaseA, "s2", leaseB));

        SandboxManager manager = fakeManager(sandboxBySession, leaseBySession);
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, proxy);

        RuntimeContext ctxA = callContext("s1");
        RuntimeContext ctxB = callContext("s2");

        // Call A (session s1) acquires its sandbox for its turn.
        mw.acquireForCall(ctxA);

        // Concurrently, Call B (session s2) acquires. serializeOnKey does NOT gate this because
        // s1 != s2, so both turns are legally in flight on the same agent bean at once.
        mw.acquireForCall(ctxB);

        // INVARIANT 1 — isolation: each call must execute against its own sandbox.
        // On single-slot main, A's exec is routed to B's sandbox and returns "s2" -> RED.
        assertEquals(
                "s1",
                proxy.execute(ctxA, "whoami", null).output(),
                "call A must execute against its own sandbox (s1), not another session's");
        assertEquals(
                "s2",
                proxy.execute(ctxB, "whoami", null).output(),
                "call B must execute against its own sandbox (s2)");

        // Call A finishes its turn and releases while B is still running.
        mw.releaseForCall(ctxA);

        // INVARIANT 2 — release affects only the releasing call.
        // On single-slot main, A's release tears down B and leaks A -> RED.
        assertTrue(sandboxA.stopped, "A's release must tear down A's own sandbox");
        assertTrue(leaseA.closed, "A's release must close A's own lease");
        assertFalse(sandboxB.stopped, "A's release must NOT tear down B's still-live sandbox");
        assertFalse(leaseB.closed, "A's release must NOT close B's lease");

        // INVARIANT 3 — B's turn keeps working after A releases.
        // On single-slot main, A's release cleared the shared slot, so B now sees
        // "No active sandbox" -> RED.
        assertEquals(
                "s2",
                proxy.execute(ctxB, "whoami", null).output(),
                "call B must still run against its own sandbox after A released");
    }

    private static RuntimeContext callContext(String sessionId) {
        SandboxContext sandboxContext = SandboxContext.builder().build();
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(SandboxContext.class, sandboxContext)
                .build();
    }

    /**
     * A {@link SandboxManager} whose {@code acquire} hands back a distinct fake sandbox per session
     * and whose {@code release} records the teardown it is asked to perform. The client/state-store
     * dependencies are unused because every consulted method is overridden.
     */
    private static SandboxManager fakeManager(
            Map<String, RecordingSandbox> sandboxBySession,
            Map<String, RecordingLease> leaseBySession) {
        SandboxClient<?> client = mock(SandboxClient.class);
        SessionSandboxStateStore stateStore = mock(SessionSandboxStateStore.class);
        return new SandboxManager(client, stateStore, "repro-agent") {
            @Override
            public SandboxAcquireResult acquire(
                    SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                String sid = runtimeContext.getSessionId();
                return SandboxAcquireResult.selfManaged(
                        sandboxBySession.get(sid), leaseBySession.get(sid));
            }

            @Override
            public void release(SandboxAcquireResult result) {
                if (result != null && result.getSandbox() instanceof RecordingSandbox rs) {
                    rs.stopped = true;
                }
            }

            @Override
            public void persistState(
                    SandboxAcquireResult result,
                    SandboxContext sandboxContext,
                    RuntimeContext runtimeContext) {
                // no-op for the reproduction
            }
        };
    }

    /** Minimal {@link Sandbox} that records teardown and echoes its session id from exec. */
    private static final class RecordingSandbox implements Sandbox {

        private final String sessionId;
        volatile boolean stopped;

        RecordingSandbox(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void close() {
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return !stopped;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, sessionId, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    /** {@link SandboxLease} that records whether it was closed. */
    private static final class RecordingLease implements SandboxLease {

        volatile boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
