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
package io.agentscope.harness.agent.filesystem.sandbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SandboxBackedFilesystemTest {

    private static RuntimeContext rc(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).build();
    }

    @Test
    void downloadFiles_decodesWrappedBase64Output() {
        byte[] expected = new byte[] {1, 2, 3, 4, 5, 6};
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, "AQID\nBAUG", "", false));
        RuntimeContext ctx = rc("session-a");
        filesystem.bindSandbox(ctx.getSessionId(), sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(ctx, List.of("/tmp/data.bin"));

        assertEquals("base64 '/tmp/data.bin'", sandbox.lastCommand);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertEquals("/tmp/data.bin", responses.get(0).path());
        assertArrayEquals(expected, responses.get(0).content());
    }

    @Test
    void downloadFiles_decodesEmptyPayloadWhenStdoutIsNull() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(0, null, "", false));
        RuntimeContext ctx = rc("session-a");
        filesystem.bindSandbox(ctx.getSessionId(), sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(ctx, List.of("/tmp/empty.bin"));

        assertEquals("base64 '/tmp/empty.bin'", sandbox.lastCommand);
        assertEquals(1, responses.size());
        assertTrue(responses.get(0).isSuccess());
        assertEquals("/tmp/empty.bin", responses.get(0).path());
        assertArrayEquals(new byte[0], responses.get(0).content());
    }

    @Test
    void downloadFiles_returnsFailureWhenCommandFails() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandbox = new FakeSandbox(new ExecResult(1, "", "boom", false));
        RuntimeContext ctx = rc("session-a");
        filesystem.bindSandbox(ctx.getSessionId(), sandbox);

        List<FileDownloadResponse> responses =
                filesystem.downloadFiles(ctx, List.of("/tmp/fail.bin"));

        assertEquals("base64 '/tmp/fail.bin'", sandbox.lastCommand);
        assertEquals(1, responses.size());
        assertTrue(!responses.get(0).isSuccess());
        assertEquals("/tmp/fail.bin", responses.get(0).path());
        assertEquals("[stderr] boom", responses.get(0).error());
    }

    @Test
    void requireSandbox_throwsWhenNoBindingForSession() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        RuntimeContext ctx = rc("unknown-session");

        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> filesystem.execute(ctx, "echo hi", null));
    }

    @Test
    void bindAndUnbind_isolatesSessionsFromEachOther() {
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox sandboxA = new FakeSandbox(new ExecResult(0, "AAAA", "", false));
        FakeSandbox sandboxB = new FakeSandbox(new ExecResult(0, "BBBB", "", false));

        filesystem.bindSandbox("session-a", sandboxA);
        filesystem.bindSandbox("session-b", sandboxB);

        // session-a routes to sandboxA
        filesystem.downloadFiles(rc("session-a"), List.of("/f"));
        assertNotNull(sandboxA.lastCommand);
        assertNull(sandboxB.lastCommand);

        // session-b routes to sandboxB
        filesystem.downloadFiles(rc("session-b"), List.of("/g"));
        assertNotNull(sandboxB.lastCommand);

        // unbind session-a; session-b still works
        filesystem.unbindSandbox("session-a");
        assertThrows(
                SandboxException.SandboxConfigurationException.class,
                () -> filesystem.execute(rc("session-a"), "echo", null));
        filesystem.downloadFiles(rc("session-b"), List.of("/h")); // must not throw
    }

    @Test
    void concurrentSessionsDontCrossContaminate() throws InterruptedException {
        int sessions = 8;
        SandboxBackedFilesystem filesystem = new SandboxBackedFilesystem();
        FakeSandbox[] sandboxes = new FakeSandbox[sessions];
        for (int i = 0; i < sessions; i++) {
            sandboxes[i] = new FakeSandbox(new ExecResult(0, "AA==", "", false)); // 1 byte
            filesystem.bindSandbox("session-" + i, sandboxes[i]);
        }

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(sessions);
        ExecutorService pool = Executors.newFixedThreadPool(sessions);
        String[] errors = new String[sessions];

        for (int i = 0; i < sessions; i++) {
            final int idx = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            RuntimeContext ctx = rc("session-" + idx);
                            // each session executes a uniquely named command
                            filesystem.execute(ctx, "cmd-" + idx, null);
                            // verify command reached the correct sandbox
                            String expected = "cmd-" + idx;
                            if (!expected.equals(sandboxes[idx].lastCommand)) {
                                errors[idx] =
                                        "session-"
                                                + idx
                                                + " routed to wrong sandbox: "
                                                + sandboxes[idx].lastCommand;
                            }
                        } catch (Exception e) {
                            errors[idx] = e.getMessage();
                        } finally {
                            done.countDown();
                        }
                    });
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        for (int i = 0; i < sessions; i++) {
            assertNull(errors[i], "Error in session-" + i + ": " + errors[i]);
        }
    }

    private static final class FakeSandbox implements Sandbox {

        private final ExecResult execResult;
        volatile String lastCommand;

        private FakeSandbox(ExecResult execResult) {
            this.execResult = execResult;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void shutdown() {}

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
            return execResult;
        }

        @Override
        public InputStream persistWorkspace() {
            return InputStream.nullInputStream();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) {}
    }
}
