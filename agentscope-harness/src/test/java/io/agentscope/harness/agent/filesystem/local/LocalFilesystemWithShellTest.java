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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemWithShellTest {

    @Test
    void outputCharset_usesNativeEncodingOnWindows() {
        assertEquals(
                Charset.forName("windows-1252"),
                LocalFilesystemWithShell.outputCharset("Windows 10", "windows-1252"));
    }

    @Test
    void outputCharset_usesUtf8OnNonWindowsSystems() {
        assertEquals(StandardCharsets.UTF_8, LocalFilesystemWithShell.outputCharset("Linux"));
    }

    @Test
    void outputCharset_fallsBackToDefaultWhenWindowsNativeEncodingIsUnavailable() {
        assertEquals(
                Charset.defaultCharset(),
                LocalFilesystemWithShell.outputCharset("Windows 10", null));
    }

    @Test
    void constructor_rejectsNonPositiveMaxOutputBytes(@TempDir Path tempDir) {
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new LocalFilesystemWithShell(tempDir, false, 60, 0, null, false));

        assertTrue(error.getMessage().contains("maxOutputBytes must be positive"));
    }

    @Test
    void drainStream_exactLimitDoesNotTruncate() {
        byte[] input = "01234567".getBytes(StandardCharsets.UTF_8);

        LocalFilesystemWithShell.BoundedCapture capture =
                LocalFilesystemWithShell.drainStream(new ByteArrayInputStream(input), input.length);

        assertEquals(input.length, capture.size());
        assertFalse(capture.truncated());
        assertEquals("01234567", capture.asString(StandardCharsets.UTF_8));
    }

    @Test
    void drainStream_discardsBeyondLimitAndStillReachesEof() {
        ByteArrayInputStream input =
                new ByteArrayInputStream("012345678".getBytes(StandardCharsets.UTF_8));

        LocalFilesystemWithShell.BoundedCapture capture =
                LocalFilesystemWithShell.drainStream(input, 8);

        assertEquals(8, capture.size());
        assertTrue(capture.truncated());
        assertEquals("01234567", capture.asString(StandardCharsets.UTF_8));
        assertEquals(0, input.available(), "overflow bytes must still be drained");
    }

    @Test
    void execute_outputLargerThanOsPipeBufferCompletesWithoutDeadlock(@TempDir Path tempDir) {
        // ~68-72 KB of stdout: beyond the OS pipe buffer (~4 KB on Windows, 64 KB on Linux),
        // below the default maxOutputBytes cap. Before stdout/stderr were drained concurrently
        // with waitFor, this deadlocked and was misreported as a timeout (exit 124).
        int lines = 4000;
        String payload = "0123456789abcdef"; // 16 chars per line
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command =
                windows
                        ? "for /l %i in (1,1," + lines + ") do @echo " + payload
                        : "i=0; while [ \"$i\" -lt "
                                + lines
                                + " ]; do echo "
                                + payload
                                + "; i=$((i+1)); done";

        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), "unexpected exit code, output: " + resp.output());
        assertFalse(resp.truncated());
        assertEquals(lines, resp.output().split(payload, -1).length - 1);
    }

    @Test
    void execute_drainsLargeStdoutAndStderrConcurrently(@TempDir Path tempDir) {
        int lines = 4000;
        int maxOutputBytes = 250_000;
        String stdoutPayload = "stdout-0123456789abcd";
        String stderrPayload = "stderr-0123456789abcd";
        String command =
                isWindows()
                        ? "for /l %i in (1,1,"
                                + lines
                                + ") do @(echo "
                                + stdoutPayload
                                + "& echo "
                                + stderrPayload
                                + " 1>&2)"
                        : "i=0; while [ \"$i\" -lt "
                                + lines
                                + " ]; do echo "
                                + stdoutPayload
                                + "; echo "
                                + stderrPayload
                                + " >&2; i=$((i+1)); done";

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, maxOutputBytes, null, false);
        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), "unexpected exit code, output: " + resp.output());
        assertFalse(resp.truncated());
        assertEquals(lines, resp.output().split(stdoutPayload, -1).length - 1);
        assertEquals(lines, resp.output().split(stderrPayload, -1).length - 1);
    }

    @Test
    void execute_boundsCaptureWhileContinuingToDrain(@TempDir Path tempDir) {
        int lines = 4000;
        int maxOutputBytes = 1024;
        String payload = "0123456789abcdef";
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String command =
                windows
                        ? "for /l %i in (1,1," + lines + ") do @echo " + payload
                        : "i=0; while [ \"$i\" -lt "
                                + lines
                                + " ]; do echo "
                                + payload
                                + "; i=$((i+1)); done";

        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, maxOutputBytes, null, false);
        ExecuteResponse resp = fs.execute(null, command, 60);

        assertEquals(0, resp.exitCode(), "unexpected exit code, output: " + resp.output());
        assertTrue(resp.truncated());
        assertTrue(resp.output().contains("Output truncated at 1024 bytes."));
        assertTrue(resp.output().length() < maxOutputBytes + 100);
    }

    @Test
    void awaitCapture_closesAndCancelsReaderAtSharedDeadline() throws Exception {
        CloseAwareBlockingInputStream input = new CloseAwareBlockingInputStream();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<LocalFilesystemWithShell.BoundedCapture> future =
                executor.submit(() -> LocalFilesystemWithShell.drainStream(input, 8));

        try {
            assertTrue(input.awaitReadStarted(1, TimeUnit.SECONDS));

            LocalFilesystemWithShell.BoundedCapture capture =
                    LocalFilesystemWithShell.awaitCapture(
                            future, input, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25));

            assertNull(capture);
            assertTrue(input.closed());
            assertTrue(future.isCancelled());
        } finally {
            input.release();
            executor.shutdownNow();
        }
    }

    @Test
    void execute_timeoutCleansUpAndPreservesTimeoutResponse(@TempDir Path tempDir)
            throws Exception {
        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        Path childPidFile = tempDir.resolve("timeout-child.pid");
        String command =
                isWindows()
                        ? longRunningShellBuiltin()
                        : "sleep 30 & echo $! > timeout-child.pid; wait";
        long childPid = -1;

        try {
            ExecuteResponse resp = fs.execute(null, command, 1);

            assertEquals(124, resp.exitCode());
            assertFalse(resp.truncated());
            assertTrue(resp.output().contains("timed out after 1 seconds (custom timeout)"));
            if (!isWindows()) {
                childPid = awaitPid(childPidFile);
                assertFalse(
                        awaitProcessAlive(childPid, 2000),
                        "timeout must terminate a snapshotted command descendant");
            }
        } finally {
            destroyProcess(childPid);
        }
    }

    @Test
    void execute_interruptionCleansUpAndRestoresInterruptFlag(@TempDir Path tempDir)
            throws Exception {
        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        Path childPidFile = tempDir.resolve("interrupted-child.pid");
        String command =
                isWindows()
                        ? longRunningShellBuiltin()
                        : "sleep 30 & echo $! > interrupted-child.pid; wait";
        CountDownLatch executeStarted = new CountDownLatch(1);
        AtomicReference<ExecuteResponse> response = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Long> childPid = new AtomicReference<>(-1L);
        Thread executeThread =
                new Thread(
                        () -> {
                            executeStarted.countDown();
                            response.set(fs.execute(null, command, 60));
                            interrupted.set(Thread.currentThread().isInterrupted());
                        });

        try {
            executeThread.start();
            assertTrue(executeStarted.await(1, TimeUnit.SECONDS));
            if (isWindows()) {
                Thread.sleep(200);
            } else {
                childPid.set(awaitPid(childPidFile));
            }
            executeThread.interrupt();
            executeThread.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(executeThread.isAlive(), "execute must return promptly after interruption");
            assertNotNull(response.get());
            assertEquals(1, response.get().exitCode());
            assertTrue(response.get().output().contains("InterruptedException"));
            assertTrue(interrupted.get(), "execute must restore the caller's interrupt flag");
            if (!isWindows()) {
                assertFalse(
                        awaitProcessAlive(childPid.get(), 2000),
                        "interruption must terminate a snapshotted command descendant");
            }
        } finally {
            if (executeThread.isAlive()) {
                executeThread.interrupt();
                executeThread.join(TimeUnit.SECONDS.toMillis(5));
            }
            destroyProcess(childPid.get());
        }
    }

    @Test
    void execute_shellExitWithBackgroundPipeHolderReturnsPromptly(@TempDir Path tempDir)
            throws Exception {
        assumeFalse(isWindows(), "POSIX shell behavior is required for this regression test");
        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        Path childPidFile = tempDir.resolve("background-child.pid");
        long childPid = -1;

        try {
            long startedNanos = System.nanoTime();
            ExecuteResponse resp =
                    fs.execute(null, "sleep 30 & echo $! > background-child.pid", 10);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            childPid = awaitPid(childPidFile);

            assertTrue(
                    resp.exitCode() == 0 || resp.exitCode() == 1,
                    "JDK may either close inherited pipes when the shell exits or wait for the"
                            + " capture deadline");
            assertFalse(resp.truncated());
            if (resp.exitCode() == 1) {
                assertTrue(resp.output().contains("output capture did not complete"));
            }
            assertTrue(elapsedMillis < 10_000, "capture deadline must beat command timeout");
        } finally {
            destroyProcess(childPid);
        }
    }

    private static String longRunningShellBuiltin() {
        return isWindows() ? "for /l %i in (1,0,2) do @ver >nul" : "while :; do :; done";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static long awaitPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(Files.exists(pidFile), "child PID file was not created");
        return Long.parseLong(Files.readString(pidFile).trim());
    }

    private static boolean awaitProcessAlive(long pid, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        boolean alive;
        do {
            alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return false;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return true;
    }

    private static void destroyProcess(long pid) {
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(process -> process.destroyForcibly());
    }

    private static final class CloseAwareBlockingInputStream extends InputStream {
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public int read() throws IOException {
            readStarted.countDown();
            try {
                release.await();
                return -1;
            } catch (InterruptedException e) {
                if (closed.get()) {
                    return -1;
                }
                Thread.currentThread().interrupt();
                throw new IOException("reader interrupted", e);
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private boolean awaitReadStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return readStarted.await(timeout, unit);
        }

        private boolean closed() {
            return closed.get();
        }

        private void release() {
            release.countDown();
        }
    }
}
