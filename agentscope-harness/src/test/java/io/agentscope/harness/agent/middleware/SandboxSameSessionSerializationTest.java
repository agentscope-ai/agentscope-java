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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end reproduction for issue #2800 — two <em>same-session</em> concurrent calls on one agent
 * bean must not race on the shared persisted state slot.
 *
 * <p>The per-call binding fix (issue #2490) stops concurrent calls from corrupting each other's
 * live binding, but {@code AgentBase.serializeOnKey} serialises only the delegate execution, not
 * the sandbox {@code acquire → resume → persist → release} window run by
 * {@link SandboxLifecycleMiddleware}. Without a guard, two same-session calls each resume a separate
 * sandbox from the same slot and the second's {@code persistState} overwrites the first — losing
 * workspace changes and briefly leaking a second container.
 *
 * <p>This test wires the real {@link SandboxManager} with the built-in
 * {@link SandboxExecutionGuard#inProcess()} guard (the harness default) and drives the exact
 * overlap: {@code A.acquire → B.acquire (blocks) → A.release → B.acquire proceeds}. It asserts the
 * second call blocks until the first releases, then resumes the state the first persisted — i.e.
 * the calls run strictly serially and hand off state instead of racing.
 */
class SandboxSameSessionSerializationTest {

    @Test
    @Timeout(10)
    void sameSessionCallsSerialiseAndHandOffState() throws Exception {
        FakeSandboxClient client = new FakeSandboxClient();
        SessionSandboxStateStore stateStore =
                new SessionSandboxStateStore(new InMemoryAgentStateStore(), "agent");
        SandboxManager manager =
                new SandboxManager(client, stateStore, "agent", SandboxExecutionGuard.inProcess());
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, proxy);

        RuntimeContext ctxA = callContext("s1");
        RuntimeContext ctxB = callContext("s1"); // same session as A

        // Call A acquires first — a fresh sandbox (empty slot). Simulate a workspace write.
        mw.acquireForCall(ctxA);
        SandboxAcquireResult resultA = ctxA.get(SandboxAcquireResult.class);
        assertTrue(resultA.getSandbox() instanceof FakeSandbox);
        ((FakeSandbox) resultA.getSandbox()).content = "written-by-A";
        assertEquals(1, client.created.size(), "A creates the first sandbox");
        assertTrue(client.resumed.isEmpty(), "A starts from an empty slot, nothing to resume");

        // Call B (same session) acquires concurrently — must block on the guard held by A.
        CountDownLatch bStarting = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        Thread tB =
                new Thread(
                        () -> {
                            bStarting.countDown();
                            mw.acquireForCall(ctxB);
                            bDone.countDown();
                        });
        tB.start();

        assertTrue(bStarting.await(2, TimeUnit.SECONDS));
        assertFalse(
                bDone.await(400, TimeUnit.MILLISECONDS),
                "B's acquire must block while A holds the same-session slot");
        assertNull(ctxB.get(SandboxAcquireResult.class), "B must not bind a sandbox while blocked");
        assertTrue(client.resumed.isEmpty(), "B must not resume a second sandbox in parallel");
        assertEquals(1, client.created.size(), "no second container while A is in-flight");

        // A finishes: persist its workspace, release its sandbox, close the guard lease.
        mw.releaseForCall(ctxA);
        assertTrue(((FakeSandbox) resultA.getSandbox()).stopped, "A's sandbox is torn down");

        // B now proceeds and must resume the state A persisted — not a stale/fresh sandbox.
        assertTrue(bDone.await(3, TimeUnit.SECONDS), "B proceeds once A releases");
        tB.join(1000);
        SandboxAcquireResult resultB = ctxB.get(SandboxAcquireResult.class);
        assertEquals(1, client.resumed.size(), "B resumes exactly one sandbox from the slot");
        assertEquals(
                "written-by-A",
                ((FakeSandbox) resultB.getSandbox()).content,
                "B must resume the workspace A persisted, proving no lost update / stale slot");

        // B writes on top of A's state and releases.
        ((FakeSandbox) resultB.getSandbox()).content = "written-by-A+B";
        mw.releaseForCall(ctxB);

        // A fresh third call sees the fully serialised result of A then B.
        RuntimeContext ctxC = callContext("s1");
        mw.acquireForCall(ctxC);
        assertEquals(
                "written-by-A+B",
                ((FakeSandbox) ctxC.get(SandboxAcquireResult.class).getSandbox()).content,
                "state accumulates serially across A → B → C");
        mw.releaseForCall(ctxC);
    }

    /**
     * When the guard is interrupted while waiting for a busy slot, {@code tryEnter} throws
     * {@link InterruptedException} — which clears the thread's interrupt flag. {@code acquireForCall}
     * rethrows it wrapped in a {@link RuntimeException}, and must first restore the interrupt flag so
     * callers up the stack can still observe the cancellation instead of it being silently swallowed.
     */
    @Test
    @Timeout(10)
    void acquireRestoresInterruptFlagWhenGuardIsInterrupted() {
        FakeSandboxClient client = new FakeSandboxClient();
        SessionSandboxStateStore stateStore =
                new SessionSandboxStateStore(new InMemoryAgentStateStore(), "agent");
        // A guard whose tryEnter always reports an interrupt, standing in for a thread interrupted
        // while blocked on a busy same-session slot.
        SandboxExecutionGuard interruptingGuard =
                key -> {
                    throw new InterruptedException("interrupted while waiting for slot");
                };
        SandboxManager manager = new SandboxManager(client, stateStore, "agent", interruptingGuard);
        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());

        RuntimeContext ctx = callContext("s1");
        assertFalse(
                Thread.currentThread().isInterrupted(),
                "precondition: the test thread starts without the interrupt flag set");

        try {
            RuntimeException ex =
                    assertThrows(RuntimeException.class, () -> mw.acquireForCall(ctx));
            assertInstanceOf(
                    InterruptedException.class,
                    ex.getCause(),
                    "the interrupt must surface as the wrapped cause");
            assertTrue(
                    Thread.currentThread().isInterrupted(),
                    "acquireForCall must restore the interrupt flag the InterruptedException"
                            + " cleared");
            assertNull(
                    ctx.get(SandboxAcquireResult.class), "no sandbox is bound on a failed acquire");
        } finally {
            // Clear the flag so it does not leak into other tests sharing this thread.
            Thread.interrupted();
        }
    }

    private static RuntimeContext callContext(String sessionId) {
        SandboxContext sandboxContext =
                SandboxContext.builder().isolationScope(IsolationScope.SESSION).build();
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .put(SandboxContext.class, sandboxContext)
                .build();
    }

    /**
     * Fake client whose sandbox state carries a single {@code content} string (standing in for the
     * workspace). {@code serializeState}/{@code deserializeState} round-trip that string via the
     * state's projection-hash field, so a resumed sandbox observes exactly what the previous call
     * persisted.
     */
    private static final class FakeSandboxClient implements SandboxClient<SandboxClientOptions> {

        final List<FakeSandbox> created = new CopyOnWriteArrayList<>();
        final List<FakeSandbox> resumed = new CopyOnWriteArrayList<>();

        @Override
        public Sandbox create(
                WorkspaceSpec workspaceSpec,
                SandboxSnapshotSpec snapshotSpec,
                SandboxClientOptions options) {
            FakeSandbox s = new FakeSandbox("");
            created.add(s);
            return s;
        }

        @Override
        public Sandbox resume(SandboxState state) {
            FakeSandbox s = new FakeSandbox(state.getWorkspaceProjectionHash());
            resumed.add(s);
            return s;
        }

        @Override
        public void delete(Sandbox sandbox) {}

        @Override
        public String serializeState(SandboxState state) {
            String content = state.getWorkspaceProjectionHash();
            return content != null ? content : "";
        }

        @Override
        public SandboxState deserializeState(String json) {
            FakeState state = new FakeState();
            state.setWorkspaceProjectionHash(json);
            return state;
        }
    }

    /** Concrete {@link SandboxState} used only to carry the fake workspace payload. */
    private static final class FakeState extends SandboxState {}

    /** Fake sandbox whose {@code content} field simulates a mutable workspace. */
    private static final class FakeSandbox implements Sandbox {

        volatile String content;
        volatile boolean stopped;

        FakeSandbox(String content) {
            this.content = content;
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
            FakeState state = new FakeState();
            state.setSessionId("s1");
            state.setWorkspaceProjectionHash(content);
            return state;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            return new ExecResult(0, content, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }
}
