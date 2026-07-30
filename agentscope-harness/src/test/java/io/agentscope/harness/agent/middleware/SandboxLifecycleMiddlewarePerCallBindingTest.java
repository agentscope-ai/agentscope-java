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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for per-call sandbox binding: concurrent calls on the same agent instance
 * must never observe, clear, or release each other's sandbox (previously the middleware and
 * the filesystem proxy kept a single agent-level slot, so a finishing call wiped the sandbox
 * of a still-running call — "No active sandbox" — or released the wrong sandbox session).
 */
class SandboxLifecycleMiddlewarePerCallBindingTest {

    private RecordingSandboxManager manager;
    private SandboxBackedFilesystem filesystemProxy;
    private SandboxLifecycleMiddleware middleware;

    @BeforeEach
    void setUp() {
        manager = new RecordingSandboxManager();
        filesystemProxy = new SandboxBackedFilesystem();
        middleware = new SandboxLifecycleMiddleware(manager, filesystemProxy);
    }

    private RuntimeContext newCallContext(String sessionId) {
        RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId(sessionId).build();
        ctx.put(SandboxContext.class, SandboxContext.builder().client(new StubClient()).build());
        return ctx;
    }

    @Test
    void concurrentCallsSeeTheirOwnSandbox() {
        StubSandbox sandboxA = new StubSandbox("A");
        StubSandbox sandboxB = new StubSandbox("B");
        manager.toAcquire.add(sandboxA);
        manager.toAcquire.add(sandboxB);

        RuntimeContext ctxA = newCallContext("session-a");
        RuntimeContext ctxB = newCallContext("session-b");
        middleware.acquireForCall(ctxA);
        middleware.acquireForCall(ctxB);

        // Each call resolves its own sandbox even though B acquired after A
        assertSame(sandboxA, ctxA.get(Sandbox.class));
        assertSame(sandboxB, ctxB.get(Sandbox.class));

        // Filesystem operations route to the caller's own sandbox
        filesystemProxy.execute(ctxA, "echo a", 5);
        assertEquals("echo a", sandboxA.lastCommand);
        assertNull(sandboxB.lastCommand);
        filesystemProxy.execute(ctxB, "echo b", 5);
        assertEquals("echo b", sandboxB.lastCommand);
        assertEquals("echo a", sandboxA.lastCommand);
    }

    @Test
    void finishingCallDoesNotDisturbStillRunningCall() {
        StubSandbox sandboxA = new StubSandbox("A");
        StubSandbox sandboxB = new StubSandbox("B");
        manager.toAcquire.add(sandboxA);
        manager.toAcquire.add(sandboxB);

        RuntimeContext ctxA = newCallContext("session-a");
        RuntimeContext ctxB = newCallContext("session-b");
        middleware.acquireForCall(ctxA);
        middleware.acquireForCall(ctxB);

        middleware.releaseForCall(ctxA);

        // A released exactly its own sandbox, not B's
        assertEquals(1, manager.released.size());
        assertSame(sandboxA, manager.released.get(0).getSandbox());

        // B keeps working: context binding intact, filesystem still routed to B
        assertSame(sandboxB, ctxB.get(Sandbox.class));
        filesystemProxy.execute(ctxB, "still alive", 5);
        assertEquals("still alive", sandboxB.lastCommand);

        // The fallback slot (held by B, the later acquirer) survives A's CAS-clear
        assertSame(sandboxB, filesystemProxy.getSandbox());

        middleware.releaseForCall(ctxB);
        assertEquals(2, manager.released.size());
        assertSame(sandboxB, manager.released.get(1).getSandbox());
        assertNull(filesystemProxy.getSandbox());
    }

    @Test
    void releaseForCallIsIdempotentPerContext() {
        StubSandbox sandboxA = new StubSandbox("A");
        manager.toAcquire.add(sandboxA);

        RuntimeContext ctxA = newCallContext("session-a");
        middleware.acquireForCall(ctxA);
        middleware.releaseForCall(ctxA);
        middleware.releaseForCall(ctxA);

        assertEquals(1, manager.released.size());
        assertNull(ctxA.get(Sandbox.class));
        assertNull(ctxA.get(SandboxAcquireResult.class));
    }

    /** SandboxManager stub recording acquire/release pairing; base collaborators unused. */
    private static final class RecordingSandboxManager extends SandboxManager {

        private final Deque<Sandbox> toAcquire = new ArrayDeque<>();
        private final List<SandboxAcquireResult> released = new ArrayList<>();

        private RecordingSandboxManager() {
            super(
                    new StubClient(),
                    new SessionSandboxStateStore(new InMemoryAgentStateStore(), "test-agent"),
                    "test-agent");
        }

        @Override
        public SandboxAcquireResult acquire(
                SandboxContext sandboxContext, RuntimeContext runtimeContext) {
            return SandboxAcquireResult.selfManaged(toAcquire.pop());
        }

        @Override
        public void persistState(
                SandboxAcquireResult result,
                SandboxContext sandboxContext,
                RuntimeContext runtimeContext) {
            // no-op: pairing is asserted via release()
        }

        @Override
        public void release(SandboxAcquireResult result) {
            released.add(result);
        }
    }

    private static final class StubSandbox implements Sandbox {

        private final String name;
        private String lastCommand;

        private StubSandbox(String name) {
            this.name = name;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void close() {}

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public SandboxState getState() {
            return null;
        }

        @Override
        public ExecResult exec(
                RuntimeContext runtimeContext, String command, Integer timeoutSeconds) {
            this.lastCommand = command;
            return new ExecResult(0, name, "", false);
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }

    private static final class StubClient implements SandboxClient<SandboxClientOptions> {

        @Override
        public Sandbox create(
                WorkspaceSpec workspaceSpec,
                SandboxSnapshotSpec snapshotSpec,
                SandboxClientOptions options) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Sandbox resume(SandboxState state) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public void delete(Sandbox sandbox) {}

        @Override
        public String serializeState(SandboxState state) {
            return "{}";
        }

        @Override
        public SandboxState deserializeState(String json) {
            throw new UnsupportedOperationException("not used in this test");
        }
    }
}
