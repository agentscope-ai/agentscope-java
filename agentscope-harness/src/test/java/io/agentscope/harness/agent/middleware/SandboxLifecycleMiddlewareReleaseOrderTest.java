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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the {@link SandboxLifecycleMiddleware#releaseForCall} ordering: the session
 * must be released (stop/persist workspace) before the session state is persisted, so state
 * mutations made during stop — e.g. per-session snapshot records — are captured by the persist.
 */
class SandboxLifecycleMiddlewareReleaseOrderTest {

    @Test
    void releaseRunsBeforePersistAndSeesStopMutations() {
        List<String> order = new ArrayList<>();
        AtomicBoolean stopMutation = new AtomicBoolean(false);
        AtomicBoolean mutationVisibleAtPersist = new AtomicBoolean(false);

        RecordingSandbox sandbox = new RecordingSandbox(stopMutation);
        RecordingLease lease = new RecordingLease();
        SandboxManager manager =
                new SandboxManager(
                        mock(SandboxClient.class),
                        mock(SessionSandboxStateStore.class),
                        "order-agent") {
                    @Override
                    public SandboxAcquireResult acquire(
                            SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                        return SandboxAcquireResult.selfManaged(sandbox, lease);
                    }

                    @Override
                    public void release(SandboxAcquireResult result) {
                        order.add("release");
                        try {
                            result.getSandbox().stop();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                    @Override
                    public void persistState(
                            SandboxAcquireResult result,
                            SandboxContext sandboxContext,
                            RuntimeContext runtimeContext) {
                        order.add("persist");
                        mutationVisibleAtPersist.set(stopMutation.get());
                    }
                };

        SandboxLifecycleMiddleware mw =
                new SandboxLifecycleMiddleware(manager, new SandboxBackedFilesystem());
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s1")
                        .put(SandboxContext.class, SandboxContext.builder().build())
                        .build();

        mw.acquireForCall(ctx);
        mw.releaseForCall(ctx);

        assertEquals(
                List.of("release", "persist"),
                order,
                "release (stop) must run before state persist");
        assertTrue(
                mutationVisibleAtPersist.get(),
                "mutations made during stop must be visible to the persist below");
        assertTrue(lease.closed, "release must close the call's own lease");
    }

    /** Minimal {@link Sandbox} that records its stop as a shared mutation. */
    private static final class RecordingSandbox implements Sandbox {

        private final AtomicBoolean stopMutation;

        RecordingSandbox(AtomicBoolean stopMutation) {
            this.stopMutation = stopMutation;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            stopMutation.set(true);
        }

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
            return new ExecResult(0, "", "", false);
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
