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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PinnedSandboxFilesystemTest {

    @Test
    void releaseWaitsForActiveUploadThenRejectsLaterUploads() throws Exception {
        BlockingSandbox sandbox = new BlockingSandbox();
        PinnedSandboxFilesystem.markSandboxAcquired(sandbox);
        PinnedSandboxFilesystem filesystem = new PinnedSandboxFilesystem(sandbox);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> upload =
                    executor.submit(
                            () ->
                                    filesystem.uploadFiles(
                                            RuntimeContext.empty(),
                                            List.of(Map.entry("session.jsonl", new byte[] {1}))));
            assertTrue(sandbox.uploadStarted.await(2, TimeUnit.SECONDS));

            Future<?> release =
                    executor.submit(() -> PinnedSandboxFilesystem.markSandboxReleased(sandbox));
            TimeUnit.MILLISECONDS.sleep(100);
            assertFalse(release.isDone(), "release should briefly coordinate with active upload");

            sandbox.allowUploadToFinish.countDown();
            upload.get(2, TimeUnit.SECONDS);
            release.get(2, TimeUnit.SECONDS);

            var rejected =
                    filesystem.uploadFiles(
                            RuntimeContext.empty(),
                            List.of(Map.entry("later.jsonl", new byte[] {2})));
            assertFalse(rejected.get(0).isSuccess());
        } finally {
            sandbox.allowUploadToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releaseDoesNotWaitIndefinitelyForStalledUpload() throws Exception {
        BlockingSandbox sandbox = new BlockingSandbox();
        PinnedSandboxFilesystem.markSandboxAcquired(sandbox);
        PinnedSandboxFilesystem filesystem = new PinnedSandboxFilesystem(sandbox);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> upload =
                    executor.submit(
                            () ->
                                    filesystem.uploadFiles(
                                            RuntimeContext.empty(),
                                            List.of(Map.entry("session.jsonl", new byte[] {1}))));
            assertTrue(sandbox.uploadStarted.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            PinnedSandboxFilesystem.markSandboxReleased(sandbox);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 2_500, "release gate wait must be bounded");
            assertFalse(upload.isDone(), "the simulated remote upload should still be stalled");
            assertFalse(filesystem.isSandboxRunning());
        } finally {
            sandbox.allowUploadToFinish.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void reacquireCreatesNewGateGenerationForSameSandboxObject() {
        BlockingSandbox sandbox = new BlockingSandbox();
        PinnedSandboxFilesystem oldFilesystem = new PinnedSandboxFilesystem(sandbox);
        PinnedSandboxFilesystem.markSandboxReleased(sandbox);

        PinnedSandboxFilesystem.markSandboxAcquired(sandbox);
        PinnedSandboxFilesystem newFilesystem = new PinnedSandboxFilesystem(sandbox);

        assertFalse(oldFilesystem.isSandboxRunning());
        assertTrue(newFilesystem.isSandboxRunning());
    }

    @Test
    void distinctEqualSandboxObjectsUseIndependentGates() {
        EqualSandbox first = new EqualSandbox();
        EqualSandbox second = new EqualSandbox();
        PinnedSandboxFilesystem firstFilesystem = new PinnedSandboxFilesystem(first);
        PinnedSandboxFilesystem secondFilesystem = new PinnedSandboxFilesystem(second);

        PinnedSandboxFilesystem.markSandboxReleased(first);

        assertFalse(firstFilesystem.isSandboxRunning());
        assertTrue(secondFilesystem.isSandboxRunning());
    }

    private static class BlockingSandbox implements Sandbox {
        private final CountDownLatch uploadStarted = new CountDownLatch(1);
        private final CountDownLatch allowUploadToFinish = new CountDownLatch(1);

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
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream persistWorkspace() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void hydrateWorkspace(InputStream archive) throws InterruptedException {
            uploadStarted.countDown();
            allowUploadToFinish.await();
        }
    }

    private static final class EqualSandbox extends BlockingSandbox {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualSandbox;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
