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
package io.agentcore.managed.server.toolworker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.server.runtime.managed.ManagedJournalStore.ToolTask;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.Outcome;
import io.agentcore.managed.server.runtime.managed.ManagedToolExecutor.State;
import io.agentcore.managed.server.runtime.managed.ManagedToolOperations;
import io.agentcore.managed.server.runtime.managed.ManagedTransportException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Real local process, explicit sandbox simulation; no cloud provider credential is needed. */
@Timeout(20)
@EnabledOnOs({OS.LINUX, OS.MAC})
class ManagedSandboxToolExecutorTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ManagedScope scope = new ManagedScope("t", "n", "s");

    private String control(String command) throws Exception {
        Path output = Files.createTempFile("managed-control-test-", ".json");
        var process =
                new ProcessBuilder("sh")
                        .directory(directory.toFile())
                        .redirectOutput(output.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try (var input = process.getOutputStream()) {
            input.write(command.getBytes(StandardCharsets.UTF_8));
        }
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return Files.readString(output);
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    private ManagedSandboxToolExecutor executor() {
        return new ManagedSandboxToolExecutor(mapper, this::control);
    }

    private ToolTask task(String id, String name, Map<String, Object> arguments) throws Exception {
        return new ToolTask(
                id,
                "turn",
                id,
                id,
                name,
                mapper.writeValueAsString(arguments),
                1,
                "ready",
                null,
                null,
                "owner");
    }

    private Outcome terminal(ManagedSandboxToolExecutor executor, ToolTask task) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        Outcome result;
        do {
            result = executor.query(scope, task);
            if (result.terminal()) return result;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("Unconfirmed sandbox execution: " + result);
        return result;
    }

    @Test
    void artifactExportFreezesBinaryBeforeSourceChangesAndSurvivesWorkerReplacement()
            throws Exception {
        byte[] bytes = new byte[2 * 1024 * 1024];
        new Random(97).nextBytes(bytes);
        Files.write(directory.resolve("report.bin"), bytes);
        var task =
                task(
                        "deliverable",
                        ManagedToolOperations.EXPORT_ARTIFACT,
                        Map.of("path", "report.bin"));
        executor().start(scope, task);
        var result = terminal(executor(), task);
        assertEquals(State.SUCCEEDED, result.state());
        var exported = mapper.readTree(result.result());
        assertArrayEquals(bytes, Base64.getDecoder().decode(exported.path("content").asText()));
        assertEquals(bytes.length, exported.path("size").asInt());
        Files.writeString(directory.resolve("report.bin"), "changed");
        executor().start(scope, task);
        assertEquals(result, executor().query(scope, task));
    }

    @Test
    void artifactExportRejectsPrivateAndOutsideFilesAndOversizedContent() throws Exception {
        Files.createDirectories(directory.resolve(".managed-checkpoints"));
        Files.writeString(directory.resolve(".managed-checkpoints/secret"), "private");
        Files.write(directory.resolve("too-large.bin"), new byte[16 * 1024 * 1024 + 1]);
        for (String path :
                List.of(".managed-checkpoints/secret", "../outside", "too-large.bin", "missing")) {
            var task =
                    task(
                            "invalid-" + UUID.randomUUID(),
                            ManagedToolOperations.EXPORT_ARTIFACT,
                            Map.of("path", path));
            executor().start(scope, task);
            var result = terminal(executor(), task);
            assertEquals(State.FAILED, result.state());
            assertEquals(
                    "artifact_export_failed",
                    mapper.readTree(result.result()).path("error").asText());
            assertFalse(mapper.readTree(result.result()).has("content"));
        }
    }

    @Test
    void duplicateStartsAndNewWorkerQueryOriginalProcess() throws Exception {
        var task =
                task(
                        "stable",
                        "sandbox.exec",
                        Map.of(
                                "command",
                                "echo effect >> effects; sleep 1; printf hands-result",
                                "timeout_seconds",
                                5));
        var first = executor();
        first.start(scope, task);
        first.start(scope, task);
        var recovered = executor();
        var result = terminal(recovered, task);
        assertEquals(State.SUCCEEDED, result.state());
        assertEquals("hands-result", mapper.readTree(result.result()).path("output").asText());
        assertEquals(List.of("effect"), Files.readAllLines(directory.resolve("effects")));
    }

    @Test
    void runningOutputCanBeReplayedByAReplacementWithoutSplittingUtf8Characters() throws Exception {
        var task =
                task(
                        "streaming",
                        "sandbox.exec",
                        Map.of(
                                "command",
                                "python3 -c 'import sys;"
                                        + " sys.stdout.buffer.write(b\"a\"*16383+\"世界\".encode());"
                                        + " sys.stdout.flush()';"
                                        + " while [ ! -f release-output ]; do sleep 0.01; done;"
                                        + " printf 'done\\n'",
                                "timeout_seconds",
                                10));
        var first = executor();
        first.start(scope, task);
        var path = directory.resolve(".managed-tools/streaming/output");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ((!Files.exists(path) || Files.size(path) < 16389) && System.nanoTime() < deadline)
            Thread.sleep(10);
        var page = first.output(scope, task, 0);
        try {
            assertFalse(page.terminal());
            assertEquals(16383, page.nextOffset());
            assertEquals(
                    "a".repeat(16383),
                    new String(Base64.getDecoder().decode(page.data()), StandardCharsets.UTF_8));
            // Hold the producer while comparing page metadata across Worker replacement.
            assertEquals(page, executor().output(scope, task, 0));
        } finally {
            Files.writeString(directory.resolve("release-output"), "ready");
        }
        terminal(executor(), task);
        var tail = executor().output(scope, task, page.nextOffset());
        assertTrue(tail.terminal());
        assertEquals(
                "世界done\n",
                new String(Base64.getDecoder().decode(tail.data()), StandardCharsets.UTF_8));
        assertEquals(tail.size(), tail.nextOffset());
    }

    @Test
    void cappedAndLostOutputAreReportedAsExplicitTerminalGaps() throws Exception {
        var task =
                task(
                        "large-output",
                        "sandbox.exec",
                        Map.of(
                                "command",
                                "python3 -c 'import sys;"
                                        + " sys.stdout.buffer.write(b\"x\"*(16*1024*1024+17))'",
                                "timeout_seconds",
                                5));
        var executor = executor();
        executor.start(scope, task);
        var result = terminal(executor, task);
        assertTrue(mapper.readTree(result.result()).path("truncated").asBoolean());
        var cap = executor.output(scope, task, 16L * 1024 * 1024);
        assertEquals("output_limit", cap.gap());
        assertEquals(16L * 1024 * 1024 + 17, cap.nextOffset());
        assertTrue(cap.terminal());
        Files.delete(directory.resolve(".managed-tools/large-output/output"));
        var missing = executor().output(scope, task, 0);
        assertEquals("source_unavailable", missing.gap());
        assertEquals(cap.size(), missing.size());
        assertEquals("", missing.data());
        assertEquals(State.SUCCEEDED, executor().query(scope, task).state());
    }

    @Test
    void fileTransferResultIsPersistedAndDownloadRemainsTyped() throws Exception {
        var executor = executor();
        var upload =
                task(
                        "upload",
                        "fs.upload",
                        Map.of(
                                "path",
                                "nested/file.txt",
                                "content",
                                Base64.getEncoder()
                                        .encodeToString(
                                                "file-content".getBytes(StandardCharsets.UTF_8))));
        executor.start(scope, upload);
        assertEquals(State.SUCCEEDED, terminal(executor, upload).state());
        var download = task("download", "fs.download", Map.of("path", "nested/file.txt"));
        executor.start(scope, download);
        var result = terminal(executor, download);
        assertEquals(
                "file-content",
                new String(
                        Base64.getDecoder()
                                .decode(mapper.readTree(result.result()).path("content").asText()),
                        StandardCharsets.UTF_8));
    }

    @Test
    void cancelIsConfirmedAfterProcessStops() throws Exception {
        var task =
                task(
                        "cancel",
                        "sandbox.exec",
                        Map.of(
                                "command",
                                "sleep 3; echo unexpected > escaped",
                                "timeout_seconds",
                                10));
        var executor = executor();
        executor.start(scope, task);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(directory.resolve(".managed-tools/cancel/process.json"))
                && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(directory.resolve(".managed-tools/cancel/process.json")));
        executor.cancel(scope, task);
        assertEquals(State.CANCELLED, terminal(executor, task).state());
        assertFalse(Files.exists(directory.resolve("escaped")));
    }

    @Test
    void cancellationStopsShellChildrenWritingHeartbeat() throws Exception {
        var task =
                task(
                        "cancel-children",
                        "sandbox.exec",
                        Map.of(
                                "command",
                                "while true; do printf x >> heartbeat; sleep 0.1; done",
                                "timeout_seconds",
                                20));
        var executor = executor();
        executor.start(scope, task);
        Path heartbeat = directory.resolve("heartbeat");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!Files.exists(heartbeat) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(heartbeat));
        executor.cancel(scope, task);
        assertEquals(State.CANCELLED, terminal(executor, task).state());
        long size = Files.size(heartbeat);
        Thread.sleep(400);
        assertEquals(size, Files.size(heartbeat));
    }

    @Test
    void fileTransfersRejectPathsAndSymbolicLinksOutsideTheWorkspace() throws Exception {
        Path outside = Files.createTempFile("managed-transfer-outside-", ".txt");
        try {
            Files.writeString(outside, "outside-content");
            Files.createSymbolicLink(directory.resolve("link"), outside);
            var executor = executor();
            for (String path : List.of("link", outside.toString())) {
                var task =
                        task("outside-" + UUID.randomUUID(), "fs.download", Map.of("path", path));
                executor.start(scope, task);
                var result = terminal(executor, task);
                assertEquals(State.FAILED, result.state());
                assertTrue(result.result().contains("outside the sandbox workspace"));
                assertFalse(result.result().contains("outside-content"));
            }
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void lostStartResponseDoesNotDuplicateEffect() throws Exception {
        var lose = new AtomicBoolean(true);
        var executor =
                new ManagedSandboxToolExecutor(
                        mapper,
                        command -> {
                            String response = control(command);
                            if (lose.compareAndSet(true, false))
                                throw new IllegalStateException("Response lost");
                            return response;
                        });
        var task =
                task(
                        "lost",
                        "sandbox.exec",
                        Map.of("command", "echo once >> effects", "timeout_seconds", 5));
        assertThrows(ManagedTransportException.class, () -> executor.start(scope, task));
        assertEquals(State.SUCCEEDED, terminal(executor(), task).state());
        assertEquals(List.of("once"), Files.readAllLines(directory.resolve("effects")));
    }

    @Test
    void workspaceSnapshotRestoresExactFilesAndReplayDoesNotOverwriteLaterWork() throws Exception {
        byte[] data = new byte[2 * 1024 * 1024];
        new Random(42).nextBytes(data);
        data[0] = 42;
        data[data.length - 1] = 99;
        Files.createDirectories(directory.resolve("notes"));
        Files.write(directory.resolve("notes/data.bin"), data);
        Files.setPosixFilePermissions(
                directory.resolve("notes"), PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.writeString(directory.resolve("kept.txt"), "original");
        var capture = task("capture", "workspace.snapshot", Map.of());
        var executor = executor();
        executor.start(scope, capture);
        var snapshot = terminal(executor, capture);
        assertEquals(State.SUCCEEDED, snapshot.state());
        var value = mapper.readValue(snapshot.result(), Map.class);
        assertFalse(mapper.writeValueAsString(value.get("manifest")).contains(".managed-tools"));
        Files.writeString(directory.resolve("kept.txt"), "changed");
        Files.writeString(directory.resolve("extra.txt"), "extra");
        Files.setPosixFilePermissions(
                directory.resolve("notes"), PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.delete(directory.resolve("notes/data.bin"));
        var restore = task("restore", "workspace.restore", Map.of("snapshot", value));
        executor.start(scope, restore);
        assertEquals(State.SUCCEEDED, terminal(executor, restore).state());
        assertArrayEquals(data, Files.readAllBytes(directory.resolve("notes/data.bin")));
        assertEquals(
                PosixFilePermissions.fromString("r-xr-xr-x"),
                Files.getPosixFilePermissions(directory.resolve("notes")));
        assertEquals("original", Files.readString(directory.resolve("kept.txt")));
        assertFalse(Files.exists(directory.resolve("extra.txt")));
        assertTrue(Files.exists(directory.resolve(".managed-tools/capture/result.json")));
        Files.delete(directory.resolve(".managed-tools/restore/result.json"));
        assertEquals(State.SUCCEEDED, terminal(executor(), restore).state());
        assertTrue(Files.exists(directory.resolve(".managed-tools/restore/result.json")));
        Files.writeString(directory.resolve("kept.txt"), "later work");
        executor.start(scope, restore);
        assertEquals(State.SUCCEEDED, terminal(executor, restore).state());
        assertEquals("later work", Files.readString(directory.resolve("kept.txt")));
        Files.setPosixFilePermissions(
                directory.resolve("notes"), PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    @Test
    void workspaceSnapshotRejectsSymlinksAndInvalidArchiveBeforeChangingFiles() throws Exception {
        Files.writeString(directory.resolve("kept.txt"), "safe");
        var capture = task("capture", "workspace.snapshot", Map.of());
        var executor = executor();
        executor.start(scope, capture);
        var value =
                new LinkedHashMap<String, Object>(
                        mapper.readValue(terminal(executor, capture).result(), Map.class));
        value.put("sha256", "wrong");
        var restore = task("invalid", "workspace.restore", Map.of("snapshot", value));
        executor.start(scope, restore);
        assertEquals(State.FAILED, terminal(executor, restore).state());
        assertEquals("safe", Files.readString(directory.resolve("kept.txt")));
        Files.createSymbolicLink(directory.resolve("link"), directory.resolve("kept.txt"));
        var symlink = task("symlink", "workspace.snapshot", Map.of());
        executor.start(scope, symlink);
        assertEquals(State.FAILED, terminal(executor, symlink).state());
    }

    @Test
    void liveLauncherIsQueryableAndCancellableBeforeTheRunnerWritesItsOwner() throws Exception {
        String program;
        try (var input = getClass().getResourceAsStream("/managed-tools.py")) {
            program = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        program =
                program.replace(
                        "def runner(root, execution):\n    job = job_path(root, execution)\n",
                        "def runner(root, execution):\n"
                                + "    job = job_path(root, execution)\n"
                                + "    while not (root / 'release-start').exists():\n"
                                + "        time.sleep(0.01)\n");
        String delayed =
                Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_8));
        var executor =
                new ManagedSandboxToolExecutor(
                        mapper,
                        command -> {
                            String payload =
                                    command.substring(
                                            command.indexOf("\n") + 1,
                                            command.lastIndexOf("\nMANAGED_REQUEST"));
                            var request = mapper.readTree(Base64.getDecoder().decode(payload));
                            ((ObjectNode) request).put("program", delayed);
                            return control(
                                    "python3 -c 'import base64;exec(base64.b64decode(\""
                                            + delayed
                                            + "\"))' <<'MANAGED_REQUEST'\n"
                                            + Base64.getEncoder()
                                                    .encodeToString(
                                                            mapper.writeValueAsBytes(request))
                                            + "\nMANAGED_REQUEST\n");
                        });
        var task =
                task(
                        "delayed",
                        "sandbox.exec",
                        Map.of("command", "echo repeated >> effects", "timeout_seconds", 5));
        executor.start(scope, task);
        try {
            assertFalse(Files.exists(directory.resolve(".managed-tools/delayed/owner.json")));
            assertEquals(State.RUNNING, executor.query(scope, task).state());
            executor.start(scope, task);
            assertEquals(State.RUNNING, executor.cancel(scope, task).state());
        } finally {
            executor.cancel(scope, task);
            Files.writeString(directory.resolve("release-start"), "release");
            assertEquals(State.CANCELLED, terminal(executor, task).state());
        }
        assertFalse(Files.exists(directory.resolve("effects")));
    }

    @Test
    void launchMarkerWithoutProcessNeverRestartsUnknownEffect() throws Exception {
        Files.createDirectories(directory.resolve(".managed-tools/unknown"));
        var task =
                task(
                        "unknown",
                        "sandbox.exec",
                        Map.of("command", "echo repeated >> effects", "timeout_seconds", 5));
        var executor = executor();
        assertEquals(State.UNKNOWN, executor.query(scope, task).state());
        executor.start(scope, task);
        assertEquals(State.UNKNOWN, executor.query(scope, task).state());
        assertFalse(Files.exists(directory.resolve("effects")));
    }
}
