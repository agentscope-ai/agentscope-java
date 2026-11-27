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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxFileTransfer;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.transcript.ObjectStoreTranscriptStore;
import io.agentscope.harness.agent.transcript.TranscriptRef;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The asynchronous session mirror must reach the sandbox of the call that wrote the files — never
 * another call's, when several users share one agent (#4273).
 *
 * <p>The mirror is scheduled while the call runs and performed after it, on a separate thread. The
 * sandbox it addresses is therefore decided at scheduling time. One agent bean has one sandbox
 * proxy whose agent-level slot is last-writer-wins under concurrency, so with a second user
 * acquiring after the first, that slot names the second user's sandbox.
 */
class SessionTreeMirrorIsolationTest {

    @TempDir Path workspace;

    @Test
    void sessionFilesOfACall_reachItsOwnSandbox_neverTheOneAcquiredAfterIt() throws Exception {
        OneAgent agent = new OneAgent();
        RuntimeContext alice = call("alice");
        RuntimeContext bob = call("bob");
        agent.acquire(alice);
        // From here on the agent-level slot names bob's sandbox.
        agent.acquire(bob);

        SessionTree tree =
                new SessionTree(contextFile("alice"), workspace, agent.proxy)
                        .setRuntimeContext(alice);
        tree.append(message("written by alice"));
        tree.flush();

        // The mirror runs after alice's call has released its sandbox.
        agent.release(alice);
        awaitMirror();

        assertTrue(
                agent.sandboxOf("bob").uploads.isEmpty(),
                "alice's session files must never be written into bob's sandbox, but were: "
                        + agent.sandboxOf("bob").uploads);
        assertFalse(
                agent.sandboxOf("alice").uploads.isEmpty(),
                "alice's session files must reach alice's own sandbox");
    }

    @Test
    void transcriptSegmentsOfACall_reachItsOwnSandbox_neverTheOneAcquiredAfterIt()
            throws Exception {
        OneAgent agent = new OneAgent();
        RuntimeContext alice = call("alice");
        RuntimeContext bob = call("bob");
        agent.acquire(alice);
        agent.acquire(bob);

        SessionTree tree =
                new SessionTree(contextFile("alice"), workspace, agent.proxy)
                        .setRuntimeContext(alice);
        tree.setTranscriptStore(
                new ObjectStoreTranscriptStore(agent.proxy),
                new TranscriptRef("tenant", "agent-a", "alice"));
        tree.append(message("written by alice"));
        tree.flush();

        agent.release(alice);
        awaitMirror();

        assertTrue(
                agent.sandboxOf("bob").uploads.isEmpty(),
                "alice's transcript must never be written into bob's sandbox, but was: "
                        + agent.sandboxOf("bob").uploads);
        assertFalse(
                agent.sandboxOf("alice").uploads.isEmpty(),
                "alice's transcript must reach alice's own sandbox");
    }

    @Test
    void aCallWithoutASandboxOfItsOwn_mirrorsNowhere() throws Exception {
        OneAgent agent = new OneAgent();
        RuntimeContext bob = call("bob");
        // The agent-level slot names bob's sandbox while another call, which never acquired
        // one, writes its session.
        agent.acquire(bob);
        RuntimeContext carol = RuntimeContext.builder().userId("carol").sessionId("carol").build();

        SessionTree tree =
                new SessionTree(contextFile("carol"), workspace, agent.proxy)
                        .setRuntimeContext(carol);
        tree.append(message("written by carol"));
        tree.flush();

        awaitMirror();

        assertTrue(
                agent.sandboxOf("bob").uploads.isEmpty(),
                "a call that has no sandbox of its own must not write into another call's, but"
                        + " did: "
                        + agent.sandboxOf("bob").uploads);
        assertTrue(agent.sandboxOf("alice").uploads.isEmpty());
    }

    // -----------------------------------------------------------------------
    //  Fixture
    // -----------------------------------------------------------------------

    private Path contextFile(String user) {
        return workspace.resolve("agents/agent-a/sessions/" + user + ".jsonl");
    }

    private static SessionEntry message(String text) {
        return new SessionEntry.MessageEntry(null, null, null, "USER", text, null);
    }

    private static void awaitMirror() throws InterruptedException {
        assertTrue(
                SessionTree.awaitMirrorQuiescence(10, TimeUnit.SECONDS),
                "the mirror executor did not settle");
    }

    private static RuntimeContext call(String user) {
        return RuntimeContext.builder()
                .userId(user)
                .sessionId(user)
                .put(SandboxContext.class, SandboxContext.builder().build())
                .build();
    }

    /**
     * One agent bean: a single sandbox proxy shared by every call, and the real lifecycle
     * middleware binding each call's sandbox the way a build does.
     */
    private final class OneAgent {
        final SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();
        private final Map<String, RecordingSandbox> sandboxes =
                Map.of(
                        "alice",
                        new RecordingSandbox("/ws-alice"),
                        "bob",
                        new RecordingSandbox("/ws-bob"));
        private final SandboxLifecycleMiddleware middleware =
                new SandboxLifecycleMiddleware(managerFor(sandboxes), proxy);

        void acquire(RuntimeContext ctx) {
            middleware.acquireForCall(ctx);
        }

        void release(RuntimeContext ctx) {
            middleware.releaseForCall(ctx);
        }

        RecordingSandbox sandboxOf(String user) {
            return sandboxes.get(user);
        }
    }

    /** Hands each user its own, user-managed sandbox, which keeps running after the call. */
    private static SandboxManager managerFor(Map<String, RecordingSandbox> byUser) {
        return new SandboxManager(
                mock(SandboxClient.class), mock(SessionSandboxStateStore.class), "shared-agent") {
            @Override
            public SandboxAcquireResult acquire(
                    SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                return SandboxAcquireResult.userManaged(byUser.get(runtimeContext.getUserId()));
            }

            @Override
            public void release(SandboxAcquireResult result) {}

            @Override
            public void persistState(
                    SandboxAcquireResult result,
                    SandboxContext sandboxContext,
                    RuntimeContext runtimeContext) {}
        };
    }

    /** Sandbox with a workspace root that records every file transferred into it. */
    private static final class RecordingSandbox implements Sandbox, SandboxFileTransfer {
        private final String root;
        private final SandboxState state = new RecordingState();
        final List<String> uploads = new CopyOnWriteArrayList<>();

        RecordingSandbox(String root) {
            this.root = root;
            WorkspaceSpec spec = new WorkspaceSpec();
            spec.setRoot(root);
            state.setWorkspaceSpec(spec);
        }

        @Override
        public boolean supportsFileTransfer(String absolutePath) {
            return absolutePath.startsWith(root + "/");
        }

        @Override
        public void uploadFile(String absolutePath, byte[] content) {
            uploads.add(absolutePath);
        }

        @Override
        public byte[] downloadFile(String absolutePath) {
            return new byte[0];
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
            return state;
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

    private static final class RecordingState extends SandboxState {}
}
