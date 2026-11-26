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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.ProjectAwareOverlay;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
        assertFalse(Files.exists(tempDir.resolve("large_tool_results")));
    }

    @Test
    void execute_keepsHeadAndDiagnosticTailAndSavesCompleteCombinedOutput(@TempDir Path tempDir)
            throws IOException {
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, 256, null, false);
        ExecuteResponse response = fs.execute(null, largeOutputCommand(7), 60);

        assertEquals(7, response.exitCode());
        assertFalse(response.timedOut());
        assertTrue(response.truncated());
        assertTrue(response.output().startsWith("stdout-head"));
        assertTrue(response.output().contains("UTF-8 bytes omitted"));
        assertTrue(response.output().contains("stdout-tail"));
        assertTrue(response.output().contains("[stderr] fatal-tail"));
        assertTrue(response.output().endsWith("Exit code: 7"));
        String path = savedPath(response);
        ReadResult read = fs.read(null, path, 0, 0);
        assertTrue(read.isSuccess(), read.error());
        String complete = read.fileData().content();
        assertEquals(80, complete.split("0123456789abcdef", -1).length - 1);
        assertTrue(complete.contains("stdout-tail"));
        assertTrue(complete.endsWith("[stderr] fatal-tail"));
        assertFalse(complete.contains("bytes omitted"));
        assertEquals(complete, Files.readString(tempDir.resolve(path)));

        ExecuteResponse second = fs.execute(null, largeOutputCommand(0), 60);
        assertEquals(0, second.exitCode());
        assertFalse(path.equals(savedPath(second)), "each invocation needs a unique artifact");
        assertTrue(
                Files.isRegularFile(tempDir.resolve(path)), "older output must not be overwritten");
    }

    @Test
    void previewOutput_usesUtf8BytesWithoutSplittingCodePoints() {
        String complete = "é汉\uD840\uDC00".repeat(12);
        byte[] bytes = complete.getBytes(StandardCharsets.UTF_8);
        Pattern marker = Pattern.compile("\n\n\\.\\.\\. (\\d+) UTF-8 bytes omitted \\.\\.\\.\n\n");
        for (int budget = 0; budget < bytes.length; budget++) {
            String preview = LocalFilesystemWithShell.previewOutput(bytes, budget);
            Matcher match = marker.matcher(preview);
            assertTrue(match.find(), "missing omission marker for budget " + budget);
            String head = preview.substring(0, match.start());
            String tail = preview.substring(match.end());
            int retained = (head + tail).getBytes(StandardCharsets.UTF_8).length;
            assertTrue(retained <= budget, "retained bytes exceed budget " + budget);
            assertEquals(bytes.length - retained, Integer.parseInt(match.group(1)));
            assertTrue(complete.startsWith(head));
            assertTrue(complete.endsWith(tail));
            assertFalse(preview.contains("\uFFFD"), "preview split a UTF-8 code point");
        }
        assertEquals(complete, LocalFilesystemWithShell.previewOutput(bytes, bytes.length));
    }

    @Test
    void execute_truncatesMultibyteOutputByBytesNotStringLength(@TempDir Path tempDir) {
        assumeFalse(isWindows(), "Windows shell output uses the native encoding instead of UTF-8");
        String complete = "é".repeat(40); // 40 Java characters, 80 UTF-8 bytes.
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, 60, null, false);
        assertTrue(fs.write(null, "payload.txt", complete).isSuccess());

        ExecuteResponse response = fs.execute(null, "cat payload.txt", 60);

        assertEquals(0, response.exitCode());
        assertTrue(response.truncated());
        assertTrue(response.output().startsWith("é".repeat(15)));
        assertTrue(response.output().contains("20 UTF-8 bytes omitted"));
        assertEquals(complete, fs.read(null, savedPath(response), 0, 0).fileData().content());

        LocalFilesystemWithShell exactBudget =
                new LocalFilesystemWithShell(tempDir, false, 60, 80, null, false);
        ExecuteResponse exact = exactBudget.execute(null, "cat payload.txt", 60);
        assertFalse(exact.truncated());
        assertEquals(complete, exact.output());
    }

    @Test
    void execute_handlesZeroAndOneByteBudgets(@TempDir Path tempDir) {
        for (int budget : new int[] {0, 1}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, budget, null, false);
            ExecuteResponse response = fs.execute(null, "echo hello", 60);
            assertEquals(0, response.exitCode());
            assertTrue(response.truncated());
            assertTrue(response.output().contains("UTF-8 bytes omitted"));
            ReadResult read = fs.read(null, savedPath(response), 0, 0);
            assertTrue(read.isSuccess(), read.error());
            assertTrue(read.fileData().content().contains("hello"));
        }
    }

    @Test
    void execute_saveFailureDoesNotChangeExitCodeOrRepeatCommand(@TempDir Path tempDir)
            throws IOException {
        for (int exitCode : new int[] {0, 7}) {
            AtomicInteger attempts = new AtomicInteger();
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, 256, null, false) {
                        @Override
                        public WriteResult write(RuntimeContext rc, String path, String content) {
                            attempts.incrementAndGet();
                            assertTrue(path.startsWith("large_tool_results/execute/"));
                            assertEquals(
                                    "",
                                    content,
                                    "archive authorization must not materialize output");
                            return WriteResult.fail("storage unavailable");
                        }
                    };
            String invocationFile = "invocations-" + exitCode + ".txt";
            String command =
                    "echo invocation >> "
                            + invocationFile
                            + (isWindows() ? " & " : "; ")
                            + largeOutputCommand(exitCode);
            ExecuteResponse response = fs.execute(null, command, 60);

            assertEquals(exitCode, response.exitCode());
            assertEquals(exitCode == 0, response.isSuccess());
            assertTrue(response.truncated());
            assertTrue(response.output().contains("fatal-tail"));
            assertTrue(response.output().contains("Full output unavailable"));
            assertFalse(response.output().contains("read_file"));
            assertFalse(response.output().contains("large_tool_results"));
            assertEquals(1, attempts.get());
            assertEquals(1, Files.readAllLines(tempDir.resolve(invocationFile)).size());
        }
    }

    @Test
    void execute_artifactIsReadableThroughOverlayInOwnNamespaceNotShellCwd(@TempDir Path tempDir)
            throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        NamespaceFactory namespace = rc -> List.of("sessions", rc.getSessionId());
        RuntimeContext rt = RuntimeContext.builder().sessionId("own").build();
        RuntimeContext other = RuntimeContext.builder().sessionId("other").build();
        for (LocalFsMode mode : LocalFsMode.values()) {
            LocalFilesystemWithShell upper =
                    new LocalFilesystemWithShell(
                            workspace,
                            mode,
                            PathPolicy.of(project),
                            60,
                            256,
                            null,
                            false,
                            namespace,
                            project);
            upper.namespaceBoundary(true);
            AbstractSandboxFilesystem overlay =
                    new ProjectAwareOverlay(
                            upper,
                            new LocalFilesystem(project),
                            new LocalFilesystem(project),
                            workspace,
                            namespace);

            ExecuteResponse response = overlay.execute(rt, largeOutputCommand(7), 60);
            String path = savedPath(response);
            ReadResult read = overlay.read(rt, path, 0, 0);
            assertEquals(7, response.exitCode());
            assertTrue(read.isSuccess(), read.error());
            assertEquals(80, read.fileData().content().split("0123456789abcdef", -1).length - 1);
            assertTrue(Files.isRegularFile(workspace.resolve("sessions/own").resolve(path)));
            assertFalse(Files.exists(project.resolve(path)), "artifact must not use shellCwd");
            assertFalse(overlay.read(other, path, 0, 0).isSuccess());
        }
    }

    @Test
    void execute_namespaceBoundaryDenialDoesNotChangeOutcome(@TempDir Path tempDir)
            throws IOException {
        assumeFalse(isWindows(), "test uses Unix symlinks without Windows privilege requirements");
        Path own = Files.createDirectories(tempDir.resolve("sessions/own"));
        Path other = Files.createDirectories(tempDir.resolve("sessions/other"));
        Files.createSymbolicLink(own.resolve("large_tool_results"), other);
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(
                        tempDir,
                        LocalFsMode.ROOTED,
                        PathPolicy.empty(),
                        60,
                        256,
                        null,
                        false,
                        rc -> List.of("sessions", rc.getSessionId()),
                        null);
        fs.namespaceBoundary(true);

        ExecuteResponse response =
                fs.execute(
                        RuntimeContext.builder().sessionId("own").build(),
                        largeOutputCommand(0),
                        60);

        assertEquals(0, response.exitCode());
        assertTrue(response.truncated());
        assertTrue(response.output().contains("Full output unavailable"));
        assertFalse(response.output().contains("read_file"));
        try (var entries = Files.list(other)) {
            assertEquals(0, entries.count(), "save must not bypass the namespace boundary");
        }
    }

    @Test
    void execute_timeoutKeepsPartialOutputAndLabelsSavedCapture(@TempDir Path tempDir) {
        assumeFalse(isWindows(), "exec sleep ensures the timed-out process has no surviving child");
        for (int budget : new int[] {4096, 256}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 1, budget, null, false);
            String command =
                    "echo partial-head; i=0; while [ \"$i\" -lt 80 ]; do echo 0123456789abcdef;"
                            + " i=$((i+1)); done; echo partial-tail; echo timeout-diagnostic >&2;"
                            + " exec sleep 30";
            ExecuteResponse response = fs.execute(null, command, 1);

            assertEquals(124, response.exitCode());
            assertTrue(response.timedOut());
            assertFalse(response.isSuccess());
            assertTrue(
                    response.output().contains("Only partial output was captured before timeout"));
            assertTrue(response.output().contains("partial-head"));
            assertTrue(response.output().contains("partial-tail"));
            assertTrue(response.output().contains("[stderr] timeout-diagnostic"));
            assertEquals(budget == 256, response.truncated());
            if (response.truncated()) {
                String saved = fs.read(null, savedPath(response), 0, 0).fileData().content();
                assertTrue(saved.startsWith("Only partial output was captured before timeout"));
                assertEquals(80, saved.split("0123456789abcdef", -1).length - 1);
                assertTrue(saved.contains("[stderr] timeout-diagnostic"));
            }
        }
    }

    @Test
    void execute_giantSingleLinesHaveBoundedPreviewAndCompleteStreamingRecovery(
            @TempDir Path tempDir) throws Exception {
        int budget = 256;
        int blocks = 2048;
        LocalFilesystemWithShell fs =
                new LocalFilesystemWithShell(tempDir, false, 60, budget, null, false);
        ExecuteResponse first = fs.execute(null, fixtureCommand("large", "M", "" + blocks), 60);
        ExecuteResponse same = fs.execute(null, fixtureCommand("large", "M", "" + blocks), 60);
        ExecuteResponse changed = fs.execute(null, fixtureCommand("large", "N", "" + blocks), 60);
        assertEquals(0, first.exitCode(), first.output());
        assertTrue(first.truncated());
        assertTrue(first.output().startsWith("stdout-head"));
        assertTrue(first.output().contains("stderr-tail"));
        assertNotNull(first.outputDigest());
        assertEquals(first.outputDigest(), same.outputDigest());
        assertNotEquals(
                first.outputDigest(),
                changed.outputDigest(),
                "a hidden middle-byte change must affect the raw-stream fingerprint");
        assertNotEquals(savedPath(first), savedPath(same));
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update("stdout-head".getBytes(StandardCharsets.UTF_8));
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < blocks; i++) {
            expected.update(chunk);
            if (i == blocks / 2) {
                expected.update((byte) 'M');
            }
        }
        expected.update("stdout-tail\n\n[stderr] stderr-head".getBytes(StandardCharsets.UTF_8));
        Arrays.fill(chunk, (byte) 'y');
        for (int i = 0; i < blocks; i++) {
            expected.update(chunk);
        }
        expected.update("stderr-tail".getBytes(StandardCharsets.UTF_8));
        Path artifact = tempDir.resolve(savedPath(first));
        MessageDigest actual = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(artifact), actual)) {
            while (in.read(chunk) != -1) {
                // Recover and verify the whole artifact without a full-output test allocation.
            }
        }
        assertEquals(
                HexFormat.of().formatHex(expected.digest()),
                HexFormat.of().formatHex(actual.digest()));
        assertNoSpools(tempDir);
    }

    @Test
    void boundedCaptureDoesNotGrowWithTotalBytesOrSplitUtf8() throws Exception {
        Class<?> type = Class.forName(LocalFilesystemWithShell.class.getName() + "$BoundedOutput");
        var constructor = type.getDeclaredConstructor(int.class);
        var append = type.getDeclaredMethod("append", byte[].class);
        var preview = type.getDeclaredMethod("preview");
        Field head = type.getDeclaredField("head");
        Field tail = type.getDeclaredField("tail");
        Field size = type.getDeclaredField("size");
        constructor.setAccessible(true);
        append.setAccessible(true);
        preview.setAccessible(true);
        head.setAccessible(true);
        tail.setAccessible(true);
        size.setAccessible(true);
        byte[] complete = "é汉\uD840\uDC00".repeat(12).getBytes(StandardCharsets.UTF_8);
        for (int budget = 0; budget <= complete.length; budget++) {
            Object capture = constructor.newInstance(budget);
            for (byte b : complete) {
                append.invoke(capture, (Object) new byte[] {b});
            }
            assertEquals(
                    LocalFilesystemWithShell.previewOutput(complete, budget),
                    preview.invoke(capture),
                    "budget " + budget);
        }
        int budget = 256;
        Object capture = constructor.newInstance(budget);
        byte[] chunk = new byte[8192];
        Arrays.fill(chunk, (byte) 'x');
        for (int i = 0; i < 8192; i++) {
            append.invoke(capture, (Object) chunk);
        }
        assertEquals((long) chunk.length * 8192, size.getLong(capture));
        assertEquals(budget, ((byte[]) head.get(capture)).length);
        assertEquals(budget, ((byte[]) tail.get(capture)).length);
        assertTrue(((String) preview.invoke(capture)).contains("UTF-8 bytes omitted"));
    }

    @Test
    void execute_streamsUtf8AndStderrStripAndBlankLineFormattingAcrossChunks(@TempDir Path tempDir)
            throws Exception {
        assumeTrue(
                LocalFilesystemWithShell.outputCharset(System.getProperty("os.name"))
                        .equals(StandardCharsets.UTF_8),
                "fixture deliberately writes UTF-8 bytes");
        String stdout = "x".repeat(8191) + "汉\uD83D\uDE00é".repeat(8192);
        String stderr = "\u2003 \n first\r\n\n汉\uD83D\uDE00last \u2003\r\n";
        String combined = stdout + "\n[stderr] first\r\n[stderr] \n[stderr] 汉\uD83D\uDE00last";
        for (int budget : new int[] {256, combined.getBytes(StandardCharsets.UTF_8).length}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, budget, null, false);
            ExecuteResponse response = fs.execute(null, fixtureCommand("unicode"), 60);
            assertEquals(0, response.exitCode(), response.output());
            assertFalse(response.output().contains("\uFFFD"));
            if (response.truncated()) {
                assertTrue(
                        response.output()
                                .startsWith(
                                        LocalFilesystemWithShell.previewOutput(
                                                combined.getBytes(StandardCharsets.UTF_8),
                                                budget)));
                assertEquals(combined, Files.readString(tempDir.resolve(savedPath(response))));
            } else {
                assertEquals(combined, response.output());
            }
            MessageDigest rawOut = MessageDigest.getInstance("SHA-256");
            MessageDigest rawErr = MessageDigest.getInstance("SHA-256");
            MessageDigest joined = MessageDigest.getInstance("SHA-256");
            joined.update(rawOut.digest(stdout.getBytes(StandardCharsets.UTF_8)));
            joined.update(rawErr.digest(stderr.getBytes(StandardCharsets.UTF_8)));
            assertEquals(HexFormat.of().formatHex(joined.digest()), response.outputDigest());
        }
        assertNoSpools(tempDir);
    }

    @Test
    void execute_stripsHugeWhitespaceRunsWithoutRetainingThem(@TempDir Path tempDir)
            throws Exception {
        for (int budget : new int[] {256, 4}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, budget, null, false);
            ExecuteResponse response = fs.execute(null, fixtureCommand("whitespace"), 60);
            assertEquals(0, response.exitCode(), response.output());
            assertNotNull(response.outputDigest());
            if (response.truncated()) {
                assertEquals(
                        "[stderr] diag", Files.readString(tempDir.resolve(savedPath(response))));
            } else {
                assertEquals("[stderr] diag", response.output());
            }
            assertNoSpools(tempDir);
        }
    }

    @Test
    void execute_spoolOrArchiveStreamFailureKeepsRealExitAndBoundedTail(@TempDir Path tempDir)
            throws Exception {
        for (boolean failSpool : new boolean[] {true, false}) {
            LocalFilesystemWithShell fs =
                    new LocalFilesystemWithShell(tempDir, false, 60, 256, null, false) {
                        @Override
                        protected Path resolvePath(RuntimeContext rc, String key) {
                            if (failSpool && key.startsWith(".shell-capture-")) {
                                throw new SecurityException("spool unavailable");
                            }
                            return super.resolvePath(rc, key);
                        }

                        @Override
                        public WriteResult write(RuntimeContext rc, String key, String content) {
                            WriteResult result = super.write(rc, key, content);
                            if (!failSpool && result.isSuccess()) {
                                try {
                                    Path path = resolvePath(rc, key);
                                    Files.delete(path);
                                    Files.createDirectory(
                                            path); // Opening the archive stream fails.
                                } catch (IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            return result;
                        }
                    };
            ExecuteResponse response = fs.execute(null, largeOutputCommand(7), 60);
            assertEquals(7, response.exitCode());
            assertTrue(response.truncated());
            assertTrue(response.output().contains("fatal-tail"));
            assertTrue(response.output().contains("Full output unavailable"));
            assertFalse(response.output().contains("read_file"));
            assertNotNull(response.outputDigest());
            assertNoSpools(tempDir);
            try (var files = Files.walk(tempDir)) {
                assertFalse(
                        files.anyMatch(path -> path.toString().endsWith(".txt")),
                        "failed archives must not survive as apparently complete artifacts");
            }
        }
    }

    @Test
    void execute_interruptTerminatesLocalProcessTreeAndDrainers(@TempDir Path tempDir)
            throws Exception {
        assumeProcessHandles();
        Set<String> existingDrainers = drainerNames();
        Path ready = tempDir.resolve("ready");
        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        AtomicReference<ExecuteResponse> response = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker =
                new Thread(
                        () -> {
                            response.set(fs.execute(null, treeCommand(ready), 60));
                            interrupted.set(Thread.currentThread().isInterrupted());
                        });
        try (var watch = tempDir.getFileSystem().newWatchService()) {
            tempDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
            worker.start();
            awaitReady(ready, watch);
            List<Long> pids = Files.readAllLines(ready).stream().map(Long::parseLong).toList();
            try {
                worker.interrupt();
                worker.join(TimeUnit.SECONDS.toMillis(60));
                assertFalse(worker.isAlive(), "interrupted execute must finish bounded cleanup");
                assertTrue(interrupted.get(), "caller interrupt flag must be restored");
                assertEquals(1, response.get().exitCode());
                for (long pid : pids) {
                    assertFalse(
                            ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                            "local fixture process survived interruption: " + pid);
                }
                assertTrue(
                        existingDrainers.containsAll(drainerNames()),
                        "this execution left a drainer thread alive");
                assertNoSpools(tempDir);
            } finally {
                worker.interrupt();
                for (long pid : pids) {
                    ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
        } finally {
            worker.interrupt();
            worker.join(TimeUnit.SECONDS.toMillis(60));
        }
    }

    @Test
    void execute_timeoutTerminatesLocalParentAndChild(@TempDir Path tempDir) throws Exception {
        assumeProcessHandles();
        Set<String> existingDrainers = drainerNames();
        Path ready = tempDir.resolve("ready");
        ExecuteResponse response =
                new LocalFilesystemWithShell(tempDir).execute(null, treeCommand(ready), 5);
        assertEquals(124, response.exitCode(), response.output());
        assertTrue(response.timedOut());
        assertTrue(Files.exists(ready), "fixture must have created a child before the timeout");
        List<Long> pids = Files.readAllLines(ready).stream().map(Long::parseLong).toList();
        try {
            assertProcessesStopped(pids, existingDrainers);
            assertNoSpools(tempDir);
        } finally {
            for (long pid : pids) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void execute_reactorCancellationInterruptsAndCleansLocalTree(@TempDir Path tempDir)
            throws Exception {
        assumeProcessHandles();
        Set<String> existingDrainers = drainerNames();
        Path ready = tempDir.resolve("ready");
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        LocalFilesystemWithShell fs = new LocalFilesystemWithShell(tempDir);
        var scheduler = reactor.core.scheduler.Schedulers.newSingle("shell-cancellation-test");
        reactor.core.Disposable subscription = null;
        List<Long> pids = List.of();
        try (var watch = tempDir.getFileSystem().newWatchService()) {
            tempDir.register(watch, StandardWatchEventKinds.ENTRY_CREATE);
            subscription =
                    reactor.core.publisher.Mono.fromCallable(
                                    () -> {
                                        try {
                                            return fs.execute(null, treeCommand(ready), 60);
                                        } finally {
                                            interrupted.set(Thread.currentThread().isInterrupted());
                                            cleaned.countDown();
                                        }
                                    })
                            .subscribeOn(scheduler)
                            .subscribe();
            awaitReady(ready, watch);
            pids = Files.readAllLines(ready).stream().map(Long::parseLong).toList();
            subscription.dispose();
            assertTrue(cleaned.await(60, TimeUnit.SECONDS), "cancelled execute did not clean up");
            assertTrue(interrupted.get(), "cancellation must preserve the worker interrupt flag");
            assertProcessesStopped(pids, existingDrainers);
            assertNoSpools(tempDir);
        } finally {
            if (subscription != null) {
                subscription.dispose();
            }
            for (long pid : pids) {
                ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            }
            scheduler.dispose();
        }
    }

    private static void assertProcessesStopped(List<Long> pids, Set<String> existingDrainers) {
        for (long pid : pids) {
            assertNotEquals(ProcessHandle.current().pid(), pid, "fixture PID must not be test JVM");
            assertFalse(
                    ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "local fixture process survived cleanup: " + pid);
        }
        assertTrue(
                existingDrainers.containsAll(drainerNames()),
                "this execution left a drainer thread alive");
    }

    private static String treeCommand(Path ready) {
        // A following shell command prevents Unix sh from replacing itself with the fixture JVM.
        return fixtureCommand("tree", ready.toString()) + (isWindows() ? "" : "; :");
    }

    private static void assumeProcessHandles() {
        try (var descendants = ProcessHandle.current().descendants()) {
            descendants.count();
        } catch (UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "platform cannot enumerate/terminate local process handles: " + e);
        }
    }

    private static void awaitReady(Path ready, java.nio.file.WatchService watch)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(ready)) {
            long remaining = deadline - System.nanoTime();
            assertTrue(remaining > 0, "local process fixture did not become ready");
            var event = watch.poll(remaining, TimeUnit.NANOSECONDS);
            assertNotNull(event, "local process fixture did not become ready");
            event.pollEvents();
            event.reset();
        }
    }

    private static Set<String> drainerNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("agentscope-shell-drain-"))
                .collect(Collectors.toSet());
    }

    private static void assertNoSpools(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            assertFalse(
                    paths.anyMatch(
                            path -> path.getFileName().toString().startsWith(".shell-capture-")),
                    "temporary spools must be cleaned up");
        }
    }

    private static String fixtureCommand(String... args) {
        try {
            String java =
                    Path.of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    isWindows() ? "java.exe" : "java")
                            .toString();
            String classpath =
                    Path.of(
                                    ProcessFixture.class
                                            .getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toString();
            String command =
                    shellQuote(java)
                            + " -cp "
                            + shellQuote(classpath)
                            + " "
                            + shellQuote(ProcessFixture.class.getName());
            for (String arg : args) {
                command += " " + shellQuote(arg);
            }
            // cmd /c strips the outer pair, leaving the executable and argument quotes intact.
            return isWindows() ? '"' + command + '"' : command;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String shellQuote(String value) {
        return isWindows()
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : "'" + value.replace("'", "'\\''") + "'";
    }

    /** A JDK-only local fixture, including on Windows; no bash or provider dependency. */
    public static class ProcessFixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("large")) {
                int blocks = Integer.parseInt(args[2]);
                byte[] chunk = new byte[8192];
                Arrays.fill(chunk, (byte) 'x');
                System.out.write("stdout-head".getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < blocks; i++) {
                    System.out.write(chunk);
                    if (i == blocks / 2) {
                        System.out.write(args[1].getBytes(StandardCharsets.UTF_8));
                    }
                }
                System.out.write("stdout-tail\n".getBytes(StandardCharsets.UTF_8));
                System.out.flush();
                System.err.write(" \n stderr-head".getBytes(StandardCharsets.UTF_8));
                Arrays.fill(chunk, (byte) 'y');
                for (int i = 0; i < blocks; i++) {
                    System.err.write(chunk);
                }
                System.err.write("stderr-tail \r\n".getBytes(StandardCharsets.UTF_8));
            } else if (args[0].equals("unicode")) {
                System.out.write(
                        ("x".repeat(8191) + "汉\uD83D\uDE00é".repeat(8192))
                                .getBytes(StandardCharsets.UTF_8));
                System.err.write(
                        "\u2003 \n first\r\n\n汉\uD83D\uDE00last \u2003\r\n"
                                .getBytes(StandardCharsets.UTF_8));
            } else if (args[0].equals("whitespace")) {
                byte[] chunk = new byte[8192];
                Arrays.fill(chunk, (byte) ' ');
                for (int i = 0; i < 2048; i++) {
                    System.err.write(chunk);
                }
                System.err.write("\n diag".getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 2048; i++) {
                    System.err.write(chunk);
                }
            } else if (args[0].equals("child")) {
                System.out.println("ready");
                System.out.flush();
                new CountDownLatch(1).await();
            } else if (args[0].equals("tree")) {
                String java =
                        Path.of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        System.getProperty("os.name").toLowerCase().contains("win")
                                                ? "java.exe"
                                                : "java")
                                .toString();
                Process child =
                        new ProcessBuilder(
                                        java,
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        ProcessFixture.class.getName(),
                                        "child")
                                .start();
                try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        child.getInputStream(), StandardCharsets.UTF_8))) {
                    if (!"ready".equals(reader.readLine())) {
                        throw new IOException("child did not start");
                    }
                    Path ready = Path.of(args[1]);
                    Path staging = ready.resolveSibling("ready-staging");
                    Files.writeString(
                            staging,
                            ProcessHandle.current().pid()
                                    + "\n"
                                    + child.pid()
                                    + "\n"
                                    + ProcessHandle.current().parent().orElseThrow().pid()
                                    + "\n");
                    Files.move(staging, ready);
                    child.waitFor();
                } finally {
                    child.destroyForcibly();
                }
            }
        }
    }

    private static String savedPath(ExecuteResponse response) {
        Matcher match =
                Pattern.compile("Use `read_file` with path `([^`]+)`").matcher(response.output());
        assertTrue(match.find(), "missing saved-output reference: " + response.output());
        String path = match.group(1);
        assertFalse(Path.of(path).isAbsolute(), "artifact key must remain namespace-relative");
        return path;
    }

    private static String largeOutputCommand(int exitCode) {
        if (isWindows()) {
            return "echo stdout-head & (for /l %i in (1,1,80) do @echo 0123456789abcdef)"
                    + " & echo stdout-tail & echo fatal-tail 1>&2 & exit /b "
                    + exitCode;
        }
        return "echo stdout-head; i=0; while [ \"$i\" -lt 80 ]; do echo 0123456789abcdef;"
                + " i=$((i+1)); done; echo stdout-tail; echo fatal-tail >&2; exit "
                + exitCode;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
