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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxBackgroundWrites;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.TrackingSandbox;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #3415: the async session mirror uploads after the call returns, so a self-managed sandbox
 * must stay up until that upload finishes instead of failing with "container is not running".
 */
class SessionTreeSandboxReleaseTest {

    @TempDir Path workspace;

    @AfterEach
    void drain() {
        SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS);
        SandboxBackgroundWrites.awaitPendingReleases(5, TimeUnit.SECONDS);
    }

    @Test
    void sandboxIsStoppedOnlyAfterThePendingMirrorUploads() throws Exception {
        List<String> events = TrackingSandbox.newEventLog();
        TrackingSandbox sandbox = new TrackingSandbox(events);
        CountDownLatch uploadGate = new CountDownLatch(1);
        sandbox.blockUploadsUntil(uploadGate);
        SandboxBackedFilesystem proxy = new SandboxBackedFilesystem();
        SandboxManager manager =
                new SandboxManager(
                        mock(SandboxClient.class),
                        mock(SessionSandboxStateStore.class),
                        "agent-3415") {
                    @Override
                    public SandboxAcquireResult acquire(
                            SandboxContext sandboxContext, RuntimeContext runtimeContext) {
                        return SandboxAcquireResult.selfManaged(sandbox);
                    }
                };
        SandboxLifecycleMiddleware mw = new SandboxLifecycleMiddleware(manager, proxy);
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("u1")
                        .sessionId("s1")
                        .put(SandboxContext.class, SandboxContext.builder().build())
                        .build();

        try {
            mw.acquireForCall(ctx);
            SessionTree tree =
                    new SessionTree(
                                    workspace.resolve("agents/a/sessions/s1.jsonl"),
                                    workspace,
                                    proxy,
                                    null,
                                    "agents/a/sessions/s1.jsonl")
                            .setRuntimeContext(ctx);
            tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hi", null));
            tree.flush();
            assertTrue(sandbox.awaitUploadStarted(5, TimeUnit.SECONDS), "mirror never started");

            mw.releaseForCall(ctx);

            assertTrue(sandbox.isRunning(), "release must wait for the in-flight mirror upload");
        } finally {
            uploadGate.countDown();
        }
        assertTrue(SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS));
        assertTrue(SandboxBackgroundWrites.awaitPendingReleases(5, TimeUnit.SECONDS));
        assertEquals(List.of("upload", "upload", "stop", "shutdown"), events);
    }

    @Test
    void mirrorIsSkippedOnceItsSandboxIsBeingReleased() throws Exception {
        System.setProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY, "100");
        List<String> events = TrackingSandbox.newEventLog();
        TrackingSandbox sandbox = new TrackingSandbox(events);
        sandbox.start();
        SandboxBackgroundWrites.Hold stuck = SandboxBackgroundWrites.tryHold(sandbox);
        try {
            SandboxBackgroundWrites.releaseWhenIdle(sandbox, () -> {}).get(5, TimeUnit.SECONDS);
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .sessionId("s2")
                            .put(
                                    SandboxAcquireResult.class,
                                    SandboxAcquireResult.selfManaged(sandbox))
                            .build();
            SessionTree tree =
                    new SessionTree(
                                    workspace.resolve("agents/a/sessions/s2.jsonl"),
                                    workspace,
                                    new SandboxBackedFilesystem(),
                                    null,
                                    "agents/a/sessions/s2.jsonl")
                            .setRuntimeContext(ctx);
            tree.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hi", null));

            tree.flush();

            assertTrue(SessionTree.awaitMirrorQuiescence(5, TimeUnit.SECONDS));
            assertEquals(List.of(), events, "no upload may be attempted on a released sandbox");
        } finally {
            stuck.close();
            System.clearProperty(SandboxBackgroundWrites.MAX_DEFER_MILLIS_PROPERTY);
        }
    }
}
